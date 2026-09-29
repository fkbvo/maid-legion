package com.dsh.maidmanager.client;

import com.dsh.maidmanager.MaidManagerMod;
import com.dsh.maidmanager.logic.MaidEntry;
import com.dsh.maidmanager.logic.ToggleRouter;
import com.dsh.maidmanager.network.C2SMaidActionPacket;
import com.dsh.maidmanager.network.NetworkHandler;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Key bindings and client-side request helpers.
 *
 * <p>The user asked for purely manual control, so there is no combat detection anywhere.
 * There are only two keys: one opens the terminal, and one summon/recall toggle which is
 * unbound by default so each player can pick their own.
 */
public final class ClientInput {
    public static final KeyMapping OPEN_TERMINAL = new KeyMapping(
            "key.maid_legion.open",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_PERIOD,
            "key.categories.maid_legion");

    /**
     * Single hotkey for both summoning and recalling.
     *
     * <p>Unbound by default (see {@link InputConstants#UNKNOWN}) because which direction it
     * should go is a matter of taste. The direction is decided at press time by
     * {@link #toggleSummonRecall()}.
     */
    public static final KeyMapping TOGGLE_ACTION = new KeyMapping(
            "key.maid_legion.toggle",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            "key.categories.maid_legion");

    private ClientInput() {
    }

    /**
     * NeoForge removed the {@code bus = ...} attribute: {@code @EventBusSubscriber} now
     * inspects the event type and attaches the handler to the right bus automatically, so
     * {@code RegisterKeyMappingsEvent} (a mod-bus event) still lands correctly.
     */
    @EventBusSubscriber(modid = MaidManagerMod.MOD_ID, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_TERMINAL);
            event.register(TOGGLE_ACTION);
        }
    }

    @EventBusSubscriber(modid = MaidManagerMod.MOD_ID, value = Dist.CLIENT)
    public static final class GameBus {
        /**
         * 1.21 removed {@code InputEvent.Key} (which fired from the raw GLFW callback before
         * key state was flushed). {@link ClientTickEvent.Post} is the supported replacement:
         * both keys are edge-triggered through {@code consumeClick()}, so polling them once
         * per client tick is equivalent, and it additionally means a key pressed while a
         * screen is open is not silently swallowed.
         */
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen != null) {
                // Drain the queued clicks anyway, otherwise a press made while a screen was
                // open would fire the moment the player closes it.
                OPEN_TERMINAL.consumeClick();
                TOGGLE_ACTION.consumeClick();
                return;
            }
            if (OPEN_TERMINAL.consumeClick()) {
                new MaidManagerScreen().openAndRefresh();
            } else if (TOGGLE_ACTION.consumeClick()) {
                toggleSummonRecall();
            }
        }
    }

    /** Cached snapshot so the hotkeys work without the GUI being open. */
    private static volatile List<MaidEntry> lastSnapshot = List.of();

    public static void updateSnapshot(List<MaidEntry> entries) {
        lastSnapshot = List.copyOf(entries);
    }

    public static List<MaidEntry> snapshot() {
        return lastSnapshot;
    }

    /**
     * Handles the single summon/recall hotkey.
     *
     * <p>Only maids that are <em>ticked in the terminal</em> are ever acted on. An empty
     * selection does nothing except tell the player to go and tick some rows, because acting
     * on every maid would make the key dangerous (one stray press would drag the whole
     * household across the map).
     *
     * <p>Direction is decided at press time:
     * <ol>
     *   <li>if any ticked maid can be <em>brought back</em> - one we stored, or one TLM has
     *       unloaded whose force-load switch is on - summon those;</li>
     *   <li>otherwise <em>recall</em> the ticked maids that are standing in the world.</li>
     * </ol>
     * So the key summons when there is something to bring back and recalls when there is not,
     * which is what "one key for both" has to mean in practice. A fallen maid is ignored
     * either way: reviving her is her badge's job, and this key must never spend materials.
     */
    public static void toggleSummonRecall() {
        List<MaidEntry> scope = scopeOf();
        if (scope.isEmpty()) {
            message("message.maid_legion.no_selection");
            return;
        }

        // The routing itself lives in ToggleRouter: it is pure, so it can be unit tested,
        // which is how the "unloaded maid was sent a STORE request" bug should have been
        // caught in the first place.
        ToggleRouter.Plan plan = ToggleRouter.plan(scope);
        switch (plan.direction()) {
            case SUMMON -> NetworkHandler.sendToServer(
                    new C2SMaidActionPacket(C2SMaidActionPacket.Action.SUMMON, plan.targets()));
            case STORE -> NetworkHandler.sendToServer(
                    new C2SMaidActionPacket(C2SMaidActionPacket.Action.STORE, plan.targets()));
            case NOTHING -> message("message.maid_legion.nothing_to_do");
        }
    }

    /** Shows a message above the hotbar, if there is a player to show it to. */
    private static void message(String key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable(key), true);
        }
    }

    /**
     * The ticked maids, or an empty list when nothing is ticked.
     *
     * <p>Deliberately does <em>not</em> fall back to the whole snapshot: the hotkey must only
     * ever touch what the player explicitly chose.
     */
    private static List<MaidEntry> scopeOf() {
        if (ClientSelection.get().isEmpty()) {
            return List.of();
        }
        List<MaidEntry> scoped = new ArrayList<>();
        for (MaidEntry entry : lastSnapshot) {
            if (ClientSelection.isSelected(entry.id)) {
                scoped.add(entry);
            }
        }
        return scoped;
    }

    public static void requestRefresh() {
        NetworkHandler.sendToServer(
                new C2SMaidActionPacket(C2SMaidActionPacket.Action.REFRESH, List.of()));
    }

    public static void sendAction(C2SMaidActionPacket.Action action, List<UUID> targets) {
        if (!targets.isEmpty()) {
            NetworkHandler.sendToServer(new C2SMaidActionPacket(action, targets));
        }
    }
}
