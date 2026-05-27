package dev.toast.homunculus;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The one place homunculus resolves entities. Every spatial entity query — "what's near me,
 * matching this filter, sorted how?" — goes through here instead of each handler re-rolling its
 * own player-box + {@code getEntities} + sort/count. Before this, that pattern was copy-pasted
 * across {@link Follow}, {@link ScanEntitiesHandler}, and {@link Sleeper}, and "what counts as
 * hostile" was defined two incompatible ways (MobCategory vs Monster.class). Both are now named,
 * documented, and centralized.
 *
 * <p><b>Threading:</b> {@link #query}, {@link #count}, and {@link #snapshot} read the live entity
 * list, so they MUST be called on the client thread (inside {@link ClientThread#supply}). The
 * predicate factories and id helpers are pure and thread-agnostic.
 *
 * <p>This is deliberately predicate-driven: the <i>filter</i> legitimately differs per use case
 * (prey types, loose items, hostiles) — that's the caller's input. The shared part is the query
 * machinery and the snapshot shape, which is what lives here.
 */
public final class Entities {
    private Entities() {}

    /* ─────────────────────────────── search boxes ─────────────────────────────── */

    /** Player-centered cube of half-extent {@code radius} on every axis. */
    public static AABB boxAround(Entity origin, double radius) {
        return boxAround(origin, radius, radius);
    }

    /** Player-centered box: {@code ±rxz} horizontally, {@code ±ry} vertically. Mirrors MC's own
     *  sleep monster-scan geometry when called with (8, 5). */
    public static AABB boxAround(Entity origin, double rxz, double ry) {
        double x = origin.getX(), y = origin.getY(), z = origin.getZ();
        return new AABB(x - rxz, y - ry, z - rxz, x + rxz, y + ry, z + rxz);
    }

    /* ─────────────────────────────── queries (client thread) ──────────────────── */

    /** Entities within a player-centered cube of half-extent {@code radius} matching {@code pred},
     *  nearest-first, excluding {@code origin}. Client-thread only. */
    public static List<Entity> query(Entity origin, Level level, double radius, Predicate<Entity> pred) {
        return query(origin, level, radius, radius, pred);
    }

    /** As {@link #query(Entity, Level, double, Predicate)} with independent horizontal/vertical
     *  extents. Client-thread only. */
    public static List<Entity> query(Entity origin, Level level, double rxz, double ry,
                                     Predicate<Entity> pred) {
        List<Entity> matched = new ArrayList<>(level.getEntities(origin, boxAround(origin, rxz, ry), pred));
        matched.sort(Comparator.comparingDouble(origin::distanceTo));
        return matched;
    }

    /** Count of entities within a player-centered cube of half-extent {@code radius} matching
     *  {@code pred} (excluding {@code origin}). Client-thread only. */
    public static int count(Entity origin, Level level, double radius, Predicate<Entity> pred) {
        return count(origin, level, radius, radius, pred);
    }

    /** As {@link #count(Entity, Level, double, Predicate)} with independent horizontal/vertical
     *  extents. Client-thread only. */
    public static int count(Entity origin, Level level, double rxz, double ry, Predicate<Entity> pred) {
        return level.getEntities(origin, boxAround(origin, rxz, ry), pred).size();
    }

    /* ─────────────────────────────── predicate library ────────────────────────── */

    /** Matches any entity whose {@link EntityType} is in {@code types}. */
    public static Predicate<Entity> ofTypes(Set<EntityType<?>> types) {
        return e -> types.contains(e.getType());
    }

    /** Matches loose {@link ItemEntity}s whose item id is NOT in {@code excludeItemIds}
     *  (e.g. AutoDrop junk the caller must not chase). */
    public static Predicate<Entity> looseItems(Set<String> excludeItemIds) {
        return e -> e instanceof ItemEntity ie && !excludeItemIds.contains(itemId(ie.getItem()));
    }

    /** Hostile by {@link MobCategory#MONSTER}. The broad "is this thing aggressive?" notion used
     *  by reflexive evasion. Covers zombies/skeletons/creepers/spiders/drowned/husks/witches/endermen. */
    public static boolean isHostileCategory(Entity e) {
        return e.getType().getCategory() == MobCategory.MONSTER;
    }

    /** Hostile by {@code instanceof Monster}. This is the definition vanilla
     *  {@code Player.startSleepInBed} uses for its "monsters nearby, can't sleep" check, so the
     *  sleep precondition keys off this (not {@link #isHostileCategory}) for parity. */
    public static boolean isMonsterClass(Entity e) {
        return e instanceof Monster;
    }

    /* ─────────────────────────────── snapshots + ids ──────────────────────────── */

    /** Immutable, off-thread-safe snapshot of an entity relative to {@code origin}. */
    public record Match(String typeId, String uuid, double x, double y, double z,
                        double distance, boolean isBaby, Float health) {
        /** JSON record matching the long-standing {@code /scan_entities} entity shape and key order. */
        public Map<String, Object> toJson() {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("type", typeId);
            rec.put("uuid", uuid);
            List<Object> pos = new ArrayList<>(3);
            pos.add(x);
            pos.add(y);
            pos.add(z);
            rec.put("position", pos);
            rec.put("distance", distance);
            rec.put("is_baby", isBaby);
            rec.put("health", health);
            return rec;
        }
    }

    /** Snapshot {@code e} relative to {@code origin}. Living entities carry baby/health; others
     *  report {@code is_baby=false, health=null}. Client-thread only (reads entity state). */
    public static Match snapshot(Entity origin, Entity e) {
        boolean isBaby = false;
        Float health = null;
        if (e instanceof LivingEntity le) {
            isBaby = le.isBaby();
            health = le.getHealth();
        }
        return new Match(typeId(e), e.getUUID().toString(), e.getX(), e.getY(), e.getZ(),
                origin.distanceTo(e), isBaby, health);
    }

    /** Canonical registry id of an entity's type, e.g. {@code "minecraft:sheep"}. */
    public static String typeId(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    /** Canonical registry id of an item stack's item, e.g. {@code "minecraft:feather"}. */
    public static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
