package confchat;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything Conf-Chat stores in the DHT. Each record knows the DHT key it lives under,
 * so a node can re-insert records it saved earlier without knowing anything else about them.
 *
 * Open Chord keeps a *set* of values per key, so equals()/hashCode() decide what counts as
 * "the same record" (needed when removing one).
 */
public final class Records {
    private Records() { }

    public interface Rec extends Serializable {
        String key();
    }

    // ---- DHT key names -------------------------------------------------------------------
    public static String acctKey(String user)     { return "acct:" + user; }
    public static String nameKey(String word)     { return "name:" + word; }
    public static String presenceKey(String user) { return "presence:" + user; }
    public static String freqKey(String user)     { return "freq:" + user; }
    public static String friendsKey(String user)  { return "friends:" + user; }
    public static String inboxKey(String user)    { return "inbox:" + user; }
    public static String confMemKey(String conf)  { return "confmem:" + conf; }
    public static String userConfKey(String user) { return "userconf:" + user; }

    // ---- records -------------------------------------------------------------------------

    /** A registered user. Password changes insert a higher-version record; readers take the newest. */
    public static final class Account implements Rec {
        private static final long serialVersionUID = 1L;
        public final String username, fullName, password;
        public final long version;

        public Account(String username, String fullName, String password, long version) {
            this.username = username; this.fullName = fullName;
            this.password = password; this.version = version;
        }
        public String key() { return acctKey(username); }
        @Override public boolean equals(Object o) {
            return o instanceof Account a && a.username.equals(username) && a.version == version;
        }
        @Override public int hashCode() { return Objects.hash(username, version); }
    }

    /** Search index: one entry per word of a user's full name, stored under name:<word>. */
    public static final class NameIndex implements Rec {
        private static final long serialVersionUID = 1L;
        public final String word, username, fullName;

        public NameIndex(String word, String username, String fullName) {
            this.word = word; this.username = username; this.fullName = fullName;
        }
        public String key() { return nameKey(word); }
        @Override public boolean equals(Object o) {
            return o instanceof NameIndex n && n.word.equals(word) && n.username.equals(username);
        }
        @Override public int hashCode() { return Objects.hash(word, username); }
    }

    /** "I am logged in" heartbeat. Considered online while the newest one is recent. */
    public static final class Presence implements Rec {
        private static final long serialVersionUID = 1L;
        public final String username, session;
        public final long time;

        public Presence(String username, String session, long time) {
            this.username = username; this.session = session; this.time = time;
        }
        public String key() { return presenceKey(username); }
        @Override public boolean equals(Object o) {
            return o instanceof Presence p && p.username.equals(username)
                    && p.session.equals(session) && p.time == time;
        }
        @Override public int hashCode() { return Objects.hash(username, session, time); }
    }

    /** Pending friend request, stored in the *recipient's* queue (freq:<to>). */
    public static final class FriendRequest implements Rec {
        private static final long serialVersionUID = 1L;
        public final String from, to, fromFullName;
        public final long time;

        public FriendRequest(String from, String to, String fromFullName, long time) {
            this.from = from; this.to = to; this.fromFullName = fromFullName; this.time = time;
        }
        public String key() { return freqKey(to); }
        @Override public boolean equals(Object o) {
            return o instanceof FriendRequest r && r.from.equals(from) && r.to.equals(to);
        }
        @Override public int hashCode() { return Objects.hash(from, to); }
    }

    /** One direction of a friendship (both directions are stored once a request is accepted). */
    public static final class Friend implements Rec {
        private static final long serialVersionUID = 1L;
        public final String owner, friend;

        public Friend(String owner, String friend) { this.owner = owner; this.friend = friend; }
        public String key() { return friendsKey(owner); }
        @Override public boolean equals(Object o) {
            return o instanceof Friend f && f.owner.equals(owner) && f.friend.equals(friend);
        }
        @Override public int hashCode() { return Objects.hash(owner, friend); }
    }

    /** A message waiting in the recipient's inbox (inbox:<to>). Covers chat, conference and notices. */
    public static final class Message implements Rec {
        private static final long serialVersionUID = 1L;
        public final String id, from, to, text, convId, convName;
        public final long time;
        public final boolean system;

        public Message(String from, String to, String text, String convId, String convName, boolean system) {
            this.id = UUID.randomUUID().toString();
            this.from = from; this.to = to; this.text = text;
            this.convId = convId; this.convName = convName; this.system = system;
            this.time = System.currentTimeMillis();
        }
        public String key() { return inboxKey(to); }
        @Override public boolean equals(Object o) { return o instanceof Message m && m.id.equals(id); }
        @Override public int hashCode() { return id.hashCode(); }
    }

    /** Participant of a conference (confmem:<confId>). The set of these *is* the conference. */
    public static final class ConfMember implements Rec {
        private static final long serialVersionUID = 1L;
        public final String confId, username;

        public ConfMember(String confId, String username) { this.confId = confId; this.username = username; }
        public String key() { return confMemKey(confId); }
        @Override public boolean equals(Object o) {
            return o instanceof ConfMember m && m.confId.equals(confId) && m.username.equals(username);
        }
        @Override public int hashCode() { return Objects.hash(confId, username); }
    }

    /** Index so a user can list their conferences (userconf:<username>). */
    public static final class Membership implements Rec {
        private static final long serialVersionUID = 1L;
        public final String username, confId, confName;

        public Membership(String username, String confId, String confName) {
            this.username = username; this.confId = confId; this.confName = confName;
        }
        public String key() { return userConfKey(username); }
        @Override public boolean equals(Object o) {
            return o instanceof Membership m && m.username.equals(username) && m.confId.equals(confId);
        }
        @Override public int hashCode() { return Objects.hash(username, confId); }
    }
}
