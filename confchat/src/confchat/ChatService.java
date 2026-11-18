package confchat;

import confchat.Records.*;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** All Conf-Chat behaviour. Everything is stored in / read from the DHT; there is no server. */
public final class ChatService {

    /** An error to show the user as-is. */
    public static final class ChatException extends RuntimeException {
        public ChatException(String msg) { super(msg); }
    }

    /** Called from background threads. */
    public interface Listener {
        void onMessage(Message m);
        void onFriendStatus(String friend, boolean online);
    }

    public static final class LoginResult {
        public String fullName;
        public List<Message> offlineMessages = new ArrayList<>();
        public List<FriendInfo> friends = new ArrayList<>();
        public int pendingRequests;
    }

    public static final class FriendInfo {
        public final String username, fullName;
        public final boolean online;
        FriendInfo(String u, String f, boolean o) { username = u; fullName = f; online = o; }
    }

    public static final class ConfInfo {
        public final String id, name;
        public final List<String> members;
        ConfInfo(String id, String name, List<String> members) { this.id = id; this.name = name; this.members = members; }
    }

    // ---- tuning --------------------------------------------------------------------------
    static final long HEARTBEAT_MS = 5_000;       // how often we say "still here"
    static final long ONLINE_TTL_MS = 20_000;     // a heartbeat older than this means offline (clocks should agree)
    static final long INBOX_POLL_MS = 1_000;      // how often we look for new messages
    static final long WATCH_MS = 5_000;           // how often we look for friends coming/going
    /** If a user leaves the system, they automatically leave the conference. Set false to keep them in. */
    static final boolean LEAVE_CONFS_ON_LOGOUT = true;
    static final boolean DEBUG = Boolean.getBoolean("confchat.debug");

    private static final Pattern USERNAME = Pattern.compile("[a-z0-9_.-]{3,20}");
    private static final Random RND = new Random();

    private final Dht dht;
    private final String session = UUID.randomUUID().toString();
    private volatile String user;
    private String fullName;
    private Listener listener;
    private Presence lastPresence;
    private ScheduledExecutorService scheduler;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final Map<String, Boolean> lastStatus = new ConcurrentHashMap<>();

    public ChatService(Dht dht) { this.dht = dht; }

    public String currentUser() { return user; }

    // ---- helpers -------------------------------------------------------------------------

    private static String norm(String u) { return u == null ? "" : u.trim().toLowerCase(Locale.ROOT); }

    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
            if (!w.isEmpty() && !out.contains(w)) out.add(w);
        return out;
    }

    private <T extends Rec> List<T> getAll(String key, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Serializable s : dht.get(key)) if (type.isInstance(s)) out.add(type.cast(s));
        return out;
    }

    private Account newestAccount(String u) {
        Account best = null;
        for (Account a : getAll(Records.acctKey(u), Account.class))
            if (best == null || a.version > best.version) best = a;
        return best;
    }

    private void requireLogin() {
        if (user == null) throw new ChatException("You are not logged in.");
    }

    private void debug(Throwable t) { if (DEBUG) t.printStackTrace(); }

    private void notifyUser(String to, String text) {
        dht.put(new Message("system", to, text, null, null, true));
    }

    // ---- accounts ------------------------------------------------------------------------

    public void register(String username, String password, String full) {
        String u = norm(username);
        if (!USERNAME.matcher(u).matches())
            throw new ChatException("Username must be 3-20 characters: letters, digits, '_', '.' or '-'.");
        if (password == null || password.isEmpty()) throw new ChatException("Password cannot be empty.");
        String fn = full == null ? "" : full.trim().replaceAll("\\s+", " ");
        if (fn.isEmpty()) throw new ChatException("Full name cannot be empty.");
        if (newestAccount(u) != null) throw new ChatException("That username is already taken.");
        dht.put(new Account(u, fn, password, System.currentTimeMillis()));
        for (String w : words(fn)) dht.put(new NameIndex(w, u, fn));
    }

    public synchronized LoginResult login(String username, String password, Listener l) {
        if (user != null) throw new ChatException("Already logged in as " + user + ".");
        String u = norm(username);
        Account acct = newestAccount(u);
        if (acct == null || !acct.password.equals(password)) throw new ChatException("Wrong username or password.");

        long now = System.currentTimeMillis();
        List<Presence> old = getAll(Records.presenceKey(u), Presence.class);
        for (Presence p : old)
            if (now - p.time < ONLINE_TTL_MS)
                throw new ChatException("'" + u + "' is already logged in elsewhere (if that session crashed, "
                        + "wait ~" + (ONLINE_TTL_MS / 1000) + " seconds and try again).");
        for (Presence p : old) dht.del(p);        // leftovers from a crashed session

        user = u; fullName = acct.fullName; listener = l;
        seen.clear(); lastStatus.clear(); lastPresence = null;
        LoginResult res = new LoginResult();
        res.fullName = fullName;
        try {
            leaveAllConferences(true);             // anything we were in before is over
            heartbeat();
            res.offlineMessages = drainInbox(true);
            res.friends = friendInfos();
            res.pendingRequests = getAll(Records.freqKey(u), FriendRequest.class).size();
        } catch (RuntimeException e) {
            user = null;
            throw e;
        }

        scheduler = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "confchat-bg");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(guard(this::heartbeat), HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(guard(this::pollInbox), INBOX_POLL_MS, INBOX_POLL_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(guard(this::watchFriends), WATCH_MS, WATCH_MS, TimeUnit.MILLISECONDS);
        return res;
    }

    private Runnable guard(Runnable r) {
        return () -> {
            try { r.run(); } catch (Throwable t) { debug(t); }
        };
    }

    public synchronized void logout() {
        if (user == null) return;
        if (scheduler != null) {
            scheduler.shutdownNow();
            try { scheduler.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        }
        try { if (LEAVE_CONFS_ON_LOGOUT) leaveAllConferences(false); } catch (RuntimeException e) { debug(e); }
        try {
            for (Presence p : getAll(Records.presenceKey(user), Presence.class)) dht.del(p);
        } catch (RuntimeException e) { debug(e); }
        user = null;
    }

    public void changePassword(String oldPw, String newPw) {
        requireLogin();
        if (newPw == null || newPw.isEmpty()) throw new ChatException("New password cannot be empty.");
        List<Account> all = getAll(Records.acctKey(user), Account.class);
        Account cur = null;
        for (Account a : all) if (cur == null || a.version > cur.version) cur = a;
        if (cur == null || !cur.password.equals(oldPw)) throw new ChatException("Current password is incorrect.");
        dht.put(new Account(user, cur.fullName, newPw, Math.max(cur.version + 1, System.currentTimeMillis())));
        for (Account a : all) dht.del(a);
    }

    // ---- presence ------------------------------------------------------------------------

    private void heartbeat() {
        if (user == null) return;
        Presence p = new Presence(user, session, System.currentTimeMillis());
        dht.put(p);
        Presence prev = lastPresence;
        lastPresence = p;
        if (prev != null) dht.del(prev);
    }

    private boolean isOnline(String u) {
        long now = System.currentTimeMillis();
        for (Presence p : getAll(Records.presenceKey(u), Presence.class))
            if (now - p.time < ONLINE_TTL_MS) return true;
        return false;
    }

    // ---- friends -------------------------------------------------------------------------

    private List<String> friendNames() {
        List<String> out = new ArrayList<>();
        for (Friend f : getAll(Records.friendsKey(user), Friend.class)) out.add(f.friend);
        Collections.sort(out);
        return out;
    }

    private List<FriendInfo> friendInfos() {
        List<FriendInfo> out = new ArrayList<>();
        for (String f : friendNames()) {
            Account a = newestAccount(f);
            boolean on = isOnline(f);
            lastStatus.put(f, on);
            out.add(new FriendInfo(f, a == null ? "?" : a.fullName, on));
        }
        return out;
    }

    public List<FriendInfo> friends() { requireLogin(); return friendInfos(); }

    /** Users whose full name contains every word of the query (whole-word, case-insensitive). */
    public List<NameIndex> searchUsers(String query) {
        requireLogin();
        List<String> ws = words(query == null ? "" : query);
        if (ws.isEmpty()) throw new ChatException("Give at least one word to search for.");
        Map<String, NameIndex> result = null;
        for (String w : ws) {
            Map<String, NameIndex> hits = new TreeMap<>();
            for (NameIndex n : getAll(Records.nameKey(w), NameIndex.class)) hits.put(n.username, n);
            if (result == null) result = hits; else result.keySet().retainAll(hits.keySet());
        }
        List<NameIndex> out = new ArrayList<>();
        for (NameIndex n : result.values()) if (!n.username.equals(user)) out.add(n);
        out.sort(Comparator.comparing(n -> n.fullName.toLowerCase(Locale.ROOT)));
        return out;
    }

    public void requestFriend(String target) {
        requireLogin();
        String t = norm(target);
        if (t.equals(user)) throw new ChatException("You cannot add yourself.");
        if (newestAccount(t) == null) throw new ChatException("No such user: " + t);
        if (friendNames().contains(t)) throw new ChatException(t + " is already your friend.");
        for (FriendRequest r : getAll(Records.freqKey(user), FriendRequest.class))
            if (r.from.equals(t)) throw new ChatException(t + " already asked you - type: accept " + t);
        for (FriendRequest r : getAll(Records.freqKey(t), FriendRequest.class))
            if (r.from.equals(user)) throw new ChatException("You already sent " + t + " a request.");
        dht.put(new FriendRequest(user, t, fullName, System.currentTimeMillis()));
        notifyUser(t, user + " (" + fullName + ") wants to be your friend. Type 'requests' to see it.");
    }

    public List<FriendRequest> pendingRequests() {
        requireLogin();
        List<FriendRequest> l = getAll(Records.freqKey(user), FriendRequest.class);
        l.sort(Comparator.comparingLong(r -> r.time));
        return l;
    }

    public void acceptFriend(String from) {
        requireLogin();
        String f = norm(from);
        FriendRequest req = null;
        for (FriendRequest r : getAll(Records.freqKey(user), FriendRequest.class)) if (r.from.equals(f)) req = r;
        if (req == null) throw new ChatException("No pending request from " + f + ".");
        dht.put(new Friend(user, f));
        dht.put(new Friend(f, user));
        dht.del(req);
        notifyUser(f, user + " accepted your friend request. You are now friends.");
    }

    public void rejectFriend(String from) {
        requireLogin();
        String f = norm(from);
        boolean found = false;
        for (FriendRequest r : getAll(Records.freqKey(user), FriendRequest.class))
            if (r.from.equals(f)) { dht.del(r); found = true; }
        if (!found) throw new ChatException("No pending request from " + f + ".");
    }

    private void watchFriends() {
        if (user == null) return;
        for (String f : friendNames()) {
            boolean on = isOnline(f);
            Boolean prev = lastStatus.put(f, on);
            if (prev != null && prev != on && listener != null) listener.onFriendStatus(f, on);
        }
    }

    // ---- direct messages -----------------------------------------------------------------

    /** @return true if the friend is online right now (otherwise the message waits for their next login) */
    public boolean sendDirect(String to, String text) {
        requireLogin();
        String t = norm(to);
        if (text == null || text.isBlank()) throw new ChatException("Nothing to send.");
        if (!friendNames().contains(t)) throw new ChatException(t + " is not one of your friends.");
        dht.put(new Message(user, t, text.trim(), null, null, false));
        return isOnline(t);
    }

    // ---- inbox ---------------------------------------------------------------------------

    private List<Message> drainInbox(boolean atLogin) {
        List<Message> msgs = getAll(Records.inboxKey(user), Message.class);
        msgs.sort(Comparator.comparingLong(m -> m.time));
        List<Message> out = new ArrayList<>();
        for (Message m : msgs) {
            try { dht.del(m); } catch (RuntimeException e) { debug(e); continue; }   // try again next poll
            if (!seen.add(m.id)) continue;
            if (atLogin && m.convId != null) continue;   // conference chatter from while we were away
            out.add(m);
        }
        return out;
    }

    private void pollInbox() {
        if (user == null) return;
        for (Message m : drainInbox(false)) if (listener != null) listener.onMessage(m);
    }

    // ---- conferences ---------------------------------------------------------------------

    private List<String> members(String confId) {
        List<String> out = new ArrayList<>();
        for (ConfMember m : getAll(Records.confMemKey(confId), ConfMember.class)) out.add(m.username);
        Collections.sort(out);
        return out;
    }

    /** Finds one of my conferences by id or name. */
    private Membership resolve(String ref) {
        String r = norm(ref);
        List<Membership> mine = getAll(Records.userConfKey(user), Membership.class);
        for (Membership m : mine) if (m.confId.equals(r)) return m;
        Membership byName = null;
        for (Membership m : mine)
            if (m.confName.equalsIgnoreCase(r)) {
                if (byName != null) throw new ChatException("Several conferences are called '" + ref + "'; use the id.");
                byName = m;
            }
        if (byName == null) throw new ChatException("You are not in a conference '" + ref + "'.");
        return byName;
    }

    private void tellConference(Membership m, String text, String except) {
        for (String member : members(m.confId))
            if (!member.equals(user) && !member.equals(except))
                dht.put(new Message("system", member, text, m.confId, m.confName, true));
    }

    public ConfInfo createConference(String name, List<String> invite) {
        requireLogin();
        if (name == null || name.isBlank()) throw new ChatException("A conference needs a name.");
        String id = String.format("%06x", RND.nextInt(1 << 24));
        dht.put(new ConfMember(id, user));
        dht.put(new Membership(user, id, name.trim()));
        for (String u : invite) addToConference(id, u);
        return new ConfInfo(id, name.trim(), members(id));
    }

    public void addToConference(String ref, String target) {
        requireLogin();
        Membership m = resolve(ref);
        String t = norm(target);
        if (newestAccount(t) == null) throw new ChatException("No such user: " + t);
        if (members(m.confId).contains(t)) throw new ChatException(t + " is already in '" + m.confName + "'.");
        if (!isOnline(t)) throw new ChatException(t + " is not online. Conferences only include people who are logged in; "
                + "add them once they are.");
        dht.put(new ConfMember(m.confId, t));
        dht.put(new Membership(t, m.confId, m.confName));
        dht.put(new Message("system", t, user + " added you to conference '" + m.confName + "' (id " + m.confId + ").",
                m.confId, m.confName, true));
        tellConference(m, user + " added " + t + " to the conference.", t);
    }

    /** @return number of people the message was sent to */
    public int sendConference(String ref, String text) {
        requireLogin();
        if (text == null || text.isBlank()) throw new ChatException("Nothing to send.");
        Membership m = resolve(ref);
        int n = 0;
        for (String member : members(m.confId))
            if (!member.equals(user)) {
                dht.put(new Message(user, member, text.trim(), m.confId, m.confName, false));
                n++;
            }
        return n;
    }

    public void leaveConference(String ref) {
        requireLogin();
        Membership m = resolve(ref);
        leave(m, user + " left the conference.");
    }

    private void leave(Membership m, String announcement) {
        dht.del(new ConfMember(m.confId, user));
        dht.del(m);
        if (announcement != null) tellConference(m, announcement, null);
    }

    private void leaveAllConferences(boolean atLogin) {
        for (Membership m : getAll(Records.userConfKey(user), Membership.class))
            leave(m, atLogin ? null : user + " left the conference (logged out).");
    }

    public List<ConfInfo> conferences() {
        requireLogin();
        List<ConfInfo> out = new ArrayList<>();
        for (Membership m : getAll(Records.userConfKey(user), Membership.class))
            out.add(new ConfInfo(m.confId, m.confName, members(m.confId)));
        out.sort(Comparator.comparing(c -> c.name.toLowerCase(Locale.ROOT)));
        return out;
    }
}
