package com.dsh.maidmanager.logic;

import com.dsh.maidmanager.MaidManagerMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.world.chunk.TicketController;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the chunks of "force loaded" maids loaded.
 *
 * <p><strong>1.21 rewrite.</strong> On 1.20 we called Forge's
 * {@code ForgeChunkManager.forceChunk(level, modId, owner, x, z, add, forceLoad)}. NeoForge
 * deleted that class, but its replacement - {@link TicketController} - has an identical
 * method signature, so the call site barely changed. What <em>is</em> new is that a
 * {@code TicketController} must be created and registered in
 * {@link net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent} before it
 * can be used; {@link #registerController} does that.
 *
 * <p>Why not vanilla {@code ServerLevel.setChunkForced}? Two reasons:
 * <ul>
 *   <li>it writes to {@code forcedchunks.dat} as a permanent world-level flag with no owner,
 *       so it survives restarts even after the mod is removed and leaks chunks forever;</li>
 *   <li>it has no per-owner bookkeeping, so two different features fighting over the same
 *       chunk cannot release it independently.</li>
 * </ul>
 * A ticket, by contrast, is tracked per owning entity UUID and per controller, and Neoforge
 * drops the entity-keyed ones automatically when the maid is removed from the world - so a
 * maid that dies or is deleted cannot leak her chunk.
 */
public final class ChunkLoadHelper {
    /** maid UUID -> the chunk we are currently holding for her. */
    private static final Map<UUID, ChunkPos> HELD = new HashMap<>();

    /**
     * Our ticket controller. Created eagerly (the constructor is trivial) but only usable
     * after {@link #registerController} has run during mod construction.
     */
    private static final TicketController CONTROLLER = new TicketController(
            ResourceLocation.fromNamespaceAndPath(MaidManagerMod.MOD_ID, "maid_chunk_loader"));

    private static volatile boolean controllerRegistered;

    private ChunkLoadHelper() {
    }

    /**
     * Registers the controller. Must be called from the mod event bus' ticket-controller
     * registration event; calling {@code forceChunk} before this throws.
     */
    public static void registerController(
            net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
        controllerRegistered = true;
    }

    public static boolean isControllerRegistered() {
        return controllerRegistered;
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
            // forceLoad=true means "keep the chunk fully ticking", not merely entity-loaded.
            boolean ok = CONTROLLER.forceChunk(level, entity, current.x, current.z, true, true);
            if (ok) {
                HELD.put(id, current);
            } else {
                MaidManagerMod.LOGGER.warn(
                        "Could not force-load chunk {} for maid {}", current, id);
            }
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.warn("force-loading chunk {} for maid {} failed: {}",
                    current, id, t.toString());
        }
    }

    /**
     * Holds an arbitrary chunk on behalf of a maid who does not exist as an entity yet
     * (the "summon an unloaded maid" path). Keyed on her UUID, which survives the reload,
     * so the ticket converts seamlessly into the entity-owned one once she is back.
     */
    public static void holdRaw(ServerLevel level, ChunkPos pos, UUID maidId) {
        HELD.put(maidId, pos);
        CONTROLLER.forceChunk(level, maidId, pos.x, pos.z, true, true);
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
            CONTROLLER.forceChunk(level, maidId, pos.x, pos.z, false, true);
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.warn("releasing chunk {} for maid {} failed: {}",
                    pos, maidId, t.toString());
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
     * NeoForge releases the actual tickets during shutdown.
     */
    public static void clear() {
        HELD.clear();
    }
}
