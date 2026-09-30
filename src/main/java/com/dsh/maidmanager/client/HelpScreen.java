package com.dsh.maidmanager.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The full usage guide, in pages.
 *
 * <p>Replaces the previous behaviour of reusing the force-load warning as a help screen, which
 * only explained one of the four things a player has to know: how to enrol a maid, what the four
 * states mean, what the two hotkeys do, and how upgrades are paid for. A player who forgot the
 * hotkey binding had nowhere to look.
 *
 * <p>One page per topic rather than one long scroll, because the topics are unrelated and a wall
 * of text is what the old screen was criticised for.
 */
public class HelpScreen extends Screen {

    /** Each page is a title key followed by its body lines. */
    private static final String[][] PAGES = {
            {
                    "gui.touhou_maid_legion.help.p1.title",
                    "gui.touhou_maid_legion.help.p1.1",
                    "gui.touhou_maid_legion.help.p1.2",
                    "gui.touhou_maid_legion.help.p1.3",
            },
            {
                    "gui.touhou_maid_legion.help.p2.title",
                    "gui.touhou_maid_legion.help.p2.1",
                    "gui.touhou_maid_legion.help.p2.2",
                    "gui.touhou_maid_legion.help.p2.3",
                    "gui.touhou_maid_legion.help.p2.4",
            },
            {
                    "gui.touhou_maid_legion.help.p3.title",
                    "gui.touhou_maid_legion.help.p3.1",
                    "gui.touhou_maid_legion.help.p3.2",
                    "gui.touhou_maid_legion.help.p3.3",
                    "gui.touhou_maid_legion.help.p3.4",
                    "gui.touhou_maid_legion.help.p3.5",
            },
            {
                    "gui.touhou_maid_legion.help.p4.title",
                    "gui.touhou_maid_legion.help.p4.1",
                    "gui.touhou_maid_legion.help.p4.2",
                    "gui.touhou_maid_legion.help.p4.3",
            },
            {
                    "gui.touhou_maid_legion.help.p5.title",
                    "gui.touhou_maid_legion.help.p5.1",
                    "gui.touhou_maid_legion.help.p5.2",
                    "gui.touhou_maid_legion.help.p5.3",
                    "gui.touhou_maid_legion.help.p5.4",
            },
            {
                    "gui.touhou_maid_legion.help.p6.title",
                    "gui.touhou_maid_legion.help.p6.1",
                    "gui.touhou_maid_legion.help.p6.2",
                    "gui.touhou_maid_legion.help.p6.3",
            },
    };

    private static final int LINE_H = 12;

    private final Screen parent;
    private int page;
    private Button prevButton;
    private Button nextButton;

    public HelpScreen(Screen parent) {
        super(Component.translatable("gui.touhou_maid_legion.help.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int y = this.height - 28;
        int w = 70;
        this.prevButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.touhou_maid_legion.help.prev"), b -> turn(-1))
                .bounds(this.width / 2 - w - 34, y, w, 20).build());
        this.nextButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.touhou_maid_legion.help.next"), b -> turn(1))
                .bounds(this.width / 2 + 34, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.touhou_maid_legion.close"),
                        b -> Minecraft.getInstance().setScreen(parent))
                .bounds(this.width / 2 - 34, y, 68, 20).build());
        refreshButtons();
    }

    private void turn(int delta) {
        this.page = Math.floorMod(this.page + delta, PAGES.length);
        refreshButtons();
    }

    private void refreshButtons() {
        if (prevButton != null) {
            prevButton.active = page > 0;
        }
        if (nextButton != null) {
            nextButton.active = page < PAGES.length - 1;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // super.render() runs the 1.21 blur + dim pass and then draws the buttons.
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        String pageLabel = Component.translatable("gui.touhou_maid_legion.help.page",
                String.valueOf(page + 1), String.valueOf(PAGES.length)).getString();
        graphics.drawCenteredString(this.font, pageLabel, this.width / 2, 26, 0xA0A0A0);

        String[] lines = PAGES[page];
        int y = 54;
        graphics.drawCenteredString(this.font, Component.translatable(lines[0]).getString(),
                this.width / 2, y, 0xFFCC00);
        y += LINE_H * 2;
        for (int i = 1; i < lines.length; i++) {
            // Wrapped, because several lines are longer than a narrow window.
            for (var wrapped : wrap(Component.translatable(lines[i]))) {
                graphics.drawString(this.font, wrapped, 20, y, 0xE0E0E0);
                y += LINE_H;
            }
        }
    }

    private List<net.minecraft.util.FormattedCharSequence> wrap(Component text) {
        List<net.minecraft.util.FormattedCharSequence> out = new ArrayList<>();
        out.addAll(this.font.split(text, this.width - 40));
        return out;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}