package com.dsh.maidmanager.logic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persists everything the upgrade system owns: per-maid upgrade levels, legion-wide ability
 * levels, the player's banked P-points, and who turned auto-deposit off.
 *
 * <p>Kept out of {@link MaidRegistry} because that class is a set of flags; this is progression
 * state with its own shape and its own migration concerns. It is also deliberately free of
 * {@code ItemStack} and {@code EntityMaid}, which is what lets it be unit tested outside a
 * running game - the same reason {@link MaidDeathStorage} avoids item types.
 *
 * <p><b>Levels are the source of truth, attributes are derived.</b> Nothing here writes an
 * attribute modifier; {@link MaidUpgradeEffects} re-derives them whenever a maid loads. That is
 * what keeps a value from being applied twice.
 */
public final class MaidProgressStorage extends SavedData {
    private static final String DATA_ID = "touhou_maid_legion_progress";
    /**
     * The id this data was written under before the mod was renamed. Kept so an existing world
     * can be migrated; without it every bought upgrade level and the whole P-point bank would be
     * lost.
     */
    private static final String LEGACY_DATA_ID = "maid_legion_progress";

    private static final String MAID_LEVELS = "MaidLevels";
    private static final String GLOBAL_LEVELS = "GlobalLevels";
    private static final String BANKED_POWER = "BankedPower";
    private static final String AUTO_DEPOSIT_OFF = "AutoDepositOff";
    private static final String DISABLED_ABILITIES = "DisabledAbilities";
    private static final String DEATH_HEAT = "DeathHeat";
    private static final String ACTIVE_CASTS = "ActiveCasts";
    private static final String BOUND_LAMPS = "BoundLamps";

    // Nested keys for the records below.
    private static final String HEAT = "Heat";
    private static final String HEAT_AT = "At";
    private static final String CAST_OWNER = "Owner";
    private static final String CAST_ENDS_AT = "EndsAt";
    private static final String CAST_TOTAL = "Total";
    private static final String LAMP_DIM = "Dim";
    private static final String LAMP_POS = "Pos";

    /** maid UUID -> (upgrade id -> level) */
    private final Map<UUID, Map<String, Integer>> maidLevels = new HashMap<>();
    /** owner UUID -> (ability id -> level) */
    private final Map<UUID, Map<String, Integer>> globalLevels = new HashMap<>();
    /** owner UUID -> banked P-points */
    private final Map<UUID, Float> bankedPower = new HashMap<>();
    /** Owners who switched auto-deposit off; absence means on. */
    private final Set<UUID> autoDepositOff = new HashSet<>();
    /**
     * Bought abilities the player has switched off, per owner.
     *
     * <p>Owning an ability and having it active are separate things: this holds the distinction so
     * a player can park an ability without losing the purchase.
     */
    private final Map<UUID, Set<String>> disabledAbilities = new HashMap<>();
    /** maid UUID -> how recently she died. Drives the shrine revival cast time. */
    private final Map<UUID, DeathHeat> deathHeat = new HashMap<>();
    /**
     * Shrine revivals in progress, keyed by maid.
     *
     * <p>Persisted rather than kept in memory: the three shrines are consumed up front, so a
     * restart that forgot the cast would silently eat them.
     */
    private final Map<UUID, ActiveCast> activeCasts = new HashMap<>();
    /** owner UUID -> the shrine lamp they bound with a gohei. */
    private final Map<UUID, BoundLamp> boundLamps = new HashMap<>();

    /** Recent deaths, cooled on read. */
    public record DeathHeat(int heat, long at) {
    }

    /** A shrine revival in progress. {@code endsAt} is an overworld game time. */
    public record ActiveCast(UUID owner, long endsAt, int totalTicks) {
    }

    /** A shrine lamp bound with a gohei: dimension id plus packed block position. */
    public record BoundLamp(String dimension, long pos) {
    }

    /**
     * Fetches the store, adopting data written before the mod was renamed.
     *
     * <p>Adoption only happens when the live store is empty, so it is idempotent and cannot
     * overwrite data written by the renamed build. The legacy file is left on disk so a rollback
     * to an older build still works.
     */
    public static MaidProgressStorage get(MinecraftServer server) {
        net.minecraft.world.level.storage.DimensionDataStorage data =
                server.overworld().getDataStorage();
        MaidProgressStorage live = data.computeIfAbsent(
                MaidProgressStorage::load, MaidProgressStorage::new, DATA_ID);
        MaidProgressStorage legacy = data.get(MaidProgressStorage::load, LEGACY_DATA_ID);
        if (adoptIfEmpty(live, legacy)) {
            live.setDirty();
        }
        return live;
    }

    /**
     * Copies pre-rename data in, but only into an empty store.
     *
     * <p>Separated from {@link #get(MinecraftServer)} so the decision can be unit tested without
     * a server.
     *
     * <p>"Empty" means <em>nothing at all</em> was written, down to a lone auto-deposit opt-out.
     * Counting the opt-out as content deliberately errs toward refusing to adopt: the only way
     * the live store can hold anything is if the renamed build already wrote it, and then that
     * data must win. At first load the store was created empty in this same call, so the strict
     * rule costs nothing in practice.
     */
    public static boolean adoptIfEmpty(MaidProgressStorage live, MaidProgressStorage legacy) {
        if (legacy == null || !live.isEmpty()) {
            return false;
        }
        return live.adoptFrom(legacy);
    }

    public boolean isEmpty() {
        return maidLevels.isEmpty() && globalLevels.isEmpty() && bankedPower.isEmpty()
                && autoDepositOff.isEmpty() && disabledAbilities.isEmpty()
                && deathHeat.isEmpty() && activeCasts.isEmpty() && boundLamps.isEmpty();
    }

    /** One-time copy of every level, balance and opt-out, into fresh collections. */
    public boolean adoptFrom(MaidProgressStorage legacy) {
        if (legacy.isEmpty()) {
            return false;
        }
        legacy.maidLevels.forEach((id, levels) -> maidLevels.put(id, new HashMap<>(levels)));
        legacy.globalLevels.forEach((id, levels) -> globalLevels.put(id, new HashMap<>(levels)));
        legacy.bankedPower.forEach(bankedPower::put);
        autoDepositOff.addAll(legacy.autoDepositOff);
        legacy.disabledAbilities.forEach((owner, ids) -> disabledAbilities.put(owner, new HashSet<>(ids)));
        legacy.deathHeat.forEach(deathHeat::put);
        legacy.activeCasts.forEach(activeCasts::put);
        legacy.boundLamps.forEach(boundLamps::put);
        return true;
    }

    public static MaidProgressStorage load(CompoundTag tag) {
        MaidProgressStorage storage = new MaidProgressStorage();
        CompoundTag maidTag = tag.getCompound(MAID_LEVELS);
        for (String key : maidTag.getAllKeys()) {
            UUID maidId = parse(key);
            if (maidId != null) {
                storage.maidLevels.put(maidId, readLevels(maidTag.getCompound(key)));
            }
        }
        CompoundTag globalTag = tag.getCompound(GLOBAL_LEVELS);
        for (String key : globalTag.getAllKeys()) {
            UUID owner = parse(key);
            if (owner != null) {
                storage.globalLevels.put(owner, readLevels(globalTag.getCompound(key)));
            }
        }
        CompoundTag bankTag = tag.getCompound(BANKED_POWER);
        for (String key : bankTag.getAllKeys()) {
            UUID owner = parse(key);
            if (owner != null) {
                storage.bankedPower.put(owner, bankTag.getFloat(key));
            }
        }
        ListTag off = tag.getList(AUTO_DEPOSIT_OFF, Tag.TAG_INT_ARRAY);
        for (int i = 0; i < off.size(); i++) {
            int[] raw = off.getIntArray(i);
            if (raw.length == 4) {
                storage.autoDepositOff.add(toUuid(raw));
            }
        }

        CompoundTag disabled = tag.getCompound(DISABLED_ABILITIES);
        for (String key : disabled.getAllKeys()) {
            UUID owner = parse(key);
            if (owner == null) {
                continue;
            }
            Set<String> ids = new HashSet<>();
            ListTag list = disabled.getList(key, Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                String id = list.getString(i);
                // Drop ids this build no longer knows, so a save from a newer build still loads.
                if (GlobalUpgrade.byId(id) != null) {
                    ids.add(id);
                }
            }
            if (!ids.isEmpty()) {
                storage.disabledAbilities.put(owner, ids);
            }
        }

        CompoundTag heat = tag.getCompound(DEATH_HEAT);
        for (String key : heat.getAllKeys()) {
            UUID maidId = parse(key);
            if (maidId == null) {
                continue;
            }
            CompoundTag entry = heat.getCompound(key);
            int value = entry.getInt(HEAT);
            if (value > 0) {
                storage.deathHeat.put(maidId, new DeathHeat(value, entry.getLong(HEAT_AT)));
            }
        }

        CompoundTag casts = tag.getCompound(ACTIVE_CASTS);
        for (String key : casts.getAllKeys()) {
            UUID maidId = parse(key);
            if (maidId == null) {
                continue;
            }
            CompoundTag entry = casts.getCompound(key);
            UUID owner = parse(entry.getString(CAST_OWNER));
            if (owner == null) {
                // Without an owner the cast could never be completed or refunded, so it is
                // dropped rather than left dangling forever.
                continue;
            }
            int total = entry.getInt(CAST_TOTAL);
            if (total > 0) {
                storage.activeCasts.put(maidId,
                        new ActiveCast(owner, entry.getLong(CAST_ENDS_AT), total));
            }
        }

        CompoundTag lamps = tag.getCompound(BOUND_LAMPS);
        for (String key : lamps.getAllKeys()) {
            UUID owner = parse(key);
            if (owner == null) {
                continue;
            }
            CompoundTag entry = lamps.getCompound(key);
            String dim = entry.getString(LAMP_DIM);
            if (!dim.isEmpty()) {
                storage.boundLamps.put(owner, new BoundLamp(dim, entry.getLong(LAMP_POS)));
            }
        }
        return storage;
    }

    /**
     * Reads one id-to-level map, dropping entries for upgrades this build no longer knows.
     *
     * <p>Forward compatibility matters more than completeness here: a save written by a newer
     * build, or by a build with an upgrade that was later removed, must still load rather than
     * throw and take the whole world's progress state with it.
     */
    private static Map<String, Integer> readLevels(CompoundTag tag) {
        Map<String, Integer> levels = new HashMap<>();
        for (String id : tag.getAllKeys()) {
            if (MaidUpgrade.byId(id) == null && GlobalUpgrade.byId(id) == null) {
                continue;
            }
            int level = tag.getInt(id);
            if (level > 0) {
                levels.put(id, level);
            }
        }
        return levels;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag maids = new CompoundTag();
        maidLevels.forEach((id, levels) -> maids.put(id.toString(), writeLevels(levels)));
        tag.put(MAID_LEVELS, maids);

        CompoundTag globals = new CompoundTag();
        globalLevels.forEach((id, levels) -> globals.put(id.toString(), writeLevels(levels)));
        tag.put(GLOBAL_LEVELS, globals);

        CompoundTag bank = new CompoundTag();
        bankedPower.forEach((id, amount) -> bank.putFloat(id.toString(), amount));
        tag.put(BANKED_POWER, bank);

        ListTag off = new ListTag();
        autoDepositOff.forEach(owner -> off.add(new net.minecraft.nbt.IntArrayTag(fromUuid(owner))));
        tag.put(AUTO_DEPOSIT_OFF, off);

        CompoundTag disabled = new CompoundTag();
        disabledAbilities.forEach((owner, ids) -> {
            ListTag list = new ListTag();
            // Sorted so repeated saves of the same state produce the same file.
            ids.stream().sorted().forEach(id -> list.add(net.minecraft.nbt.StringTag.valueOf(id)));
            if (!list.isEmpty()) {
                disabled.put(owner.toString(), list);
            }
        });
        tag.put(DISABLED_ABILITIES, disabled);

        CompoundTag heat = new CompoundTag();
        deathHeat.forEach((maidId, record) -> {
            CompoundTag entry = new CompoundTag();
            entry.putInt(HEAT, record.heat());
            entry.putLong(HEAT_AT, record.at());
            heat.put(maidId.toString(), entry);
        });
        tag.put(DEATH_HEAT, heat);

        CompoundTag casts = new CompoundTag();
        activeCasts.forEach((maidId, cast) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString(CAST_OWNER, cast.owner().toString());
            entry.putLong(CAST_ENDS_AT, cast.endsAt());
            entry.putInt(CAST_TOTAL, cast.totalTicks());
            casts.put(maidId.toString(), entry);
        });
        tag.put(ACTIVE_CASTS, casts);

        CompoundTag lamps = new CompoundTag();
        boundLamps.forEach((owner, lamp) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString(LAMP_DIM, lamp.dimension());
            entry.putLong(LAMP_POS, lamp.pos());
            lamps.put(owner.toString(), entry);
        });
        tag.put(BOUND_LAMPS, lamps);
        return tag;
    }

    private static CompoundTag writeLevels(Map<String, Integer> levels) {
        CompoundTag tag = new CompoundTag();
        levels.forEach((id, level) -> {
            if (level > 0) {
                tag.putInt(id, level);
            }
        });
        return tag;
    }

    // ------------------------------------------------------------------
    // Per-maid upgrade levels
    // ------------------------------------------------------------------

    public int level(UUID maidId, MaidUpgrade upgrade) {
        Map<String, Integer> levels = maidLevels.get(maidId);
        return levels == null ? 0 : levels.getOrDefault(upgrade.id(), 0);
    }

    /** Every level for one maid, for the client snapshot. Missing ids mean level 0. */
    public Map<String, Integer> levelsOf(UUID maidId) {
        Map<String, Integer> levels = maidLevels.get(maidId);
        return levels == null ? Map.of() : Map.copyOf(levels);
    }

    public void setLevel(UUID maidId, MaidUpgrade upgrade, int level) {
        int clamped = MaidUpgrade.clampLevel(level);
        if (clamped == 0) {
            Map<String, Integer> levels = maidLevels.get(maidId);
            if (levels != null && levels.remove(upgrade.id()) != null) {
                setDirty();
            }
            return;
        }
        maidLevels.computeIfAbsent(maidId, k -> new HashMap<>()).put(upgrade.id(), clamped);
        setDirty();
    }

    // ------------------------------------------------------------------
    // Legion-wide ability levels
    // ------------------------------------------------------------------

    public int globalLevel(UUID owner, GlobalUpgrade upgrade) {
        Map<String, Integer> levels = globalLevels.get(owner);
        return levels == null ? 0 : levels.getOrDefault(upgrade.id(), 0);
    }

    public Map<String, Integer> globalLevelsOf(UUID owner) {
        Map<String, Integer> levels = globalLevels.get(owner);
        return levels == null ? Map.of() : Map.copyOf(levels);
    }

    public boolean hasGlobal(UUID owner, GlobalUpgrade upgrade) {
        return globalLevel(owner, upgrade) > 0;
    }

    public void setGlobalLevel(UUID owner, GlobalUpgrade upgrade, int level) {
        int clamped = Math.max(0, Math.min(upgrade.maxLevel(), level));
        if (clamped == 0) {
            Map<String, Integer> levels = globalLevels.get(owner);
            if (levels != null && levels.remove(upgrade.id()) != null) {
                setDirty();
            }
            return;
        }
        globalLevels.computeIfAbsent(owner, k -> new HashMap<>()).put(upgrade.id(), clamped);
        setDirty();
    }

    // ------------------------------------------------------------------
    // Banked P-points
    // ------------------------------------------------------------------

    public float banked(UUID owner) {
        return bankedPower.getOrDefault(owner, 0.0F);
    }

    /**
     * Moves as much of {@code amount} into the bank as {@code cap} allows.
     *
     * <p>Pure arithmetic on purpose - the caller reads and writes TLM's wallet around it, so this
     * stays testable without a player.
     *
     * @return how much was actually banked, which may be less than {@code amount}
     */
    public float deposit(UUID owner, float amount, float cap) {
        if (amount <= 0.0F || cap <= 0.0F) {
            return 0.0F;
        }
        float current = banked(owner);
        float room = Math.max(0.0F, cap - current);
        float moved = Math.min(amount, room);
        if (moved > 0.0F) {
            bankedPower.put(owner, current + moved);
            setDirty();
        }
        return moved;
    }

    /**
     * Removes up to {@code max} from the bank.
     *
     * @return how much was actually taken, which may be less than {@code max}
     */
    public float withdraw(UUID owner, float max) {
        if (max <= 0.0F) {
            return 0.0F;
        }
        float current = banked(owner);
        float taken = Math.min(current, max);
        if (taken > 0.0F) {
            float left = current - taken;
            if (left <= 0.0F) {
                bankedPower.remove(owner);
            } else {
                bankedPower.put(owner, left);
            }
            setDirty();
        }
        return taken;
    }

    /** Charges the bank directly, for an ability purchase. Returns false when short. */
    public boolean charge(UUID owner, int cost) {
        if (banked(owner) + 1.0E-4F < cost) {
            return false;
        }
        withdraw(owner, cost);
        return true;
    }

    // ------------------------------------------------------------------
    // Auto-deposit
    // ------------------------------------------------------------------

    /** Auto-deposit is on unless the player turned it off, so the default is the useful one. */
    public boolean autoDepositEnabled(UUID owner) {
        return !autoDepositOff.contains(owner);
    }

    public void setAutoDepositEnabled(UUID owner, boolean enabled) {
        if (enabled ? autoDepositOff.remove(owner) : autoDepositOff.add(owner)) {
            setDirty();
        }
    }

    // ------------------------------------------------------------------
    // Ability on/off switches
    // ------------------------------------------------------------------

    /**
     * Owning an ability and having it active are separate: this reports the switch.
     *
     * <p>Defaults to on, so a purchase does something the moment it is made.
     */
    public boolean isAbilityEnabled(UUID owner, GlobalUpgrade ability) {
        Set<String> ids = disabledAbilities.get(owner);
        return ids == null || !ids.contains(ability.id());
    }

    public void setAbilityEnabled(UUID owner, GlobalUpgrade ability, boolean enabled) {
        Set<String> ids = disabledAbilities.get(owner);
        if (enabled) {
            if (ids != null && ids.remove(ability.id())) {
                if (ids.isEmpty()) {
                    disabledAbilities.remove(owner);
                }
                setDirty();
            }
            return;
        }
        disabledAbilities.computeIfAbsent(owner, k -> new HashSet<>()).add(ability.id());
        setDirty();
    }

    // ------------------------------------------------------------------
    // Recent-death heat
    // ------------------------------------------------------------------

    /** How recently this maid died, or null when she has no recorded heat. */
    public DeathHeat deathHeat(UUID maidId) {
        return deathHeat.get(maidId);
    }

    /**
     * Records one more death at {@code now}, cooling whatever heat was already there.
     *
     * <p>Must be called exactly once per death: TLM runs its tombstone path twice, so the caller
     * guards on its own "already recorded" check.
     */
    public void recordDeathHeat(UUID maidId, long now) {
        DeathHeat previous = deathHeat.get(maidId);
        int stored = previous == null ? 0 : previous.heat();
        long at = previous == null ? now : previous.at();
        deathHeat.put(maidId, new DeathHeat(ReviveCast.heatAfterDeath(stored, at, now), now));
        setDirty();
    }

    /** Clears the heat entirely, for an admin reset. */
    public void clearDeathHeat(UUID maidId) {
        if (deathHeat.remove(maidId) != null) {
            setDirty();
        }
    }

    // ------------------------------------------------------------------
    // Shrine revivals in progress
    // ------------------------------------------------------------------

    public ActiveCast activeCast(UUID maidId) {
        return activeCasts.get(maidId);
    }

    /** Every cast in progress, for the once-a-tick completion sweep. */
    public Map<UUID, ActiveCast> activeCasts() {
        return Map.copyOf(activeCasts);
    }

    public void setActiveCast(UUID maidId, ActiveCast cast) {
        activeCasts.put(maidId, cast);
        setDirty();
    }

    public void clearActiveCast(UUID maidId) {
        if (activeCasts.remove(maidId) != null) {
            setDirty();
        }
    }

    // ------------------------------------------------------------------
    // Bound shrine lamps
    // ------------------------------------------------------------------

    public BoundLamp boundLamp(UUID owner) {
        return boundLamps.get(owner);
    }

    public void setBoundLamp(UUID owner, BoundLamp lamp) {
        boundLamps.put(owner, lamp);
        setDirty();
    }

    public void clearBoundLamp(UUID owner) {
        if (boundLamps.remove(owner) != null) {
            setDirty();
        }
    }

    private static UUID parse(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID toUuid(int[] raw) {
        return new UUID((long) raw[0] << 32 | (raw[1] & 0xFFFFFFFFL),
                (long) raw[2] << 32 | (raw[3] & 0xFFFFFFFFL));
    }

    private static int[] fromUuid(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        return new int[]{(int) (msb >> 32), (int) msb, (int) (lsb >> 32), (int) lsb};
    }
}
