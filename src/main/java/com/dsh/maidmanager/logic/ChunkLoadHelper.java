package com.dsh.maidmanager.logic;

import com.dsh.maidmanager.MaidManagerMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.world.ForgeChunkManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the chunks of "force loaded" maids loaded.
 *
 * <p>Uses Forge's {@link ForgeChunkManager#forceChunk} rather than the vanilla
 * {@code setChunkForced}. The difference matters:
 *
 * <ul>
 *   <li>{@code setChunkForced} writes to {@code forcedchunks.dat} and is a permanent,
 *       world-level flag. There is no per-owner bookkeeping and it survives restarts even
 *       if the mod is later removed, which makes it easy to leak chunks forever.</li>
 *   <li>{@code ForgeChunkManager} tracks tickets <em>per owning entity UUID and per mod id</em>,
 *       and {@code forceChunk(..., forceLoad=true)} only holds the chunk while the owner
 *       entity exists. Tickets are dropped automatically when the entity is removed or the
 *       server stops, so a maid that dies or is deleted cannot leak her chunk.</li>
 * </ul>
 *
 * <p>We still keep our own record of what we forced so that toggling the switch off, or the
 * maid changing chunk, releases exactly the chunks we added.
 */
public final class ChunkLoadHelper {
    /** maid UUID -> the chunk we are currently holding for her. */
    private static final Map<UUID, ChunkPos> HELD = new HashMap<>();

    private ChunkLoadHelper() {
    }

    /**
     * Holds the chunk containing {@code entity} for as long as the maid exists.
     * Safe to call repeatedly; only the first call for a given chunk does work.
     */
    public static void hold(ServerLevel level, Entity entity) {
        UUID id = entity.getUUID();
        ChunkPos current = entity.chunkPosition();
        ChunkPos previous = HELD.get(id);
        if (current.equals(previous)) {
            return;
        }
        if (previous != null) {
            release(level, id, previous);
        }
        try {
            boolean ok = ForgeChunkManager.forceChunk(
                    level, MaidManagerMod.MOD_ID, entity, current.x, current.z, true, true);
            if (ok) {
                HELD.put(id, current);
            } else {
                MaidManagerMod.LOGGER.warn(
                        "Could not force-load chunk {} for maid {}", current, id);
            }
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.warn("forceChunk failed for maid {}: {}", id, t.toString());
        }
    }

    /** Releases the chunk held for this maid, if any. */
    public static void release(ServerLevel level, UUID maidId) {
        ChunkPos held = HELD.remove(maidId);
        if (held != null) {
            release(level, maidId, held);
        }
    }

    private static void release(ServerLevel level, UUID maidId, ChunkPos pos) {
        try {
            ForgeChunkManager.forceChunk(
                    level, MaidManagerMod.MOD_ID, maidId, pos.x, pos.z, false, true);
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.warn("forceChunk release failed for maid {}: {}", maidId, t.toString());
        }
    }

    /** True when we currently hold a chunk for this maid. */
    public static boolean isHeld(UUID maidId) {
        return HELD.containsKey(maidId);
    }

    /** For diagnostics / commands. */
    public static Set<UUID> heldMaids() {
        return new HashSet<>(HELD.keySet());
    }

    /**
     * Drops all bookkeeping. Called on server stop so a restart starts from a clean slate;
     * Forge releases the actual tickets during shutdown.
     */
    public static void clear() {
        HELD.clear();
    }
}
