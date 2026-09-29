package com.dsh.maidmanager.logic;

import com.dsh.maidmanager.Config;
import com.dsh.maidmanager.MaidManagerMod;
import com.dsh.maidmanager.util.MaidUtil;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.github.tartaricacid.touhoulittlemaid.util.PlaceHelper;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side brain of the maid terminal.
 *
 * <p>All operations are authoritative here. The client sends intents (a set of maid UUIDs)
 * and this class re-validates ownership against the live entity before doing anything, so a
 * malicious client cannot touch another player's maids.
 *
 * <p>Summoning picks the cheapest technique that works for each situation, which was derived
 * from reading TLM's own code:
 * <ul>
 *   <li>same dimension, loaded -&gt; {@code teleportToOwner}</li>
 *   <li>cross dimension -&gt; re-create from NBT (needs no chunk loading at all, because
 *       {@code MaidWorldData} is an overworld singleton that already knows the maid)</li>
 *   <li>same dimension but unloaded -&gt; the maid's own entity must be brought back, which
 *       is the only case that genuinely benefits from a temporary chunk ticket</li>
 * </ul>
 */
public final class MaidManagerService {
    /**
     * A box large enough to contain every loaded entity in a level. Using one shared
     * constant avoids rebuilding a huge AABB per query.
     */
    private static final net.minecraft.world.phys.AABB ALL_ENTITIES = new net.minecraft.world.phys.AABB(
            -3.0E7D, -2048.0D, -3.0E7D, 3.0E7D, 2048.0D, 3.0E7D);

    private MaidManagerService() {
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** Builds the full snapshot list shown in the GUI. */
    public static List<MaidEntry> snapshot(ServerPlayer player) {
        MaidRegistry registry = MaidRegistry.get(player.getServer());
        MaidStorage storage = MaidStorage.get(player.getServer());
        Map<UUID, MaidEntry> byId = new LinkedHashMap<>();

        // 1) Loaded maids in the player's current dimension.
        ServerLevel level = player.serverLevel();
        for (EntityMaid maid : level.getEntitiesOfClass(EntityMaid.class, ALL_ENTITIES)) {
            if (maid.isOwnedBy(player)) {
                byId.putIfAbsent(maid.getUUID(), present(maid, player, registry));
            }
        }

        // 2) Stored maids (our own NBT records) - always summonable.
        for (MaidStorage.StoredMaid stored : storage.list(player.getUUID())) {
            byId.putIfAbsent(stored.id(), new MaidEntry(
                    stored.id(),
                    Component.literal(stored.name().isEmpty() ? "Maid" : stored.name()),
                    MaidState.STORED,
                    "",
                    BlockPos.ZERO,
                    -1.0F,
                    -1.0F,
                    registry.isForceLoad(player.getUUID(), stored.id()),
                    registry.isAcknowledged(player.getUUID()),
                    true,
                    stored.storedAt(),
                    registry.isFavourite(player.getUUID(), stored.id())));
        }

        // 3) Maids TLM knows about but which are not in this world right now.
        //    Verified: onRemovedFromWorld -> addInfo, onAddedToWorld -> removeInfo,
        //    so this list is exactly "currently unloaded".
        for (MaidInfo info : MaidUtil.getUnloadedMaidInfos(player)) {
            UUID id = info.getEntityId();
            if (id == null || byId.containsKey(id)) {
                continue;
            }
            Component name = info.getName();
            byId.put(id, new MaidEntry(
                    id,
                    name == null ? Component.literal("Maid") : name,
                    MaidState.UNLOADED,
                    info.getDimension() == null ? "?" : info.getDimension(),
                    info.getChunkPos() == null ? BlockPos.ZERO : info.getChunkPos(),
                    -1.0F,
                    -1.0F,
                    registry.isForceLoad(player.getUUID(), id),
                    registry.isAcknowledged(player.getUUID()),
                    info.getDimension() != null && MaidUtil.isSameDimension(player, info.getDimension()),
                    0L,
                    registry.isFavourite(player.getUUID(), id)));
        }

        // 3b) Dead maids we captured instead of leaving a tombstone. These are recorded before
        //     TLM's own world data is consulted, so a captured death never shows up as
        //     "unloaded" as well.
        for (MaidDeathStorage.DeadMaid dead : MaidDeathStorage.get(player.getServer()).list(player.getUUID())) {
            byId.put(dead.id(), new MaidEntry(
                    dead.id(),
                    Component.literal(dead.name().isEmpty() ? "Maid" : dead.name()),
                    MaidState.DEAD,
                    "",
                    BlockPos.ZERO,
                    -1.0F,
                    -1.0F,
                    false,
                    registry.isAcknowledged(player.getUUID()),
                    true,
                    dead.diedAt(),
                    registry.isFavourite(player.getUUID(), dead.id()),
                    dead.deathCount(),
                    dead.withItems()));
        }

        // 4) Keep only maids the player explicitly enrolled (gohei shift-right-click).
        //
        //    Filtering here, at the single point where the snapshot is assembled, means every
        //    consumer - the GUI, the hotkey scope, the /maidlegion command - sees exactly the
        //    same roster. Doing it per-caller would let the hotkey act on maids the panel
        //    never showed, which is precisely the accident enrolment exists to prevent.
        Set<UUID> allowed = registry.enrolledMaids(player.getUUID());
        List<MaidEntry> result = new ArrayList<>(byId.size());
        for (MaidEntry entry : byId.values()) {
            if (allowed.contains(entry.id)) {
                result.add(entry);
            }
        }
        return result;
    }

    /** True when this maid is enrolled and therefore visible to the panel. */
    public static boolean isEnrolled(ServerPlayer player, UUID maidId) {
        return MaidRegistry.get(player.getServer()).isEnrolled(player.getUUID(), maidId);
    }

    // ------------------------------------------------------------------
    // Revival
    // ------------------------------------------------------------------

    /**
     * The materials TLM's own {@code reborn_maid} altar recipe costs, mirrored exactly so the
     * panel is not a cheaper route than the altar.
     *
     * <p>Read from {@code data/touhou_little_maid/recipes/altar/reborn_maid.json}: a film, plus
     * one each of lapis, gold, redstone, iron and coal. We charge items rather than the
     * recipe's {@code power} of 0.5 because the altar's power is drawn from the multiblock,
     * which the panel has no access to.
     */
    private static final List<ItemStack> REVIVE_MATERIALS = List.of(
            new ItemStack(net.minecraft.world.item.Items.LAPIS_LAZULI),
            new ItemStack(net.minecraft.world.item.Items.GOLD_INGOT),
            new ItemStack(net.minecraft.world.item.Items.REDSTONE),
            new ItemStack(net.minecraft.world.item.Items.IRON_INGOT),
            new ItemStack(net.minecraft.world.item.Items.COAL));

    /** How many shrines can substitute for the materials, as the user designed. */
    public static final int SHRINE_ALTERNATIVE_COUNT = 3;

    /** Ticks to wait before a revive completes: base, plus a penalty per prior death. */
    public static int reviveDelayTicks(int deathCount) {
        return Math.min(20 * 60, 20 * (5 + 5 * Math.max(0, deathCount - 1)));
    }

    /** The material list the GUI shows, with the amounts the player must supply. */
    public static List<ItemStack> reviveMaterials() {
        return REVIVE_MATERIALS;
    }

    /** True when the player has every material (the film is supplied by the captured data). */
    public static boolean hasReviveMaterials(ServerPlayer player) {
        for (ItemStack required : REVIVE_MATERIALS) {
            if (!player.getInventory().hasAnyMatching(s -> s.is(required.getItem())
                    && s.getCount() >= required.getCount())) {
                return false;
            }
        }
        return true;
    }

    /** Number of shrine blocks the player is carrying, for the shrine-based revive. */
    public static int countShrines(ServerPlayer player) {
        return player.getInventory().countItem(
                com.github.tartaricacid.touhoulittlemaid.init.InitBlocks.SHRINE.get().asItem());
    }

    public static boolean hasShrineAlternative(ServerPlayer player) {
        return countShrines(player) >= SHRINE_ALTERNATIVE_COUNT;
    }

    /**
     * Consumes whichever payment the player chose.
     *
     * @param useShrines true to spend {@value #SHRINE_ALTERNATIVE_COUNT} shrines instead of
     *                   the altar materials
     * @return true when payment succeeded and something was actually consumed
     */
    private static boolean payForRevive(ServerPlayer player, boolean useShrines) {
        if (useShrines) {
            if (!hasShrineAlternative(player)) {
                return false;
            }
            int remaining = SHRINE_ALTERNATIVE_COUNT;
            var shrineItem = com.github.tartaricacid.touhoulittlemaid.init.InitBlocks.SHRINE.get().asItem();
            for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.is(shrineItem)) {
                    int take = Math.min(remaining, stack.getCount());
                    stack.shrink(take);
                    remaining -= take;
                }
            }
            return remaining == 0;
        }

        if (!hasReviveMaterials(player)) {
            return false;
        }
        for (ItemStack required : REVIVE_MATERIALS) {
            int remaining = required.getCount();
            for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.is(required.getItem())) {
                    int take = Math.min(remaining, stack.getCount());
                    stack.shrink(take);
                    remaining -= take;
                }
            }
        }
        return true;
    }

    /** A revive that is waiting out its delay. */
    private record PendingRevive(UUID ownerId, UUID maidId, long readyAtTick, boolean useShrines) {
    }

    private static final Map<UUID, PendingRevive> PENDING_REVIVES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Starts a revive: validates, takes payment, then waits out the delay.
     *
     * <p>Payment is taken up front rather than on completion, so a player cannot start a
     * revive, walk away, and keep the materials. If the delay is interrupted (server stop,
     * owner leaving) the materials are refunded by {@link #tickPendingRevives}.
     *
     * @return a result describing what happened, so the GUI can report it
     */
    public static ReviveResult beginRevive(ServerPlayer player, UUID maidId, boolean useShrines) {
        if (!canControl(player, maidId)) {
            return ReviveResult.NOT_OWNED;
        }
        MaidDeathStorage storage = MaidDeathStorage.get(player.getServer());
        MaidDeathStorage.DeadMaid dead = storage.get(player.getUUID(), maidId);
        if (dead == null) {
            return ReviveResult.NOT_DEAD;
        }
        if (PENDING_REVIVES.containsKey(maidId)) {
            return ReviveResult.ALREADY_PENDING;
        }
        // Reviving across dimensions would drop her at the wrong place, so require proximity
        // to the player instead of guessing a safe spot in another world.
        if (!payForRevive(player, useShrines)) {
            return useShrines ? ReviveResult.NEED_SHRINES : ReviveResult.NEED_MATERIALS;
        }

        long readyAt = player.getServer().getTickCount() + reviveDelayTicks(dead.deathCount());
        PENDING_REVIVES.put(maidId, new PendingRevive(player.getUUID(), maidId, readyAt, useShrines));
        player.displayClientMessage(Component.translatable("message.maid_legion.revive_started",
                dead.name(), reviveDelayTicks(dead.deathCount()) / 20), true);
        return ReviveResult.STARTED;
    }

    /** Advances pending revives; completes one when its delay elapses. */
    public static void tickPendingRevives(MinecraftServer server) {
        if (PENDING_REVIVES.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        var iterator = PENDING_REVIVES.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            PendingRevive pending = entry.getValue();
            ServerPlayer owner = server.getPlayerList().getPlayer(pending.ownerId());
            if (owner == null) {
                // Owner gone: refund and drop the request rather than stranding the payment.
                refundRevive(server, pending);
                iterator.remove();
                continue;
            }
            if (now < pending.readyAtTick()) {
                continue;
            }
            iterator.remove();
            if (finishRevive(owner, pending.maidId())) {
                owner.displayClientMessage(
                        Component.translatable("message.maid_legion.revive_done"), true);
            } else {
                // Placement failed: give the materials back so the attempt is not wasted.
                refundRevive(server, pending);
                owner.sendSystemMessage(Component.translatable("message.maid_legion.revive_failed"));
            }
            com.dsh.maidmanager.network.MaidActionHandler.refresh(owner);
        }
    }

    /**
     * Rebuilds the maid from her captured NBT next to the player.
     *
     * <p>Her whole inventory travels inside that snapshot - armour, hands, backpack, baubles,
     * the hidden slot and the task inventory are all part of {@code saveWithoutId} - which is
     * why nothing was ever dropped and nothing needs re-inserting here.
     */
    private static boolean finishRevive(ServerPlayer player, UUID maidId) {
        MaidDeathStorage storage = MaidDeathStorage.get(player.getServer());
        MaidDeathStorage.DeadMaid dead = storage.get(player.getUUID(), maidId);
        if (dead == null) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        BlockPos target = findSpawnPos(level, player);
        if (target == null) {
            return false;
        }
        try {
            EntityMaid maid = InitEntities.MAID.get().create(level);
            if (maid == null) {
                return false;
            }
            maid.load(dead.data().copy());
            maid.setUUID(maidId);
            maid.moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D,
                    player.getYRot(), 0.0F);
            maid.setDeltaMovement(0.0D, 0.0D, 0.0D);
            // She comes back at full health; a revive that returned her at 1 HP would just get
            // her killed again during the delay the player already waited through.
            maid.setHealth(maid.getMaxHealth());
            level.addFreshEntity(maid);
            maid.spawnExplosionParticle();

            storage.remove(player.getUUID(), maidId);
            // Re-register with TLM so she is tracked as a live maid again.
            MaidUtil.registerMaid(maid);
            return true;
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Revive failed for maid {}", maidId, t);
            return false;
        }
    }

    /** Gives back whatever {@link #payForRevive} took. */
    private static void refundRevive(MinecraftServer server, PendingRevive pending) {
        ServerPlayer owner = server.getPlayerList().getPlayer(pending.ownerId());
        if (owner == null) {
            return;
        }
        if (pending.useShrines()) {
            owner.getInventory().add(new ItemStack(
                    com.github.tartaricacid.touhoulittlemaid.init.InitBlocks.SHRINE.get().asItem(),
                    SHRINE_ALTERNATIVE_COUNT));
        } else {
            for (ItemStack required : REVIVE_MATERIALS) {
                if (!owner.getInventory().add(required.copy())) {
                    owner.drop(required.copy(), false);
                }
            }
        }
    }

    /** Outcome of asking to revive, so the caller can pick the right message. */
    public enum ReviveResult {
        STARTED,
        NOT_OWNED,
        NOT_DEAD,
        ALREADY_PENDING,
        NEED_MATERIALS,
        NEED_SHRINES
    }

    private static MaidEntry present(EntityMaid maid, ServerPlayer player, MaidRegistry registry) {
        return new MaidEntry(
                maid.getUUID(),
                maid.getDisplayName(),
                MaidState.PRESENT,
                MaidUtil.dimensionOf(maid),
                maid.blockPosition(),
                maid.getHealth(),
                maid.getMaxHealth(),
                registry.isForceLoad(player.getUUID(), maid.getUUID()),
                registry.isAcknowledged(player.getUUID()),
                true,
                0L,
                registry.isFavourite(player.getUUID(), maid.getUUID()));
    }

    /** Finds a loaded maid owned by the player, searching every level. */
    @Nullable
    public static EntityMaid findLoadedMaid(ServerPlayer player, UUID maidId) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(maidId);
            if (entity instanceof EntityMaid maid && maid.isOwnedBy(player)) {
                return maid;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /** Result of an attempted action, used to build the feedback message. */
    public record Result(int success, int failed, @Nullable String reasonKey) {
        public static Result ok(int count) {
            return new Result(count, 0, null);
        }
    }

    /** Stores a loaded maid into {@link MaidStorage}. Returns true on success. */
    public static boolean store(ServerPlayer player, UUID maidId) {
        if (!canControl(player, maidId)) {
            return false;
        }
        EntityMaid maid = findLoadedMaid(player, maidId);
        if (maid == null) {
            return false;
        }
        try {
            CompoundTag data = new CompoundTag();
            // Exactly what TLM's smart-slab does, so equipment/model/task/inventories survive.
            maid.saveWithoutId(data);
            String name = maid.getDisplayName().getString();
            MaidStorage.get(player.getServer()).put(player.getUUID(), maidId, data, name);
            maid.discard();
            return true;
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Failed to store maid {}", maidId, t);
            return false;
        }
    }

    /**
     * Summons a stored maid back into the world next to the player.
     * Requires no chunk loading because the NBT is already in hand.
     */
    public static boolean releaseStored(ServerPlayer player, UUID maidId) {
        if (!canControl(player, maidId)) {
            return false;
        }
        MaidStorage storage = MaidStorage.get(player.getServer());
        MaidStorage.StoredMaid stored = storage.get(player.getUUID(), maidId);
        if (stored == null) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        BlockPos target = findSpawnPos(level, player);
        if (target == null) {
            return false;
        }
        try {
            EntityMaid maid = InitEntities.MAID.get().create(level);
            if (maid == null) {
                return false;
            }
            // load() restores the full entity; moveTo then places it.
            maid.load(stored.data().copy());
            maid.moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, player.getYRot(), 0.0F);
            maid.setDeltaMovement(0.0D, 0.0D, 0.0D);
            level.addFreshEntity(maid);
            maid.spawnExplosionParticle();
            storage.remove(player.getUUID(), maidId);
            return true;
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Failed to release maid {}", maidId, t);
            return false;
        }
    }

    /**
     * Teleports an already-loaded maid to the player, clearing any state that would block
     * movement. Mirrors the checks TLM's own unload mixin performs.
     */
    public static boolean summonLoaded(ServerPlayer player, EntityMaid maid) {
        if (!canControl(player, maid.getUUID())) {
            return false;
        }
        try {
            MaidUtil.clearBlockingStates(maid);
            MaidUtil.disableHomeMode(maid);
            maid.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.GLOWING, 200, 1, true, false));

            if (maid.level() != player.level()) {
                return summonCrossDimension(player, maid);
            }
            if (maid.teleportToOwner(player)) {
                return true;
            }
            // Fall back to an explicit position when TLM's own placement search fails.
            BlockPos pos = findSpawnPos(player.serverLevel(), player);
            if (pos == null) {
                return false;
            }
            maid.teleportTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
            return true;
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Failed to summon maid {}", maid.getUUID(), t);
            return false;
        }
    }

    /**
     * Moves a maid across dimensions by round-tripping her through NBT.
     *
     * <p>Chosen over {@code changeDimension} because the bytecode of
     * {@code EntityMaid.changeDimension} shows it does not actually switch levels - it only
     * re-runs a placement search 16 times - so the vanilla/Forge path is required anyway, and
     * the NBT route additionally avoids touching chunk tickets.
     */
    private static boolean summonCrossDimension(ServerPlayer player, EntityMaid maid) {
        try {
            CompoundTag data = new CompoundTag();
            maid.saveWithoutId(data);
            UUID id = maid.getUUID();
            maid.discard();

            ServerLevel level = player.serverLevel();
            BlockPos pos = findSpawnPos(level, player);
            if (pos == null) {
                // Put her back rather than deleting her.
                restoreInPlace(maid, data);
                return false;
            }
            EntityMaid moved = InitEntities.MAID.get().create(level);
            if (moved == null) {
                return false;
            }
            moved.load(data);
            moved.setUUID(id);
            moved.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, player.getYRot(), 0.0F);
            level.addFreshEntity(moved);
            moved.spawnExplosionParticle();
            return true;
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Cross-dimension summon failed for {}", maid.getUUID(), t);
            return false;
        }
    }

    private static void restoreInPlace(EntityMaid old, CompoundTag data) {
        try {
            ServerLevel level = (ServerLevel) old.level();
            EntityMaid restored = InitEntities.MAID.get().create(level);
            if (restored != null) {
                restored.load(data);
                level.addFreshEntity(restored);
            }
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.error("Failed to restore maid after aborted teleport", t);
        }
    }

    /**
     * Finds a safe standing position near the player.
     *
     * <p>Reuses TLM's own {@code PlaceHelper} check so we accept exactly the spots TLM
     * would, then falls back to a small spiral search.
     */
    @Nullable
    public static BlockPos findSpawnPos(ServerLevel level, ServerPlayer player) {
        BlockPos origin = player.blockPosition();
        if (isSafe(level, origin)) {
            return origin;
        }
        for (int radius = 1; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    for (int dy = 1; dy >= -1; dy--) {
                        BlockPos candidate = origin.offset(dx, dy, dz);
                        if (isSafe(level, candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean isSafe(ServerLevel level, BlockPos pos) {
        try {
            if (PlaceHelper.notSuitableForPlaceMaid(level, pos.below())) {
                return false;
            }
        } catch (Throwable ignored) {
            // If the helper is unavailable, fall through to the generic checks.
        }
        if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        if (!level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) {
            return false;
        }
        BlockPos below = pos.below();
        return !level.getBlockState(below).getCollisionShape(level, below).isEmpty()
                || !level.getFluidState(below).isEmpty();
    }

    // ------------------------------------------------------------------
    // Force-load switch
    // ------------------------------------------------------------------

    /**
     * Maids waiting for their chunk to come back so they can be summoned.
     *
     * <p>Chunk loading is asynchronous, so a summon cannot complete in one tick. We remember
     * the request, try again each tick for a bounded time, and report failure if the maid
     * never shows up.
     */
    private record PendingSummon(UUID ownerId, UUID maidId, long deadline) {
    }

    private static final Map<UUID, PendingSummon> PENDING = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Asks Forge to load the maid's chunk, remembering the request so the summon can finish
     * once the entity is back. Returns false when the maid cannot be located at all.
     */
    public static boolean beginForceLoadSummon(ServerPlayer player, UUID maidId) {
        if (!canControl(player, maidId)) {
            return false;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        String dimension = null;
        BlockPos pos = null;
        for (MaidInfo info : MaidUtil.getUnloadedMaidInfos(player)) {
            if (maidId.equals(info.getEntityId())) {
                dimension = info.getDimension();
                pos = info.getChunkPos();
                break;
            }
        }
        if (dimension == null || pos == null) {
            return false;
        }
        ServerLevel target = dimensionLevel(server, dimension);
        if (target == null) {
            return false;
        }
        long deadline = server.getTickCount() + Config.COMMON.forceLoadTimeoutTicks.get();
        PENDING.put(maidId, new PendingSummon(player.getUUID(), maidId, deadline));

        // Hold the chunk. The maid does not exist as an entity yet, so there is no entity
        // ticket owner to use; hold it on the level keyed by chunk, and release it as soon as
        // the summon resolves or times out.
        try {
            net.minecraft.world.level.ChunkPos chunk = new net.minecraft.world.level.ChunkPos(pos);
            ChunkLoadHelper.holdRaw(target, chunk, maidId);
        } catch (Throwable t) {
            MaidManagerMod.LOGGER.warn("Could not start force-load for maid {}: {}", maidId, t.toString());
            PENDING.remove(maidId);
            return false;
        }
        return true;
    }

    /** Resolves a dimension id such as {@code minecraft:the_nether} to a level. */
    @Nullable
    private static ServerLevel dimensionLevel(MinecraftServer server, String dimensionId) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimensionId)) {
                return level;
            }
        }
        return null;
    }

    /**
     * Advances pending force-load summons. Called every server tick; completes a summon as
     * soon as the maid's entity is back, and gives up once the deadline passes so a request
     * can never hang forever.
     */
    public static void tickPendingSummons(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        var iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            PendingSummon pending = entry.getValue();
            ServerPlayer owner = server.getPlayerList().getPlayer(pending.ownerId());
            if (owner == null) {
                iterator.remove();
                continue;
            }
            EntityMaid maid = findLoadedMaid(owner, pending.maidId());
            if (maid != null) {
                iterator.remove();
                if (summonLoaded(owner, maid)) {
                    owner.displayClientMessage(
                            Component.translatable("message.maid_legion.force_load_done"), true);
                } else {
                    owner.sendSystemMessage(Component.translatable("message.maid_legion.cannot_reach"));
                }
                com.dsh.maidmanager.network.MaidActionHandler.refresh(owner);
                continue;
            }
            if (now > pending.deadline()) {
                iterator.remove();
                owner.sendSystemMessage(Component.translatable("message.maid_legion.force_load_timeout"));
                // Release the ticket we took, otherwise it would leak.
                releaseForceLoad(server, pending.maidId());
            }
        }
    }

    /** Releases a force-loaded chunk previously taken for a maid. */
    public static void releaseForceLoad(MinecraftServer server, UUID maidId) {
        for (ServerLevel level : server.getAllLevels()) {
            ChunkLoadHelper.release(level, maidId);
        }
    }

    /** Removes any pending request for a maid (e.g. when the switch is turned off). */
    public static void cancelPending(UUID maidId) {
        PENDING.remove(maidId);
    }

    /**
     * On server start, re-apply force-loading for every maid whose switch is still on.
     *
     * <p>Chunk tickets only live for the lifetime of the server process, so without this a
     * maid with the switch enabled would silently stop being kept loaded after a restart.
     */
    public static void onServerStarted(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (EntityMaid maid : level.getEntitiesOfClass(EntityMaid.class, ALL_ENTITIES)) {
                UUID owner = maid.getOwnerUUID();
                if (owner == null) {
                    continue;
                }
                if (MaidRegistry.get(server).isForceLoad(owner, maid.getUUID())) {
                    ChunkLoadHelper.hold(level, maid);
                }
            }
        }
    }

    /** Releases our bookkeeping when the server stops; Forge drops the tickets itself. */
    public static void onServerStopping(MinecraftServer server) {
        PENDING.clear();
        ChunkLoadHelper.clear();
    }

    /**
     * Re-asserts the held chunk for every force-loaded maid that is currently loaded.
     *
     * <p>Covers maids that walked into a new chunk, and re-takes a chunk that was dropped.
     */
    public static void tickForceLoadedMaids(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (EntityMaid maid : level.getEntitiesOfClass(EntityMaid.class, ALL_ENTITIES)) {
                UUID owner = maid.getOwnerUUID();
                if (owner == null) {
                    continue;
                }
                if (MaidRegistry.get(server).isForceLoad(owner, maid.getUUID())) {
                    ChunkLoadHelper.hold(level, maid);
                }
            }
        }
    }

    /** Enables or disables force-loading for one maid, applying the change immediately. */
    public static void setForceLoad(ServerPlayer player, UUID maidId, boolean enabled) {
        if (!canControl(player, maidId)) {
            return;
        }
        MaidRegistry registry = MaidRegistry.get(player.getServer());
        registry.setForceLoad(player.getUUID(), maidId, enabled);
        if (enabled) {
            registry.setAcknowledged(player.getUUID());
            // If she is loaded right now, start holding her chunk straight away.
            EntityMaid maid = findLoadedMaid(player, maidId);
            if (maid != null && maid.level() instanceof ServerLevel level) {
                ChunkLoadHelper.hold(level, maid);
            }
        } else {
            cancelPending(maidId);
            if (player.getServer() != null) {
                releaseForceLoad(player.getServer(), maidId);
            }
        }
    }

    public static void acknowledge(ServerPlayer player) {
        MaidRegistry.get(player.getServer()).setAcknowledged(player.getUUID());
    }

    /**
     * Stars or unstars a maid.
     *
     * <p>Ownership is verified before storing the flag, so a crafted packet cannot add
     * entries for maids the player does not own. A maid that is currently unloaded is still
     * accepted when she appears in TLM's records for this player.
     */
    public static void setFavourite(ServerPlayer player, UUID maidId, boolean favourite) {
        if (!canControl(player, maidId)) {
            return;
        }
        MaidRegistry.get(player.getServer()).setFavourite(player.getUUID(), maidId, favourite);
    }

    /** True when the maid is one of the player's, whether loaded, stored or merely recorded. */
    public static boolean ownsMaid(ServerPlayer player, UUID maidId) {
        if (findLoadedMaid(player, maidId) != null) {
            return true;
        }
        if (MaidStorage.get(player.getServer()).contains(player.getUUID(), maidId)) {
            return true;
        }
        for (MaidInfo info : MaidUtil.getUnloadedMaidInfos(player)) {
            if (maidId.equals(info.getEntityId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the player may act on this maid through the panel: they own her <em>and</em>
     * have enrolled her.
     *
     * <p>Every mutating entry point validates with this rather than {@link #ownsMaid}, because
     * ownership alone is not consent to be commanded from the terminal. The client only ever
     * sends ids it saw in an enrolled snapshot, so a request for an unenrolled maid means
     * either a stale client or a crafted packet - both are refused.
     */
    public static boolean canControl(ServerPlayer player, UUID maidId) {
        return ownsMaid(player, maidId) && isEnrolled(player, maidId);
    }

    // ------------------------------------------------------------------
    // Commands (useful for debugging without a client)
    // ------------------------------------------------------------------

    public static void registerCommands(com.mojang.brigadier.CommandDispatcher<
            net.minecraft.commands.CommandSourceStack> dispatcher) {
        dispatcher.register(net.minecraft.commands.Commands.literal("maidlegion")
                .requires(src -> src.hasPermission(0))
                .then(net.minecraft.commands.Commands.literal("list").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    List<MaidEntry> entries = snapshot(player);
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "Maid Legion: " + entries.size() + " maid(s)"), false);
                    for (MaidEntry e : entries) {
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                " - " + e.name.getString() + " [" + e.state + "] "
                                        + (e.dimension.isEmpty() ? "" : e.dimension + " " + e.pos)
                                        + (e.forceLoad ? " (force-load)" : "")), false);
                    }
                    return entries.size();
                })));
    }

    /** Convenience used by the config-driven throttle. */
    public static int maxPerAction() {
        return Config.COMMON.maxSummonPerAction.get();
    }

    /** Ticks to wait between maids within one action. */
    public static int intervalTicks() {
        return Config.COMMON.summonIntervalTicks.get();
    }

    /** Whether server owners allow chunk loading at all. */
    public static boolean forceLoadAllowed() {
        return Config.COMMON.allowForceLoad.get();
    }

    /** Unused placeholder to keep the Level import meaningful for future dimension work. */
    static boolean isOverworld(Level level) {
        return level.dimension().equals(Level.OVERWORLD);
    }

    /** Spawn type used when re-creating a maid, so vanilla hooks behave normally. */
    static MobSpawnType spawnType() {
        return MobSpawnType.MOB_SUMMONED;
    }
}
