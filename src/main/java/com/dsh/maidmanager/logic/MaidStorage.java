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
 * Persists maids that the player has "stored" (recalled) with the terminal.
 *
 * <p>Stored maids are held as the exact NBT produced by {@code EntityMaid.saveWithoutId},
 * which is the same representation TLM's own smart-slab item uses. That guarantees the
 * maid comes back with her equipment, model, favour, task and inventories intact, and it
 * means releasing a maid never requires loading a chunk.
 *
 * <p>World-scoped ({@link SavedData} on the overworld) so a stored maid survives a restart
 * and is reachable from any dimension, matching how TLM stores its own maid records.
 */
public final class MaidStorage extends SavedData {
    private static final String DATA_ID = "touhou_maid_legion_stored_maids";
    /**
     * The id this data was written under before the mod was renamed. Kept so an existing world can be migrated; without it every maid the player had stored would come back missing.
     */
    private static final String LEGACY_DATA_ID = "maid_legion_stored_maids";
    private static final String ROOT = "Players";
    private static final String ENTRIES = "Entries";
    private static final String MAID_ID = "MaidId";
    private static final String MAID_DATA = "MaidData";
    private static final String MAID_NAME = "MaidName";
    private static final String STORED_AT = "StoredAt";

    /** owner UUID -> (maid UUID -> record) */
    private final Map<UUID, Map<UUID, StoredMaid>> byOwner = new HashMap<>();

    public static MaidStorage get(MinecraftServer server) {
        net.minecraft.world.level.storage.DimensionDataStorage data =
                server.overworld().getDataStorage();
        SavedData.Factory<MaidStorage> factory =
                new SavedData.Factory<>(MaidStorage::new, MaidStorage::load);
        MaidStorage live = data.computeIfAbsent(factory, DATA_ID);
        MaidStorage legacy = data.get(factory, LEGACY_DATA_ID);
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
     */
    public static boolean adoptIfEmpty(MaidStorage live, MaidStorage legacy) {
        if (legacy == null || !live.isEmpty()) {
            return false;
        }
        return live.adoptFrom(legacy);
    }

    public boolean isEmpty() {
        return byOwner.isEmpty();
    }

    /**
     * One-time copy of every stored maid.
     *
     * <p>Each record's NBT is copied rather than shared, so the legacy instance cannot be mutated
     * through the live one.
     */
    public boolean adoptFrom(MaidStorage legacy) {
        if (legacy.isEmpty()) {
            return false;
        }
        legacy.byOwner.forEach((owner, maids) -> {
            Map<UUID, StoredMaid> copy = new LinkedHashMap<>();
            maids.forEach((maidId, record) -> copy.put(maidId, new StoredMaid(
                    record.id(), record.data().copy(), record.name(), record.storedAt())));
            byOwner.put(owner, copy);
        });
        return true;
    }

    /**
     * 1.21 changed the {@link SavedData} contract: {@code load} and {@code save} now take a
     * {@link HolderLookup.Provider} so that data containing registry-backed values (items,
     * enchantments, ...) can be resolved without a live level. We only read and write plain
     * NBT - the maid snapshot is opaque to us - so the provider is unused, but the signatures
     * must match or the anonymous {@code SavedData.Factory} will not compile.
     */
    public static MaidStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        MaidStorage storage = new MaidStorage();
        CompoundTag players = tag.getCompound(ROOT);
        for (String ownerKey : players.getAllKeys()) {
            UUID owner;
            try {
                owner = UUID.fromString(ownerKey);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ListTag list = players.getList(ownerKey, Tag.TAG_COMPOUND);
            Map<UUID, StoredMaid> map = new LinkedHashMap<>();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (!entry.hasUUID(MAID_ID) || !entry.contains(MAID_DATA, Tag.TAG_COMPOUND)) {
                    continue;
                }
                UUID maidId = entry.getUUID(MAID_ID);
                map.put(maidId, new StoredMaid(maidId,
                        entry.getCompound(MAID_DATA),
                        entry.getString(MAID_NAME),
                        entry.getLong(STORED_AT)));
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
                entry.putLong(STORED_AT, record.storedAt());
                list.add(entry);
            });
            players.put(owner.toString(), list);
        });
        tag.put(ROOT, players);
        return tag;
    }

    /** Stores (or overwrites) a maid for its owner. */
    public void put(UUID owner, UUID maidId, CompoundTag maidData, String name) {
        byOwner.computeIfAbsent(owner, k -> new LinkedHashMap<>())
                .put(maidId, new StoredMaid(maidId, maidData.copy(), name, System.currentTimeMillis()));
        setDirty();
    }

    @Nullable
    public StoredMaid get(UUID owner, UUID maidId) {
        Map<UUID, StoredMaid> map = byOwner.get(owner);
        return map == null ? null : map.get(maidId);
    }

    @Nullable
    public StoredMaid remove(UUID owner, UUID maidId) {
        Map<UUID, StoredMaid> map = byOwner.get(owner);
        if (map == null) {
            return null;
        }
        StoredMaid removed = map.remove(maidId);
        if (removed != null) {
            setDirty();
        }
        return removed;
    }

    public List<StoredMaid> list(UUID owner) {
        Map<UUID, StoredMaid> map = byOwner.get(owner);
        return map == null ? List.of() : new ArrayList<>(map.values());
    }

    /**
     * Edits a stored maid's NBT in place. Returns false when there is no such record.
     *
     * <p>Explicit rather than handing out the record's live tag for callers to mutate: writing
     * without marking the data dirty is an easy way to lose the change on the next save. This also
     * keeps "who may write" in one place.
     */
    public boolean editData(UUID owner, UUID maidId,
                            java.util.function.Consumer<CompoundTag> editor) {
        StoredMaid record = get(owner, maidId);
        if (record == null) {
            return false;
        }
        editor.accept(record.data());
        setDirty();
        return true;
    }
    public boolean contains(UUID owner, UUID maidId) {
        return get(owner, maidId) != null;
    }

    /** One stored maid: her full entity NBT plus a little display metadata. */
    public record StoredMaid(UUID id, CompoundTag data, String name, long storedAt) {
    }
}
