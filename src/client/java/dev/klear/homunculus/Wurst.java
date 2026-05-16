package dev.klear.homunculus;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reflection-only bridge to Wurst's hack list. Lets homunculus toggle and read
 * the state of Wurst's modules (KillAura, AutoEat, AutoTool, …) without a
 * compile-time dependency on Wurst itself. If Wurst isn't on the classpath at
 * runtime, every method short-circuits cleanly.
 *
 * Wurst API surface used (confirmed via javap on Wurst-Client-v7.51.2):
 *   net.wurstclient.WurstClient.INSTANCE  (enum singleton)
 *     .getHax() -> net.wurstclient.hack.HackList
 *       .getHackByName(String) -> Hack | null
 *       .getAllHax() -> Collection&lt;Hack&gt;
 *   net.wurstclient.hack.Hack extends net.wurstclient.Feature
 *     .getName() -> String
 *     .isEnabled() -> boolean
 *     .setEnabled(boolean)
 *     .getCategory() -> Category  (Feature method)
 */
public final class Wurst {
    private Wurst() {}

    private static volatile Boolean apiLoaded;
    private static final Object INIT = new Object();

    // Reflection handles, populated only on successful isApiLoaded().
    private static Object wurstInstance;
    private static Object hackList;
    private static Method getHackByName;
    private static Method getAllHax;
    private static Method hackGetName;
    private static Method hackIsEnabled;
    private static Method hackSetEnabled;
    private static Method hackGetCategory;

    public static boolean isApiLoaded() {
        Boolean cached = apiLoaded;
        if (cached != null) return cached;
        synchronized (INIT) {
            if (apiLoaded != null) return apiLoaded;
            try {
                Class<?> wurstClass = Class.forName("net.wurstclient.WurstClient");
                wurstInstance = wurstClass.getField("INSTANCE").get(null);
                Method getHax = wurstClass.getMethod("getHax");
                hackList = getHax.invoke(wurstInstance);

                Class<?> hackListClass = Class.forName("net.wurstclient.hack.HackList");
                getHackByName = hackListClass.getMethod("getHackByName", String.class);
                getAllHax = hackListClass.getMethod("getAllHax");

                Class<?> hackClass = Class.forName("net.wurstclient.hack.Hack");
                hackGetName = hackClass.getMethod("getName");
                hackIsEnabled = hackClass.getMethod("isEnabled");
                hackSetEnabled = hackClass.getMethod("setEnabled", boolean.class);
                // getCategory is declared on Feature, but getMethod walks the hierarchy.
                hackGetCategory = hackClass.getMethod("getCategory");

                apiLoaded = Boolean.TRUE;
                HomunculusClient.LOGGER.info("Wurst API bound — /wurst/* endpoints active");
            } catch (Throwable t) {
                HomunculusClient.LOGGER.warn("Wurst API not reachable; /wurst/* will return wurst_not_loaded ({})", t.toString());
                apiLoaded = Boolean.FALSE;
            }
            return apiLoaded;
        }
    }

    /** Case-insensitive lookup. Returns the Hack object, or null. */
    public static Object findHack(String name) {
        if (!isApiLoaded()) return null;
        try {
            Object exact = getHackByName.invoke(hackList, name);
            if (exact != null) return exact;
            Collection<?> all = (Collection<?>) getAllHax.invoke(hackList);
            for (Object hack : all) {
                String hn = (String) hackGetName.invoke(hack);
                if (hn.equalsIgnoreCase(name)) return hack;
            }
            return null;
        } catch (ReflectiveOperationException e) {
            HomunculusClient.LOGGER.error("Wurst findHack({}) failed", name, e);
            return null;
        }
    }

    public static boolean setEnabled(Object hack, boolean enabled) {
        try {
            hackSetEnabled.invoke(hack, enabled);
            return true;
        } catch (ReflectiveOperationException e) {
            HomunculusClient.LOGGER.error("Wurst setEnabled failed", e);
            return false;
        }
    }

    public static boolean isEnabled(Object hack) {
        try {
            return (Boolean) hackIsEnabled.invoke(hack);
        } catch (ReflectiveOperationException e) {
            HomunculusClient.LOGGER.error("Wurst isEnabled failed", e);
            return false;
        }
    }

    public static String getName(Object hack) {
        try {
            return (String) hackGetName.invoke(hack);
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    public static String getCategory(Object hack) {
        try {
            Object cat = hackGetCategory.invoke(hack);
            return cat == null ? null : cat.toString();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    public static List<Object> getAllHacks() {
        if (!isApiLoaded()) return List.of();
        try {
            Collection<?> all = (Collection<?>) getAllHax.invoke(hackList);
            return new ArrayList<>(all);
        } catch (ReflectiveOperationException e) {
            HomunculusClient.LOGGER.error("Wurst getAllHacks failed", e);
            return List.of();
        }
    }
}
