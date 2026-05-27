package dev.toast.homunculus;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime flag read by {@code WurstIngameHudMixin} on the render thread to
 * suppress Wurst's on-screen HUD (logo/version, active-hacks list, TabGui).
 *
 * Hidden by default so headless recordings stay clean; flip at runtime via
 * POST /wurst/hud {"visible": true}. The mixin only exists when Wurst is on the
 * classpath, so this flag is a no-op otherwise.
 */
public final class WurstHud {
    private WurstHud() {}

    private static final AtomicBoolean HIDDEN = new AtomicBoolean(true);

    public static boolean isHidden() {
        return HIDDEN.get();
    }

    public static void setHidden(boolean hidden) {
        HIDDEN.set(hidden);
    }
}
