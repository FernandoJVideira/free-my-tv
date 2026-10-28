package io.freemytv;

import android.content.Context;
import android.os.SystemClock;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Mi Box's Netflix and Live TV buttons. Xiaomi's system opens those apps itself, before any app's key filter sees
 * the key (its log: "it's a netflix key", then a START from uid 1000), so RemoteKeyService never gets them. They do
 * show in the remote's raw input, which the TV's own ADB can read: this watches `getevent` there and, when a button is
 * set to something, opens that once the system's own launch has landed. The app Xiaomi opened stays behind it; Netflix,
 * if it wasn't running, is stopped (the app, not turned off: it starts again from its own icon).
 * Runs in the key service's process (:keys), only on the Mi Box.
 */
final class KeyWatcher {
    private KeyWatcher() {}

    private static final String NETFLIX = "com.netflix.ninja";
    /** The system starts its app when the key goes up: ours goes on top of it a moment later. */
    private static final long AFTER_UP_MS = 300, STOP_AFTER_MS = 700;

    private static final ExecutorService ACTIONS = Executors.newSingleThreadExecutor();
    private static Thread thread;
    private static Adb session;
    private static volatile boolean on;
    private static boolean netflixWasRunning;

    static synchronized void start(Context c) {
        if (on) return;
        on = true;
        final Context app = c.getApplicationContext();
        thread = new Thread(() -> run(app), "key-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    static synchronized void stop() {
        on = false;
        if (session != null) session.close();
        session = null;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private static void run(Context c) {
        long wait = 5_000;
        while (on) {
            long began = SystemClock.elapsedRealtime();
            try {
                Adb adb = Adb.connect(c, 5_000, null);
                synchronized (KeyWatcher.class) {
                    if (!on) {
                        adb.close();
                        return;
                    }
                    session = adb;
                }
                try {
                    AppLog.w("key watcher on");
                    adb.watch("getevent -l", line -> {
                        onLine(c, line);
                        return on;
                    });
                } finally {
                    adb.close();
                }
            } catch (IOException | RuntimeException e) {
                if (on) AppLog.w("key watcher: " + e.getMessage());
            }
            if (SystemClock.elapsedRealtime() - began > 60_000) wait = 5_000;   // it ran a while: try again soon
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
            wait = Math.min(wait * 2, 120_000);
        }
    }

    /** "/dev/input/event3: EV_KEY  KEY_VIDEO  DOWN": scan codes 393 (Netflix) and 399 (Live TV) of the Xiaomi remote. */
    private static void onLine(Context c, String line) {
        if (!line.contains("EV_KEY")) return;
        boolean netflix = line.contains("KEY_VIDEO");
        if (!netflix && !line.contains("KEY_GREEN")) return;
        boolean down = line.trim().endsWith("DOWN"), up = line.trim().endsWith("UP");
        if (!down && !up) return;
        final String pkg = Prefs.keyApp(netflix ? Prefs.KEY_MIBOX_NETFLIX : Prefs.KEY_MIBOX_LIVE);
        if (pkg.isEmpty() || (netflix && pkg.equals(NETFLIX))) return;   // "Xiaomi default": the system's own
        ACTIONS.execute(() -> {
            try {
                if (down) {
                    if (netflix) netflixWasRunning = running(c, NETFLIX);
                } else {
                    Thread.sleep(AFTER_UP_MS);
                    boolean opened = RemoteKeys.open(c, pkg);
                    AppLog.w("remote key " + (netflix ? "Netflix" : "Live TV") + " -> " + pkg + (opened ? "" : " (not installed)"));
                    if (opened && netflix && !netflixWasRunning) {
                        Thread.sleep(STOP_AFTER_MS);
                        try (Adb adb = Adb.connect(c, 5_000, null)) {
                            adb.shell("am force-stop " + NETFLIX);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                AppLog.w("key watcher: " + e.getMessage());
            }
        });
    }

    private static boolean running(Context c, String pkg) throws IOException {
        try (Adb adb = Adb.connect(c, 5_000, null)) {
            return adb.shell("pidof " + pkg).trim().matches("[0-9 ]+");
        }
    }
}
