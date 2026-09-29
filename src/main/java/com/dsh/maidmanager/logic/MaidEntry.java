package com.dsh.maidmanager.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * An immutable snapshot of one maid, sent from server to client for display.
 *
 * <p>The client never acts on these fields directly; every action is re-validated on the
 * server against the real entity. The snapshot only drives rendering.
 */
public final class MaidEntry {
    public final UUID id;
    public final Component name;
    public final MaidState state;
    public final String dimension;
    public final BlockPos pos;
    public final float health;
    public final float maxHealth;
    /** Whether the per-maid force-load switch is on. */
    public final boolean forceLoad;
    /** Whether the player already accepted the heavy-load explanation. */
    public final boolean acknowledged;
    /** True when the maid is in the same dimension as the player. */
    public final boolean sameDimension;
    /**
     * When the maid was stored (epoch millis), or 0 when she is not in our storage.
     * Used to tell two identically-named maids apart.
     */
    public final long storedAt;
    /** Whether the maid is starred; starred maids are pinned above everything else. */
    public final boolean favourite;
    /**
     * How many times this maid has died, for a {@link MaidState#DEAD} entry.
     *
     * <p>Cumulative and not reset by reviving, because the revive delay scales with it.
     * Zero for every other state.
     */
    public final int deathCount;
    /** Whether a {@link MaidState#DEAD} maid still has her inventory in the snapshot. */
    public final boolean hadItems;

    public MaidEntry(UUID id, Component name, MaidState state, String dimension, BlockPos pos,
                     float health, float maxHealth, boolean forceLoad, boolean acknowledged,
                     boolean sameDimension) {
        this(id, name, state, dimension, pos, health, maxHealth, forceLoad, acknowledged,
                sameDimension, 0L, false);
    }

    public MaidEntry(UUID id, Component name, MaidState state, String dimension, BlockPos pos,
                     float health, float maxHealth, boolean forceLoad, boolean acknowledged,
                     boolean sameDimension, long storedAt) {
        this(id, name, state, dimension, pos, health, maxHealth, forceLoad, acknowledged,
                sameDimension, storedAt, false);
    }

    public MaidEntry(UUID id, Component name, MaidState state, String dimension, BlockPos pos,
                     float health, float maxHealth, boolean forceLoad, boolean acknowledged,
                     boolean sameDimension, long storedAt, boolean favourite) {
        this(id, name, state, dimension, pos, health, maxHealth, forceLoad, acknowledged,
                sameDimension, storedAt, favourite, 0, false);
    }

    public MaidEntry(UUID id, Component name, MaidState state, String dimension, BlockPos pos,
                     float health, float maxHealth, boolean forceLoad, boolean acknowledged,
                     boolean sameDimension, long storedAt, boolean favourite,
                     int deathCount, boolean hadItems) {
        this.id = id;
        this.name = name;
        this.state = state;
        this.dimension = dimension;
        this.pos = pos;
        this.health = health;
        this.maxHealth = maxHealth;
        this.forceLoad = forceLoad;
        this.acknowledged = acknowledged;
        this.sameDimension = sameDimension;
        this.storedAt = storedAt;
        this.favourite = favourite;
        this.deathCount = deathCount;
        this.hadItems = hadItems;
    }

    /** A short, stable suffix that distinguishes two maids sharing a name, e.g. {@code 3f2a}. */
    public String shortId() {
        return id.toString().substring(0, 4);
    }

    /**
     * Whether this maid can be summoned right now. Mirrors the server-side rule so the UI
     * can grey out impossible rows before the player clicks.
     */
    public boolean summonable() {
        return switch (state) {
            case PRESENT, STORED -> true;
            // Unloaded maids need the switch; without it there is nothing to teleport.
            case UNLOADED -> forceLoad;
            // Dead maids come back through revive(), which has its own cost.
            case DEAD -> false;
        };
    }

    public boolean storeable() {
        return state == MaidState.PRESENT;
    }

    /** True when this row can be revived from the panel. */
    public boolean revivable() {
        return state == MaidState.DEAD;
    }

    public float healthFraction() {
        return maxHealth <= 0.0F ? 0.0F : Math.max(0.0F, Math.min(1.0F, health / maxHealth));
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(id);
        // 1.21 moved Components onto the codec system; FriendlyByteBuf no longer has
        // writeComponent/readComponent. ComponentSerialization.STREAM_CODEC is the vanilla
        // replacement, and it needs a RegistryFriendlyByteBuf (it resolves translatable
        // arguments through the registry access), which is also the type the payload codecs
        // receive - so the two line up exactly.
        ComponentSerialization.STREAM_CODEC.encode(buf, name);
        buf.writeEnum(state);
        buf.writeUtf(dimension);
        buf.writeBlockPos(pos);
        buf.writeFloat(health);
        buf.writeFloat(maxHealth);
        buf.writeBoolean(forceLoad);
        buf.writeBoolean(acknowledged);
        buf.writeBoolean(sameDimension);
        buf.writeLong(storedAt);
        buf.writeBoolean(favourite);
        buf.writeVarInt(deathCount);
        buf.writeBoolean(hadItems);
    }

    public static MaidEntry read(RegistryFriendlyByteBuf buf) {
        return new MaidEntry(
                buf.readUUID(),
                ComponentSerialization.STREAM_CODEC.decode(buf),
                buf.readEnum(MaidState.class),
                buf.readUtf(),
                buf.readBlockPos(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readLong(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readBoolean());
    }

    @Nullable
    public static CompoundTag copy(@Nullable CompoundTag tag) {
        return tag == null ? null : tag.copy();
    }
}
