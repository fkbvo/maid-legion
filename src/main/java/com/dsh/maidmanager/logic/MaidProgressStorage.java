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
    private static final String DATA_ID = "maid_legion_progress";

    private static final String MAID_LEVELS = "MaidLevels";
    private static final String GLOBAL_LEVELS = "GlobalLevels";
    private static final String BANKED_POWER = "BankedPower";
    private static final String AUTO_DEPOSIT_OFF = "AutoDepositOff";

    /** maid UUID -> (upgrade id -> level) */
    private final Map<UUID, Map<String, Integer>> maidLevels = new HashMap<>();
    /** owner UUID -> (ability id -> level) */
    private final Map<UUID, Map<String, Integer>> globalLevels = new HashMap<>();
    /** owner UUID -> banked P-points */
    private final Map<UUID, Float> bankedPower = new HashMap<>();
    /** Owners who switched auto-deposit off; absence means on. */
    private final Set<UUID> autoDepositOff = new HashSet<>();

    public static MaidProgressStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(MaidProgressStorage::load, MaidProgressStorage::new, DATA_ID);
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
