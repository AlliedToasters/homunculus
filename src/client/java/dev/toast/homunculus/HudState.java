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
        HEALTH("health"),
        FOOD("food"),
        AIR("air"),
        HOTBAR("hotbar"),
        EFFECTS("effects"),
        EXPERIENCE("experience"),
        CROSSHAIR("crosshair"),
        SELECTED_ITEM("selected_item");

        public final String key;

        Element(String key) {
            this.key = key;
        }
    }

    // Populated once at class-init; only the AtomicBoolean values mutate, so the
    // map itself is safe to read concurrently from the render thread.
    private static final EnumMap<Element, AtomicBoolean> HIDDEN = new EnumMap<>(Element.class);
    static {
        for (Element e : Element.values()) {
            HIDDEN.put(e, new AtomicBoolean(false));
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
