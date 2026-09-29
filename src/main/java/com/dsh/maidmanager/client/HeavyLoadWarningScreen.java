package com.dsh.maidmanager.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * One-time explanation shown the first time a player turns on a maid's force-load switch.
 *
 * <p>Deliberately not a hard cap: the user asked for a warning instead of a limit, so this
 * screen informs without restricting. Acceptance is recorded server-side, so it appears once
 * per player.
 */
public class HeavyLoadWarningScreen extends Screen {
    private final Screen parent;
    private final Runnable onConfirm;
    private final boolean infoOnly;

    /** Shown before the player turns the switch on for the first time. */
    public HeavyLoadWarningScreen(Screen parent, Runnable onConfirm) {
        this(parent, onConfirm, false);
    }

    private HeavyLoadWarningScreen(Screen parent, Runnable onConfirm, boolean infoOnly) {
        super(Component.translatable("gui.maid_legion.warning.title"));
        this.parent = parent;
        this.onConfirm = onConfirm;
        this.infoOnly = infoOnly;
    }

    /** Opened from the help button: explains the feature without changing anything. */
    public static HeavyLoadWarningScreen asHelp(Screen parent) {
        return new HeavyLoadWarningScreen(parent, () -> { }, true);
    }

    @Override
    protected void init() {
        int y = this.height / 2 + 30;
        if (!infoOnly) {
            addRenderableWidget(Button.builder(Component.translatable("gui.maid_legion.warning.confirm"), b -> {
                onConfirm.run();
                net.minecraft.client.Minecraft.getInstance().setScreen(parent);
            }).bounds(this.width / 2 - 104, y, 100, 20).build());
        }
        addRenderableWidget(Button.builder(
                        Component.translatable(infoOnly
                                ? "gui.maid_legion.warning.close"
                                : "gui.maid_legion.warning.cancel"), b ->
                        net.minecraft.client.Minecraft.getInstance().setScreen(parent))
                .bounds(infoOnly ? this.width / 2 - 50 : this.width / 2 + 4, y, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 1.21 removed the one-argument Screen.renderBackground(GuiGraphics); the dim/blur pass
        // now happens inside super.render(), which is called at the end of this method.
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 60, 0xFFFFCC00);

        Component[] lines = new Component[]{
                Component.translatable("gui.maid_legion.warning.line1"),
                Component.translatable("gui.maid_legion.warning.line2"),
                Component.empty(),
                Component.translatable("gui.maid_legion.warning.line3"),
                Component.translatable("gui.maid_legion.warning.line4"),
        };
        int y = this.height / 2 - 36;
        for (Component line : lines) {
            graphics.drawCenteredString(this.font, line, this.width / 2, y, 0xFFE0E0E0);
            y += 14;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
