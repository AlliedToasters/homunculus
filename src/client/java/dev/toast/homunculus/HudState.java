package dev.toast.homunculus;

import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-element visibility flags for vanilla Minecraft's in-game HUD, read by
 * {@code GuiHudMixin} on the render thread to cancel individual HUD renders.
 *
 * All elements default to visible (no startup mutation) — this is opt-in
 * suppression for clean recordings, controllable via POST /hud. See
 * [[project-agent-video-recording]].
 */
public final class HudState {
    private HudState() {}

    /** A toggleable vanilla HUD element, with the JSON key used by /hud. */
    public enum Element {
        HEALTH("health", false),
        FOOD("food", false),
        AIR("air", false),
        HOTBAR("hotbar", false),
        EFFECTS("effects", false),
        EXPERIENCE("experience", false),
        CROSSHAIR("crosshair", false),
        SELECTED_ITEM("selected_item", false),
        // The "Demo time's up!" overlay (Gui.renderDemoOverlay), shown whenever the
        // client runs in demo mode — which the agent fleet always does, since 20+
        // concurrent clients can't share the handful of owned accounts. Pure noise
        // for us, so it defaults hidden. No-op on owned-account clients (renders
        // only when Minecraft.isDemo()).
        DEMO("demo", true);

        public final String key;
        public final boolean defaultHidden;

        Element(String key, boolean defaultHidden) {
            this.key = key;
            this.defaultHidden = defaultHidden;
        }
    }

    // Populated once at class-init; only the AtomicBoolean values mutate, so the
    // map itself is safe to read concurrently from the render thread.
    private static final EnumMap<Element, AtomicBoolean> HIDDEN = new EnumMap<>(Element.class);
    static {
        for (Element e : Element.values()) {
            HIDDEN.put(e, new AtomicBoolean(e.defaultHidden));
        }
    }

    public static boolean isHidden(Element e) {
        return HIDDEN.get(e).get();
    }

    public static void setHidden(Element e, boolean hidden) {
        HIDDEN.get(e).set(hidden);
    }

    /** Element for a JSON key, or null if the key isn't recognized. */
    public static Element byKey(String key) {
        for (Element e : Element.values()) {
            if (e.key.equals(key)) return e;
        }
        return null;
    }
}
