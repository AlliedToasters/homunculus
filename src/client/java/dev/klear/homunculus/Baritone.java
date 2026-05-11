package dev.klear.homunculus;

import java.util.concurrent.locks.ReentrantLock;

// Helper for the /baritone/* endpoints — no baritone.api.* imports so the class
// itself loads even when baritone-api is absent at runtime.
public final class Baritone {
    public static final ReentrantLock SESSION_LOCK = new ReentrantLock();

    private static volatile Boolean apiLoaded;
    private static final Object INIT = new Object();

    private Baritone() {}

    public static boolean isApiLoaded() {
        Boolean cached = apiLoaded;
        if (cached != null) return cached;
        synchronized (INIT) {
            if (apiLoaded != null) return apiLoaded;
            try {
                Class.forName("baritone.api.BaritoneAPI");
                apiLoaded = Boolean.TRUE;
            } catch (Throwable t) {
                apiLoaded = Boolean.FALSE;
            }
            return apiLoaded;
        }
    }
}
