package com.dsh.maidmanager.logic;

import com.dsh.maidmanager.MaidManagerMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persists maids that died while enrolled in the legion.
 *
 * <p>This exists so a death can be undone from the panel instead of from a tombstone. When an
 * enrolled maid dies we capture her full NBT here and cancel TLM's tombstone, which normally
 * scatters her equipment on the ground when opened. Everything - armour, both hands, the maid
 * backpack, baubles, the hidden slot, the task inventory and her experience - is already part
 * of {@code saveWithoutId}, so a single snapshot round-trips the whole maid.
 *
 * <p>Deliberately parallel to {@link MaidStorage} rather than reusing it: a stored maid can be
 * released at will, whereas a dead maid must first be paid for. Keeping them apart means a
 * player can never accidentally free a dead maid by pressing the summon hotkey, and the
 * revive cost stays enforceable.
 */
public final class MaidDeathStorage extends SavedData {
    private static final String DATA_ID = "maid_legion_dead_maids";
    private static final String ROOT = "Players";
    private static final String MAID_ID = "MaidId";
    private static final String MAID_DATA = "MaidData";
    private static final String MAID_NAME = "MaidName";
    private static final String DIED_AT = "DiedAt";
    private static final String DEATH_COUNT = "DeathCount";
    private static final String WITH_ITEMS = "WithItems";

    /** owner UUID -> (maid UUID -> record) */
    private final Map<UUID, Map<UUID, DeadMaid>> byOwner = new HashMap<>();

    public static MaidDeathStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MaidDeathStorage::new, MaidDeathStorage::load), DATA_ID);
    }

    public static MaidDeathStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        MaidDeathStorage storage = new MaidDeathStorage();
        CompoundTag players = tag.getCompound(ROOT);
        for (String ownerKey : players.getAllKeys()) {
            UUID owner;
            try {
                owner = UUID.fromString(ownerKey);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ListTag list = players.getList(ownerKey, Tag.TAG_COMPOUND);
            Map<UUID, DeadMaid> map = new LinkedHashMap<>();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (!entry.hasUUID(MAID_ID) || !entry.contains(MAID_DATA, Tag.TAG_COMPOUND)) {
                    continue;
                }
                UUID maidId = entry.getUUID(MAID_ID);
                map.put(maidId, new DeadMaid(
                        maidId,
                        entry.getCompound(MAID_DATA),
                        entry.getString(MAID_NAME),
                        entry.getLong(DIED_AT),
                        entry.getInt(DEATH_COUNT),
                        entry.getBoolean(WITH_ITEMS)));
            }
            storage.byOwner.put(owner, map);
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag players = new CompoundTag();
        byOwner.forEach((owner, maids) -> {
            ListTag list = new ListTag();
            maids.values().forEach(record -> {
                CompoundTag entry = new CompoundTag();
                entry.putUUID(MAID_ID, record.id());
                entry.put(MAID_DATA, record.data().copy());
                entry.putString(MAID_NAME, record.name());
                entry.putLong(DIED_AT, record.diedAt());
                entry.putInt(DEATH_COUNT, record.deathCount());
                entry.putBoolean(WITH_ITEMS, record.withItems());
                list.add(entry);
            });
            players.put(owner.toString(), list);
        });
        tag.put(ROOT, players);
        return tag;
    }

    /**
     * Records a death, or updates the existing record for a maid who died before.
     *
     * <p>The death counter is cumulative for the maid, not reset when she is revived, so the
     * "repeated deaths cost more time" rule has something to count. It is only cleared by an
     * explicit call to {@link #clearDeathCount}.
     *
     * @param withItems whether the snapshot still carried her inventory. A maid revived and
     *                  killed again keeps her death streak, but her items are whatever she
     *                  actually had at the time.
     */
    public void recordDeath(UUID owner, UUID maidId, CompoundTag maidData, String name,
                            boolean withItems) {
        Map<UUID, DeadMaid> map = byOwner.computeIfAbsent(owner, k -> new LinkedHashMap<>());
        DeadMaid previous = map.get(maidId);
        int deathCount = previous == null ? 1 : previous.deathCount() + 1;
        map.put(maidId, new DeadMaid(maidId, maidData.copy(), name,
                System.currentTimeMillis(), deathCount, withItems));
        setDirty();
    }

    @Nullable
    public DeadMaid get(UUID owner, UUID maidId) {
        Map<UUID, DeadMaid> map = byOwner.get(owner);
        return map == null ? null : map.get(maidId);
    }

    @Nullable
    public DeadMaid remove(UUID owner, UUID maidId) {
        Map<UUID, DeadMaid> map = byOwner.get(owner);
        if (map == null) {
            return null;
        }
        DeadMaid removed = map.remove(maidId);
        if (removed != null) {
            setDirty();
        }
        return removed;
    }

    public List<DeadMaid> list(UUID owner) {
        Map<UUID, DeadMaid> map = byOwner.get(owner);
        return map == null ? List.of() : new ArrayList<>(map.values());
    }

    public boolean contains(UUID owner, UUID maidId) {
        return get(owner, maidId) != null;
    }

    /** How many times this maid has died, so the revive delay can scale. */
    public int deathCount(UUID owner, UUID maidId) {
        DeadMaid record = get(owner, maidId);
        return record == null ? 0 : record.deathCount();
    }

    /**
     * One dead maid: her full entity NBT, plus enough metadata to price the revive.
     *
     * @param deathCount cumulative deaths for this maid, driving the revive delay
     * @param withItems  whether {@code data} still contains her inventory
     */
    public record DeadMaid(UUID id, CompoundTag data, String name, long diedAt,
                           int deathCount, boolean withItems) {
    }
}
