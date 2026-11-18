package confchat;

import confchat.ChatService.*;
import confchat.Records.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Command-line front end. */
public final class Cli implements Listener {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());

    private final ChatService svc;
    private final BufferedReader in;
    private volatile String prompt = "> ";

    public Cli(ChatService svc, BufferedReader in) { this.svc = svc; this.in = in; }

    // ---- output (background threads print here too, so keep it in one place) ----------------

    private synchronized void println(String s) { System.out.println(s); }

    /** Used for things that arrive while the user may be typing: print on a fresh line, then redraw the prompt. */
    private synchronized void async(String s) {
        System.out.print("\r" + s + "\n" + prompt);
        System.out.flush();
    }

    private String ask(String label) throws IOException {
        synchronized (this) { System.out.print(label); System.out.flush(); }
        String line = in.readLine();
        if (line == null) throw new EOFException();
        return line.trim();
    }

    private static final class EOFException extends IOException { }

    private static String when(long t) { return TIME.format(Instant.ofEpochMilli(t)); }

    // ---- Listener (messages and friend status arriving in the background) ---------------------

    @Override public void onMessage(Message m) { async(format(m, false)); }

    @Override public void onFriendStatus(String friend, boolean online) {
        async("* " + friend + " is now " + (online ? "ONLINE" : "offline"));
    }

    private static String format(Message m, boolean withTime) {
        String stamp = withTime ? "(" + when(m.time) + ") " : "";
        String conf = m.convName == null ? "" : "[" + m.convName + "] ";
        if (m.system) return stamp + conf + "* " + m.text;
        return stamp + conf + m.from + ": " + m.text;
    }

    // ---- main loop -----------------------------------------------------------------------

    public void run() {
        println("Commands: register, login, quit");
        try {
            while (true) {
                prompt = "> ";
                String cmd = ask(prompt).toLowerCase(Locale.ROOT);
                try {
                    switch (cmd) {
                        case "" -> { }
                        case "register" -> register();
                        case "login" -> { if (login()) session(); }
                        case "quit", "exit" -> { return; }
                        default -> println("Type register, login or quit.");
                    }
                } catch (ChatException | Dht.DhtException e) {
                    println("! " + e.getMessage());
                }
            }
        } catch (IOException e) {
            // end of input: just quit
        } finally {
            svc.logout();
        }
    }

    private void register() throws IOException {
        String u = ask("Choose a username: ");
        String full = ask("Full name: ");
        String pw = ask("Choose a password: ");
        svc.register(u, pw, full);
        println("Registered. You can log in now.");
    }

    private boolean login() throws IOException {
        String u = ask("Username: ");
        String pw = ask("Password: ");
        LoginResult r = svc.login(u, pw, this);
        prompt = svc.currentUser() + "> ";
        println("Welcome, " + r.fullName + "!");
        if (!r.offlineMessages.isEmpty()) {
            println("--- " + r.offlineMessages.size() + " message(s) received while you were away ---");
            for (Message m : r.offlineMessages) println(format(m, true));
            println("---");
        }
        printFriends(r.friends);
        if (r.pendingRequests > 0) println("You have " + r.pendingRequests + " pending friend request(s). Type 'requests'.");
        println("Type 'help' for commands.");
        return true;
    }

    private void printFriends(List<FriendInfo> friends) {
        if (friends.isEmpty()) { println("You have no friends yet. Try: find <name words>"); return; }
        long online = friends.stream().filter(f -> f.online).count();
        println("Friends (" + online + " of " + friends.size() + " online):");
        for (FriendInfo f : friends)
            println(String.format("  %-8s %-16s %s", f.online ? "ONLINE" : "offline", f.username, f.fullName));
    }

    private void session() throws IOException {
        try {
            while (true) {
                String line = ask(prompt);
                if (line.isEmpty()) continue;
                String[] p = line.split("\\s+", 3);
                String cmd = p[0].toLowerCase(Locale.ROOT);
                try {
                    if (!command(cmd, p, line)) return;
                } catch (ChatException | Dht.DhtException e) {
                    println("! " + e.getMessage());
                }
            }
        } finally {
            svc.logout();
        }
    }

    private static String arg(String[] p, int i, String usage) {
        if (p.length <= i) throw new ChatException("Usage: " + usage);
        return p[i];
    }

    /** @return false to log out */
    private boolean command(String cmd, String[] p, String line) throws IOException {
        switch (cmd) {
            case "help", "?" -> help();
            case "friends" -> printFriends(svc.friends());
            case "find" -> find(line.substring(cmd.length()).trim());
            case "addfriend" -> {
                String u = arg(p, 1, "addfriend <username>");
                svc.requestFriend(u);
                println("Friend request sent to " + u + ".");
            }
            case "requests" -> {
                List<FriendRequest> rs = svc.pendingRequests();
                if (rs.isEmpty()) println("No pending friend requests.");
                for (FriendRequest r : rs) println("  " + r.from + " (" + r.fromFullName + ")  - accept " + r.from + " / reject " + r.from);
            }
            case "accept" -> { String u = arg(p, 1, "accept <username>"); svc.acceptFriend(u); println(u + " is now your friend."); }
            case "reject" -> { String u = arg(p, 1, "reject <username>"); svc.rejectFriend(u); println("Request from " + u + " rejected."); }
            case "msg" -> {
                String to = arg(p, 1, "msg <friend> <text>");
                String text = arg(p, 2, "msg <friend> <text>");
                boolean online = svc.sendDirect(to, text);
                println(online ? "sent" : "sent - " + to + " is offline and will get it when they log in");
            }
            case "conf" -> conf(p, line);
            case "say" -> {
                String c = arg(p, 1, "say <conference> <text>");
                String text = arg(p, 2, "say <conference> <text>");
                svc.sendConference(c, text);
            }
            case "passwd" -> {
                String o = ask("Current password: ");
                String n = ask("New password: ");
                svc.changePassword(o, n);
                println("Password changed.");
            }
            case "logout" -> { println("Logged out."); return false; }
            case "quit", "exit" -> { throw new EOFException(); }
            default -> println("Unknown command. Type 'help'.");
        }
        return true;
    }

    private void find(String query) {
        List<NameIndex> hits = svc.searchUsers(query);
        if (hits.isEmpty()) { println("No users found."); return; }
        Set<String> friends = new HashSet<>();
        for (FriendInfo f : svc.friends()) friends.add(f.username);
        for (NameIndex n : hits)
            println(String.format("  %-16s %s%s", n.username, n.fullName, friends.contains(n.username) ? "   (already a friend)" : ""));
        println("Use: addfriend <username>");
    }

    private void conf(String[] p, String line) {
        String sub = arg(p, 1, "conf new|list|add|say|leave ...").toLowerCase(Locale.ROOT);
        String[] a = line.split("\\s+");
        switch (sub) {
            case "new" -> {
                if (a.length < 3) throw new ChatException("Usage: conf new <name> [user ...]");
                List<String> invite = new ArrayList<>(Arrays.asList(a).subList(3, a.length));
                ConfInfo c = svc.createConference(a[2], invite);
                println("Created conference '" + c.name + "' (id " + c.id + "), members: " + String.join(", ", c.members));
            }
            case "list" -> {
                List<ConfInfo> cs = svc.conferences();
                if (cs.isEmpty()) println("You are not in any conference.");
                for (ConfInfo c : cs) println("  " + c.id + "  " + c.name + "  members: " + String.join(", ", c.members));
            }
            case "add" -> {
                if (a.length < 4) throw new ChatException("Usage: conf add <conference> <user>");
                svc.addToConference(a[2], a[3]);
                println(a[3] + " added.");
            }
            case "say" -> {
                String[] q = line.split("\\s+", 4);
                if (q.length < 4) throw new ChatException("Usage: conf say <conference> <text>");
                svc.sendConference(q[2], q[3]);
            }
            case "leave" -> {
                if (a.length < 3) throw new ChatException("Usage: conf leave <conference>");
                svc.leaveConference(a[2]);
                println("You left the conference.");
            }
            default -> throw new ChatException("Usage: conf new|list|add|say|leave ...");
        }
    }

    private void help() {
        println("""
                Friends
                  friends                        who your friends are and who is online
                  find <words>                   search users by words of their full name
                  addfriend <username>           send a friend request
                  requests                       show pending requests;  accept <user> / reject <user>
                Messages
                  msg <friend> <text>            send to a friend (delivered at next login if offline)
                Conferences (names are one word; <conference> = id or name)
                  conf new <name> [users...]     create a conference (invited users must be online)
                  conf list                      your conferences and their members
                  conf add <conference> <user>   add an online user
                  say <conference> <text>        message everyone in it   (also: conf say ...)
                  conf leave <conference>
                Account
                  passwd                         change your password
                  logout   quit""");
    }
}
