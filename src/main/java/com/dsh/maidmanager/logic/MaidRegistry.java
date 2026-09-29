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
 * Persists the per-maid "force load" switches and the one-time heavy-load acknowledgement.
 *
 * <p>Kept separate from {@link MaidStorage} because the two have different lifecycles: a
 * maid's switch must survive being summoned and stored again, so it cannot live inside the
 * stored NBT.
 */
public final class MaidRegistry extends SavedData {
    private static final String DATA_ID = "maid_legion_registry";
    private static final String FORCE_LOAD = "ForceLoad";
    private static final String FAVOURITES = "Favourites";
    private static final String ACKNOWLEDGED = "Acknowledged";

    private final Map<UUID, Set<UUID>> forceLoad = new HashMap<>();
    private final Map<UUID, Set<UUID>> favourites = new HashMap<>();
    private final Set<UUID> acknowledged = new HashSet<>();

    public static MaidRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(MaidRegistry::load, MaidRegistry::new, DATA_ID);
    }

    public static MaidRegistry load(CompoundTag tag) {
        MaidRegistry registry = new MaidRegistry();
        readPerOwner(tag, FORCE_LOAD, registry.forceLoad);
        readPerOwner(tag, FAVOURITES, registry.favourites);
        ListTag ack = tag.getList(ACKNOWLEDGED, Tag.TAG_INT_ARRAY);
        for (int i = 0; i < ack.size(); i++) {
            int[] raw = ack.getIntArray(i);
            if (raw.length == 4) {
                registry.acknowledged.add(toUuid(raw));
            }
        }
        return registry;
    }

    /** Reads a {@code owner -> set of maid UUIDs} compound, skipping malformed entries. */
    private static void readPerOwner(CompoundTag tag, String key, Map<UUID, Set<UUID>> into) {
        CompoundTag root = tag.getCompound(key);
        for (String ownerKey : root.getAllKeys()) {
            UUID owner = parse(ownerKey);
            if (owner == null) {
                continue;
            }
            Set<UUID> maids = new HashSet<>();
            ListTag list = root.getList(ownerKey, Tag.TAG_INT_ARRAY);
            for (int i = 0; i < list.size(); i++) {
                int[] raw = list.getIntArray(i);
                if (raw.length == 4) {
                    maids.add(toUuid(raw));
                }
            }
            into.put(owner, maids);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.put(FORCE_LOAD, writePerOwner(forceLoad));
        tag.put(FAVOURITES, writePerOwner(favourites));
        ListTag ack = new ListTag();
        acknowledged.forEach(owner -> ack.add(new net.minecraft.nbt.IntArrayTag(fromUuid(owner))));
        tag.put(ACKNOWLEDGED, ack);
        return tag;
    }

    private static CompoundTag writePerOwner(Map<UUID, Set<UUID>> source) {
        CompoundTag root = new CompoundTag();
        source.forEach((owner, maids) -> {
            ListTag list = new ListTag();
            maids.forEach(maid -> list.add(new net.minecraft.nbt.IntArrayTag(fromUuid(maid))));
            root.put(owner.toString(), list);
        });
        return root;
    }

    public boolean isForceLoad(UUID owner, UUID maidId) {
        Set<UUID> set = forceLoad.get(owner);
        return set != null && set.contains(maidId);
    }

    public void setForceLoad(UUID owner, UUID maidId, boolean enabled) {
        Set<UUID> set = forceLoad.computeIfAbsent(owner, k -> new HashSet<>());
        if (enabled ? set.add(maidId) : set.remove(maidId)) {
            setDirty();
        }
    }

    // ------------------------------------------------------------------
    // Favourites (starred maids, pinned to the top of the terminal)
    // ------------------------------------------------------------------

    public boolean isFavourite(UUID owner, UUID maidId) {
        Set<UUID> set = favourites.get(owner);
        return set != null && set.contains(maidId);
    }

    public void setFavourite(UUID owner, UUID maidId, boolean favourite) {
        Set<UUID> set = favourites.computeIfAbsent(owner, k -> new HashSet<>());
        if (favourite ? set.add(maidId) : set.remove(maidId)) {
            setDirty();
        }
    }

    /** Number of starred maids, so the GUI can enable or disable the filter button. */
    public int favouriteCount(UUID owner) {
        Set<UUID> set = favourites.get(owner);
        return set == null ? 0 : set.size();
    }

    public boolean isAcknowledged(UUID owner) {
        return acknowledged.contains(owner);
    }

    public void setAcknowledged(UUID owner) {
        if (acknowledged.add(owner)) {
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
        return new int[]{
                (int) (msb >> 32), (int) msb,
                (int) (lsb >> 32), (int) lsb
        };
    }
}
