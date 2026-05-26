package dev.toast.homunculus;

import java.util.Set;

/**
 * Runtime policy for which foods the offhand-food curator (see {@link Equipper})
 * is allowed to stage into the offhand for Wurst AutoEat to consume.
 *
 * AutoEat is configured (by the craft harness) to "Take items from = Hands" +
 * "Allow offhand", so the offhand is the ONLY place auto-eating draws from. By
 * controlling what the curator places there, homunculus controls what gets eaten:
 *
 *   - ANY (default): every food is approved — preserves daily-driver behavior
 *     (agents auto-eat whatever they have, including raw meat, as a survival net).
 *   - COOKED_ONLY: raw meat is NOT approved, so it never reaches the offhand and
 *     is never auto-eaten. Forces the agent to cook before the meat feeds them —
 *     used by the cook-capability tests so the substrate stops eating raw beef
 *     out from under the cook chain.
 *
 * Set at runtime via POST /food_policy (see {@link FoodPolicyHandler}); the craft
 * harness sets it per-rollout from the CRAFT_FOOD_POLICY env knob.
 */
public final class FoodPolicy {
	public enum Mode { ANY, COOKED_ONLY }

	private static volatile Mode mode = Mode.ANY;

	private FoodPolicy() {}

	public static Mode get() { return mode; }

	public static void set(Mode m) { mode = m; }

	/** Parse "any"/"cooked_only" (case-insensitive); null on unknown. */
	public static Mode parse(String s) {
		if (s == null) return null;
		return switch (s.trim().toLowerCase()) {
			case "any" -> Mode.ANY;
			case "cooked_only", "cooked-only", "cookedonly" -> Mode.COOKED_ONLY;
			default -> null;
		};
	}

	public static String label(Mode m) {
		return m == Mode.COOKED_ONLY ? "cooked_only" : "any";
	}

	// Raw meats (item registry paths) that should be cooked before they feed the
	// agent. Under COOKED_ONLY these are excluded from the auto-food slot + offhand
	// so AutoEat (Hands mode) never consumes them. Cooked variants (cooked_beef, …)
	// are not in this set and so remain approved.
	private static final Set<String> RAW_MEATS = Set.of(
			"beef", "porkchop", "mutton", "chicken", "rabbit", "cod", "salmon");

	public static boolean isRawMeat(String itemPath) {
		return RAW_MEATS.contains(itemPath);
	}
}
