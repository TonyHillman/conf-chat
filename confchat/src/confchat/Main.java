package confchat;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Conf-Chat: a serverless chat built on the Open Chord DHT.
 *
 *   --port N          listen on this port (default: a free port between 4242 and 4261)
 *   --host H          address other nodes use to reach this one: an IP, or "auto" for the LAN address
 *                     (default 127.0.0.1, fine when every node runs on this machine)
 *   --bootstrap H:P   join the network through this node (default: look for one on this machine)
 *   --data DIR        where the shutdown snapshot is kept (default confchat-data)
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        String host = null, bootstrap = null, dataDir = "confchat-data";
        int port = 0;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(next(args, ++i));
                case "--host" -> host = next(args, ++i);
                case "--bootstrap" -> bootstrap = next(args, ++i);
                case "--data" -> dataDir = next(args, ++i);
                default -> {
                    System.out.println("Usage: run [--port N] [--host IP|auto] [--bootstrap host:port] [--data DIR]");
                    return;
                }
            }
        }

        System.out.println("Starting Conf-Chat node...");
        Dht dht;
        try {
            dht = Dht.open(host, port, bootstrap);
        } catch (Dht.DhtException e) {
            System.out.println("Could not start: " + e.getMessage());
            return;
        }
        File snapshot = new File(dataDir, "snapshot.dat");
        if (dht.createdNetwork) {
            int n = dht.restoreSnapshot(snapshot);
            System.out.println("Created a new network at " + dht.url
                    + (n > 0 ? " (restored " + n + " saved records)" : ""));
        } else {
            System.out.println("Joined the network via " + dht.joinedVia + " as " + dht.url);
        }

        ChatService svc = new ChatService(dht);
        AtomicBoolean down = new AtomicBoolean();
        Runnable stop = () -> {
            if (down.compareAndSet(false, true)) {
                svc.logout();
                dht.shutdown(snapshot);
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(stop));    // Ctrl+C
        try {
            new Cli(svc, new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()))).run();
        } finally {
            stop.run();
            System.out.println("Bye.");
            System.exit(0);       // Open Chord's threads would otherwise keep the JVM alive
        }
    }

    private static String next(String[] a, int i) {
        if (i >= a.length) throw new IllegalArgumentException("Missing value after " + a[i - 1]);
        return a[i];
    }
}
