package confchat;

import de.uniba.wiai.lspi.chord.com.Entry;
import de.uniba.wiai.lspi.chord.data.URL;
import de.uniba.wiai.lspi.chord.service.Key;
import de.uniba.wiai.lspi.chord.service.ServiceException;
import de.uniba.wiai.lspi.chord.service.impl.ChordImpl;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.Callable;

/** Thin wrapper around one Open Chord node: start-up, retries, and an on-disk snapshot. */
public final class Dht {

    public static final class DhtException extends RuntimeException {
        public DhtException(String msg, Throwable cause) { super(msg, cause); }
        public DhtException(String msg) { super(msg); }
    }

    /** Open Chord needs a Key; ours is just a string.*/
    public static final class StrKey implements Key, Serializable {
        private static final long serialVersionUID = 1L;
        private final String s;
        public StrKey(String s) { this.s = s; }
        public byte[] getBytes() { return s.getBytes(StandardCharsets.UTF_8); }
        @Override public int hashCode() { return s.hashCode(); }
        @Override public boolean equals(Object o) { return o instanceof StrKey k && k.s.equals(s); }
        @Override public String toString() { return s; }
    }

    public static final int FIRST_PORT = 4242, LAST_PORT = 4261;

    private final ChordImpl chord;
    public final String url;
    public final boolean createdNetwork;
    public final String joinedVia;          // null if we created the network
    private boolean closed;

    private Dht(ChordImpl chord, String url, boolean created, String joinedVia) {
        this.chord = chord; this.url = url; this.createdNetwork = created; this.joinedVia = joinedVia;
    }

    // ---- start-up ------------------------------------------------------------------------

    /** Open Chord reads these once, when its classes load, so they must be set before the first node. */
    private static void configure() {
        String[][] p = {
            {"de.uniba.wiai.lspi.util.logging.off", "true"},
            {"de.uniba.wiai.lspi.chord.data.ID.number.of.displayed.bytes", "4"},
            {"de.uniba.wiai.lspi.chord.data.ID.displayed.representation", "2"},
            {"de.uniba.wiai.lspi.chord.service.impl.ChordImpl.successors", "3"},
            {"de.uniba.wiai.lspi.chord.service.impl.ChordImpl.AsyncThread.no", "10"},
            {"de.uniba.wiai.lspi.chord.service.impl.ChordImpl.StabilizeTask.start", "1"},
            {"de.uniba.wiai.lspi.chord.service.impl.ChordImpl.StabilizeTask.interval", "2"},
            {"de.uniba.wiai.lspi.chord.service.impl.ChordImpl.FixFingerTask.start", "0"},
            {"de.uniba.wiai.lspi.chord.service.impl.ChordImpl.FixFingerTask.interval", "2"},
            {"de.uniba.wiai.lspi.chord.service.impl.ChordImpl.CheckPredecessorTask.start", "1"},
            {"de.uniba.wiai.lspi.chord.service.impl.ChordImpl.CheckPredecessorTask.interval", "2"},
            {"de.uniba.wiai.lspi.chord.com.socket.InvocationThread.corepoolsize", "10"},
            {"de.uniba.wiai.lspi.chord.com.socket.InvocationThread.maxpoolsize", "50"},
            {"de.uniba.wiai.lspi.chord.com.socket.InvocationThread.keepalivetime", "20"},
        };
        for (String[] kv : p) System.setProperty(kv[0], kv[1]);
    }

    /**
     * Starts a node. If {@code bootstrap} ("host:port") is given we must join through it; otherwise we look
     * for a running node on this machine (ports 4242-4261) and, finding none, create a new network.
     *
     * @param host this node's address as other nodes will see it (null = 127.0.0.1, "auto" = LAN address)
     * @param port fixed listening port, or 0 to pick a free one
     */
    public static Dht open(String host, int port, String bootstrap) {
        configure();
        if (host == null) host = "127.0.0.1";
        else if (host.equals("auto")) host = detectLanAddress();

        List<String> candidates = new ArrayList<>();
        if (bootstrap != null) candidates.add(bootstrap);
        else candidates.addAll(scanLocalPorts());

        Set<Integer> tried = new HashSet<>();      // Open Chord refuses to reuse an address within one JVM
        for (String cand : candidates) {
            String[] hp = cand.split(":");
            if (hp.length != 2) throw new DhtException("Bootstrap must look like host:port, got '" + cand + "'");
            String bh = hp[0];
            int bp = Integer.parseInt(hp[1]);
            if (!canConnect(bh, bp)) {
                if (bootstrap != null) throw new DhtException("Cannot reach bootstrap node " + cand);
                continue;
            }
            // A join can fail while the ring is still settling after another node left (the ring may still
            // remember a node that used the same address), so retry a few times on fresh ports.
            Exception last = null;
            for (int attempt = 1; attempt <= 4; attempt++) {
                int own = pickPort(port, tried);
                tried.add(own);
                ChordImpl chord = new ChordImpl();
                try {
                    URL local = new URL("ocsocket://" + host + ":" + own + "/");
                    chord.join(local, new URL("ocsocket://" + bh + ":" + bp + "/"));
                    return new Dht(chord, local.toString(), false, cand);
                } catch (Exception e) {
                    last = e;
                    if (ChatService.DEBUG) { System.err.println("join via " + cand + " failed:"); e.printStackTrace(); }
                    try { chord.leave(); } catch (Throwable ignored) { }
                    if (port > 0) break;           // fixed port: cannot move to another one
                    try { Thread.sleep(2500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
            if (bootstrap != null)
                throw new DhtException("Could not join via " + cand + ": " + (last == null ? "?" : last.getMessage()), last);
            // otherwise whatever was listening there is not a working Conf-Chat node; try the next port
        }
        int own = pickPort(port, tried);
        ChordImpl chord = new ChordImpl();
        try {
            URL local = new URL("ocsocket://" + host + ":" + own + "/");
            chord.create(local);
            return new Dht(chord, local.toString(), true, null);
        } catch (Exception e) {
            throw new DhtException("Could not create a network: " + e.getMessage(), e);
        }
    }

    /** Which ports in our range have something listening? Checked in parallel: refused connections can be slow on Windows. */
    private static List<String> scanLocalPorts() {
        int n = LAST_PORT - FIRST_PORT + 1;
        boolean[] open = new boolean[n];
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            Thread t = new Thread(() -> open[idx] = canConnect("127.0.0.1", FIRST_PORT + idx));
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) if (open[i]) out.add("127.0.0.1:" + (FIRST_PORT + i));
        return out;
    }

    private static boolean canConnect(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 800);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isFree(int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int pickPort(int wanted, Set<Integer> tried) {
        if (wanted > 0) {
            if (!isFree(wanted)) throw new DhtException("Port " + wanted + " is already in use.");
            return wanted;
        }
        // Random rather than "first free": a just-departed node's address can still be remembered by the ring.
        List<Integer> free = new ArrayList<>();
        for (int p = FIRST_PORT; p <= LAST_PORT; p++) if (!tried.contains(p) && isFree(p)) free.add(p);
        if (free.isEmpty()) throw new DhtException("No free port between " + FIRST_PORT + " and " + LAST_PORT + "; use --port.");
        return free.get(new Random().nextInt(free.size()));
    }

    private static String detectLanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (IOException ignored) { }
        return "127.0.0.1";
    }

    // ---- storage operations --------------------------------------------------------------

    /** Chord can fail briefly while the ring re-stabilises after a join or leave, so retry a few times. */
    private <T> T retry(Callable<T> op) {
        Exception last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                return op.call();
            } catch (Exception e) {
                last = e;
                try { Thread.sleep(300L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new DhtException("Network operation failed: " + (last == null ? "?" : last.getMessage()), last);
    }

    public Set<Serializable> get(String key) {
        return retry(() -> chord.retrieve(new StrKey(key)));
    }

    public void put(Records.Rec rec) {
        retry(() -> { chord.insert(new StrKey(rec.key()), rec); return null; });
    }

    public void del(Records.Rec rec) {
        retry(() -> { chord.remove(new StrKey(rec.key()), rec); return null; });
    }

    // ---- snapshot (survives every node being shut down) ------------------------------------

    /**
     * Everything this node currently stores (its own share plus replicas of its neighbours').
     * Open Chord offers no public way to read this, hence the reflection.
     */
    @SuppressWarnings("unchecked")
    private List<Records.Rec> localRecords() throws Exception {
        Field f = ChordImpl.class.getDeclaredField("entries");
        f.setAccessible(true);
        Object entries = f.get(chord);
        Method m = entries.getClass().getDeclaredMethod("getEntries");
        m.setAccessible(true);
        Map<?, Set<Entry>> map = (Map<?, Set<Entry>>) m.invoke(entries);
        List<Records.Rec> out = new ArrayList<>();
        for (Set<Entry> set : new ArrayList<>(map.values()))
            for (Entry e : new ArrayList<>(set))
                if (e.getValue() instanceof Records.Rec r && !(r instanceof Records.Presence)) out.add(r);
        return out;
    }

    public void saveSnapshot(File file) {
        try {
            List<Records.Rec> recs = localRecords();
            File dir = file.getAbsoluteFile().getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(file.getPath() + ".tmp");
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(tmp))) {
                out.writeObject(new ArrayList<>(recs));
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            System.err.println("(warning: could not save snapshot: " + t + ")");
        }
    }

    /** Re-inserts a snapshot. Only the node that creates a brand-new network does this. */
    @SuppressWarnings("unchecked")
    public int restoreSnapshot(File file) {
        if (!file.isFile()) return 0;
        int n = 0;
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(file))) {
            for (Records.Rec r : (List<Records.Rec>) in.readObject()) {
                put(r);
                n++;
            }
        } catch (Throwable t) {
            System.err.println("(warning: could not restore snapshot: " + t + ")");
        }
        return n;
    }

    public synchronized void shutdown(File snapshot) {
        if (closed) return;
        closed = true;
        if (snapshot != null) saveSnapshot(snapshot);
        try { chord.leave(); } catch (Throwable ignored) { }
    }
}
