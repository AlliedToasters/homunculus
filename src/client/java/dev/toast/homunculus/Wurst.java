package dev.toast.homunculus;

import com.google.gson.JsonElement;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.Item;

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

    // Setting reflection (best-effort, bound after core API loads)
    private static volatile boolean settingApiReady;
    private static Method featureGetSettings;
    private static Method settingFromJsonM;
    private static Method settingToJsonM;
    private static Method settingGetName;
    private static Class<?> itemListSettingClass;
    private static Method ilsGetItemNames;
    private static Method ilsAdd;
    private static Method ilsRemove;
    private static Method ilsReset;

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
                try {
                    featureGetSettings = Class.forName("net.wurstclient.Feature").getMethod("getSettings");
                    Class<?> settingClass = Class.forName("net.wurstclient.settings.Setting");
                    settingFromJsonM = settingClass.getMethod("fromJson", JsonElement.class);
                    settingToJsonM = settingClass.getMethod("toJson");
                    settingGetName = settingClass.getMethod("getName");
                    itemListSettingClass = Class.forName("net.wurstclient.settings.ItemListSetting");
                    ilsGetItemNames = itemListSettingClass.getMethod("getItemNames");
                    ilsAdd = itemListSettingClass.getMethod("add", Item.class);
                    ilsRemove = itemListSettingClass.getMethod("remove", int.class);
                    ilsReset = itemListSettingClass.getMethod("resetToDefaults");
                    settingApiReady = true;
                    HomunculusClient.LOGGER.info("Wurst Setting reflection bound — /wurst/setting active");
                } catch (Throwable ils) {
                    HomunculusClient.LOGGER.warn("Wurst Setting reflection unavailable ({}); /wurst/setting will return unsupported_setting_type", ils.toString());
                }
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

    public static boolean isSettingApiReady() {
        return isApiLoaded() && settingApiReady;
    }

    /** Case-insensitive lookup of a setting on a hack object. Returns the Setting or null. */
    @SuppressWarnings("unchecked")
    public static Object findSetting(Object hack, String name) {
        if (!settingApiReady) return null;
        try {
            Map<String, Object> settings = (Map<String, Object>) featureGetSettings.invoke(hack);
            Object exact = settings.get(name);
            if (exact != null) return exact;
            for (Map.Entry<String, Object> e : settings.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
            }
            return null;
        } catch (ReflectiveOperationException e) {
            HomunculusClient.LOGGER.error("Wurst findSetting({}) failed", name, e);
            return null;
        }
    }

    /** All settings of a hack as a name→Setting map (Wurst's Feature.getSettings,
     *  an ordered map). Empty if the API isn't ready / the hack is null. Used by
     *  PlayerObsSnapshot to expose the active policy (g_t) in the codec obs. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getSettingsMap(Object hack) {
        if (!settingApiReady || hack == null) return java.util.Map.of();
        try {
            return (Map<String, Object>) featureGetSettings.invoke(hack);
        } catch (ReflectiveOperationException e) {
            HomunculusClient.LOGGER.error("Wurst getSettingsMap failed", e);
            return java.util.Map.of();
        }
    }

    public static boolean isItemListSetting(Object setting) {
        return itemListSettingClass != null && itemListSettingClass.isInstance(setting);
    }

    @SuppressWarnings("unchecked")
    public static List<String> getItemNames(Object setting) {
        try {
            return (List<String>) ilsGetItemNames.invoke(setting);
        } catch (ReflectiveOperationException e) {
            HomunculusClient.LOGGER.error("Wurst getItemNames failed", e);
            return List.of();
        }
    }

    public static void itemListAdd(Object setting, Item item) {
        try {
            ilsAdd.invoke(setting, item);
        } catch (ReflectiveOperationException e) {
            HomunculusClient.LOGGER.error("Wurst itemListAdd failed", e);
        }
    }

    public static void itemListRemoveAt(Object setting, int index) {
        try {
            ilsRemove.invoke(setting, index);
        } catch (ReflectiveOperationException e) {
            HomunculusClient.LOGGER.error("Wurst itemListRemoveAt({}) failed", index, e);
        }
    }

    public static void itemListReset(Object setting) {
        try {
            ilsReset.invoke(setting);
        } catch (ReflectiveOperationException e) {
            HomunculusClient.LOGGER.error("Wurst itemListReset failed", e);
        }
    }

    /** True if Wurst would serialize this as the "default" sentinel in settings.json. */
    public static boolean isItemListAtDefaults(Object setting) {
        JsonElement json = settingToJson(setting);
        // Wurst returns JsonPrimitive("default") at defaults, JsonArray otherwise.
        // JsonPrimitive.toString() emits the value as a JSON string: "\"default\""
        return json != null && "\"default\"".equals(json.toString());
    }

    /** Returns the setting's name as registered on its hack, or null on failure. */
    public static String settingName(Object setting) {
        try {
            return (String) settingGetName.invoke(setting);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** Returns the setting subclass simple name (e.g. "CheckboxSetting"). */
    public static String settingTypeName(Object setting) {
        Class<?> c = setting.getClass();
        // Walk up to a class whose name ends in "Setting" — concrete subclasses like FilterPassiveSetting
        // are useful but we want the closest Wurst type, e.g. CheckboxSetting / SliderSetting.
        while (c != null && c != Object.class) {
            String n = c.getSimpleName();
            if (n.endsWith("Setting") && c.getPackageName().startsWith("net.wurstclient.settings")) return n;
            c = c.getSuperclass();
        }
        return setting.getClass().getSimpleName();
    }

    /** Serialize a setting via its toJson(). Returns null on failure. */
    public static JsonElement settingToJson(Object setting) {
        if (!settingApiReady) return null;
        try {
            return (JsonElement) settingToJsonM.invoke(setting);
        } catch (ReflectiveOperationException e) {
            HomunculusClient.LOGGER.error("Wurst settingToJson failed", e);
            return null;
        }
    }

    /**
     * Apply a JsonElement to a setting via Wurst's Setting.fromJson(JsonElement). Returns null on success;
     * on failure, returns the underlying Throwable from the setting (typically JsonParseException for
     * wrong-shaped input, or IllegalArgumentException for out-of-range values).
     */
    public static Throwable settingFromJson(Object setting, JsonElement element) {
        if (!settingApiReady) return new IllegalStateException("setting API not ready");
        try {
            settingFromJsonM.invoke(setting, element);
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause() != null ? e.getCause() : e;
        } catch (ReflectiveOperationException e) {
            return e;
        }
    }
}
