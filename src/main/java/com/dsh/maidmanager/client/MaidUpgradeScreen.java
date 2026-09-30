package com.dsh.maidmanager.client;

import com.dsh.maidmanager.logic.GlobalUpgrade;
import com.dsh.maidmanager.logic.MaidEntry;
import com.dsh.maidmanager.logic.MaidUpgrade;
import com.dsh.maidmanager.logic.ProgressionInfo;
import com.dsh.maidmanager.network.C2SOpenMaidGuiPacket;
import com.dsh.maidmanager.network.C2SPowerBankPacket;
import com.dsh.maidmanager.network.C2SUpgradePacket;
import com.dsh.maidmanager.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The upgrade panel: two tabs sharing one window.
 *
 * <p>The split is deliberate and is the whole design. <b>Per-maid upgrades are bought with that
 * maid's own experience and are numeric</b> - attack, health, armour and so on. <b>Legion
 * abilities are bought with the player's banked P-points and change a rule rather than a number</b>
 * - faster experience for everyone, waiving the death penalty, following the owner through the
 * air. Selling stats for P-points would have made the second tab a duplicate of the first.
 *
 * <p>Two tabs rather than two windows because the terminal's footer already carries five buttons;
 * a sixth and seventh would not fit. The legion tab is reachable without ticking anything, so it
 * is never locked behind a single-maid selection.
 *
 * <p>Rendering stays to plain fills and text, matching the terminal, so the mod does not depend on
 * TLM's GUI assets.
 */
public class MaidUpgradeScreen extends Screen {

    /** Which tab to open on. Public so the terminal can jump straight to either one. */
    public enum Tab {
        SINGLE,
        GLOBAL
    }

    private static final int TITLE_Y = 10;
    private static final int SUBTITLE_Y = 28;
    private static final int LIST_TOP_SINGLE = 58;
    private static final int ROW_H = 26;
    private static final int BAR_W = 120;
    private static final int BAR_H = 8;

    /** Column x-offsets, relative to the centred content box. */
    private static final int COL_LEVEL = 150;
    private static final int COL_BAR = 250;
    private static final int COL_EFFECT = 390;
    /**
     * Widest the content area may get.
     *
     * <p>The right-hand columns are placed relative to this, not at absolute offsets: an absolute
     * button column is wrong on any screen narrower than itself, and the previous value was wider
     * than a 640px screen, so every buy button was drawn off the right edge and could never be
     * clicked.
     */
    private static final int CONTENT_MAX = 760;
    /** Width of a row's buy button, and the margin between it and the content edge. */
    private static final int BUTTON_W = 60;
    private static final int RIGHT_MARGIN = 8;

    private static final int GLOBAL_FUNDS_TOP = 50;
    private static final int GLOBAL_FUNDS_H = 74;
    private static final int GLOBAL_ROW_H = 52;

    private static final int COLOUR_TITLE = 0xFFFFFF;
    private static final int COLOUR_LABEL = 0xE0E0E0;
    private static final int COLOUR_DIM = 0xA0A0A0;
    private static final int COLOUR_FAINT = 0x707070;
    private static final int COLOUR_GOLD = 0xFFCC00;
    private static final int COLOUR_GREEN = 0x4CAF50;
    private static final int COLOUR_RED = 0xE05252;
    private static final int COLOUR_EXP = 0xAAFFAA;
    private static final int COLOUR_BAR_BG = 0xFF181818;
    private static final int COLOUR_BAR_FG = 0xFF4CAF50;
    private static final int COLOUR_BAR_MAX = 0xFF78C878;

    private final Screen parent;
    private final List<MaidEntry> entries;
    private MaidEntry maid;

    private Tab tab = Tab.SINGLE;
    private double scroll;
    private int listTop;
    private int listBottom;
    private int contentLeft;
    private int contentWidth;

    private final Map<MaidUpgrade, Button> singleButtons = new EnumMap<>(MaidUpgrade.class);
    private final Map<GlobalUpgrade, Button> globalButtons = new EnumMap<>(GlobalUpgrade.class);

    public MaidUpgradeScreen(Screen parent, MaidEntry maid, List<MaidEntry> entries) {
        this(parent, maid, entries, Tab.SINGLE);
    }

    /**
     * @param maid  null when opened from the legion button, which has no single maid to show
     * @param initial which tab to start on
     */
    public MaidUpgradeScreen(Screen parent, @Nullable MaidEntry maid, List<MaidEntry> entries,
                             Tab initial) {
        super(Component.translatable("gui.touhou_maid_legion.upgrade.title"));
        this.parent = parent;
        this.maid = maid;
        this.entries = entries;
        this.tab = initial;
    }

    /** Server pushed a new snapshot: pick up the new levels and experience for this maid. */
    public void updateEntries(List<MaidEntry> newEntries) {
        // The legion tab has no single maid, so there is nothing to look her up by. Checked
        // before the loop rather than inside it: every bank action and every purchase makes the
        // server push a fresh list, so without this the whole legion tab throws on first use.
        if (this.maid == null) {
            this.rebuildWidgets();
            return;
        }
        MaidEntry refreshed = null;
        for (MaidEntry candidate : newEntries) {
            if (candidate.id.equals(maid.id)) {
                refreshed = candidate;
                break;
            }
        }
        // She may have vanished from the roster entirely (enrolment removed, or another player's
        // maid). Keep showing the stale row rather than crashing, but stop offering purchases.
        if (refreshed != null) {
            this.maid = refreshed;
        }
        this.rebuildWidgets();
    }

    private ProgressionInfo progression() {
        return ClientPayloadHandlers.progression();
    }

    @Override
    protected void init() {
        singleButtons.clear();
        globalButtons.clear();

        this.contentWidth = Math.min(this.width - 20, CONTENT_MAX);
        this.contentLeft = (this.width - contentWidth) / 2;
        this.listTop = LIST_TOP_SINGLE;
        // Footer holds the two tabs and Close.
        this.listBottom = this.height - 40;

        // No tab buttons: which tab you get is decided by how the screen was opened - the
        // Upgrade chip on a maid's row shows that maid, the terminal's legion button shows the
        // abilities. Both entry points exist on the screen you came from, so a pair of buttons
        // here was a second way to do what the previous screen already offered.
        addRenderableWidget(Button.builder(Component.translatable("gui.touhou_maid_legion.close"),
                        b -> Minecraft.getInstance().setScreen(parent))
                .bounds(this.width - 84, this.height - 30, 74, 20).build());

        if (tab == Tab.SINGLE) {
            initSingleTab();
        } else {
            initGlobalTab();
        }
        rebuildWidgetsDone();
    }

    private void rebuildWidgetsDone() {
        // Nothing to do now that the tab buttons are gone; kept as the single place that runs
        // after the tab's widgets exist, so a future per-tab fix has somewhere obvious to go.
    }


    // ------------------------------------------------------------------
    // Per-maid tab
    // ------------------------------------------------------------------

    private void initSingleTab() {
        int rowY = listTop;
        for (MaidUpgrade upgrade : MaidUpgrade.values()) {
            final MaidUpgrade target = upgrade;
            Button button = addRenderableWidget(Button.builder(
                            Component.translatable("gui.touhou_maid_legion.upgrade.buy"),
                            b -> buySingle(target))
                    .bounds(singleButtonX(), 0, BUTTON_W, 18).build());
            singleButtons.put(upgrade, button);
            rowY += ROW_H;
        }
        layoutSingleRows();
    }

    /**
     * Positions the per-maid buttons, honouring the scroll offset.
     *
     * <p>The header can be short enough on a small GUI scale that nine rows do not fit, so the
     * list scrolls and the buttons have to move with it - they are real widgets and cannot simply
     * be drawn at an offset like the row text is.
     */
    private void layoutSingleRows() {
        int visible = listBottom - listTop;
        int total = MaidUpgrade.values().length * ROW_H;
        double max = Math.max(0, total - visible);
        scroll = Mth.clamp(scroll, 0, max);

        int index = 0;
        for (MaidUpgrade upgrade : MaidUpgrade.values()) {
            Button button = singleButtons.get(upgrade);
            if (button != null) {
                int y = listTop + index * ROW_H - (int) scroll;
                button.setY(y + 3);
                button.visible = y + ROW_H > listTop && y < listBottom;
            }
            index++;
        }
    }

    /** Left edge of a row's buy button: hard against the right of the content area. */
    private int singleButtonX() {
        return contentLeft + contentWidth - RIGHT_MARGIN - BUTTON_W;
    }

    /** Left edge that right-aligns a cost label in the gap before the buy button. */
    private int costRightAligned(String text) {
        return singleButtonX() - 10 - this.font.width(text);
    }
    private void buySingle(MaidUpgrade upgrade) {
        NetworkHandler.sendToServer(new C2SUpgradePacket(maid.id, upgrade.id()));
    }

    // ------------------------------------------------------------------
    // Legion tab
    // ------------------------------------------------------------------

    private void initGlobalTab() {
        int y = GLOBAL_FUNDS_TOP + GLOBAL_FUNDS_H + 10;
        for (GlobalUpgrade ability : GlobalUpgrade.values()) {
            final GlobalUpgrade target = ability;
            Button button = addRenderableWidget(Button.builder(
                            Component.translatable("gui.touhou_maid_legion.upgrade.buy"),
                            b -> buyGlobal(target))
                    .bounds(contentLeft + contentWidth - 96, y + 14, 90, 20).build());
            globalButtons.put(ability, button);
            y += GLOBAL_ROW_H;
        }
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.touhou_maid_legion.bank.deposit"),
                        b -> sendBank(C2SPowerBankPacket.Action.DEPOSIT))
                .bounds(contentLeft + contentWidth - 320, GLOBAL_FUNDS_TOP + 8, 100, 18).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.touhou_maid_legion.bank.withdraw"),
                        b -> sendBank(C2SPowerBankPacket.Action.WITHDRAW))
                .bounds(contentLeft + contentWidth - 214, GLOBAL_FUNDS_TOP + 8, 100, 18).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.touhou_maid_legion.bank.auto"),
                        b -> sendBank(C2SPowerBankPacket.Action.TOGGLE_AUTO))
                .bounds(contentLeft + contentWidth - 320, GLOBAL_FUNDS_TOP + 28, 206, 18).build());
        // The two routes that bypass TLM's 5.0 wallet. They belong with the bank rather than in a
        // menu of their own, because both of them end up as banked points.
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.touhou_maid_legion.bank.drain_lamp"),
                        b -> sendBank(C2SPowerBankPacket.Action.DRAIN_LAMP))
                .bounds(contentLeft + contentWidth - 320, GLOBAL_FUNDS_TOP + 50, 152, 18).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.touhou_maid_legion.bank.deposit_items"),
                        b -> sendBank(C2SPowerBankPacket.Action.DEPOSIT_ITEMS))
                .bounds(contentLeft + contentWidth - 162, GLOBAL_FUNDS_TOP + 50, 152, 18).build());
    }

    private void sendBank(C2SPowerBankPacket.Action action) {
        NetworkHandler.sendToServer(new C2SPowerBankPacket(action));
    }

    /**
     * Buys an ability, or flips its switch when it is already owned and toggleable.
     *
     * <p>One button for both, because the two actions are mutually exclusive per ability: an
     * unowned one can only be bought, an owned-and-toggleable one can only be switched.
     */
    private void buyGlobal(GlobalUpgrade ability) {
        boolean owned = progression().has(ability);
        NetworkHandler.sendToServer(new C2SUpgradePacket(null, ability.id(),
                owned && ability.toggleable()
                        ? C2SUpgradePacket.Mode.TOGGLE : C2SUpgradePacket.Mode.BUY));
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Draw order is super.render, then our content, then the widgets again.
        //
        // <p>1.21 removed Screen.renderBackground(GuiGraphics) and moved the blur + dim pass
        // into super.render(), so the background cannot be drawn first and our content second
        // without the dim landing on top of it. Our row bands and the funds block also overlap
        // where the buttons sit, so the widgets are redrawn last to stay on top. Drawing them
        // twice is idempotent - the second pass paints exactly the same pixels.
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, COLOUR_TITLE);
        refreshButtonStates();

        if (tab == Tab.SINGLE) {
            renderSingleTab(graphics, mouseX, mouseY);
        } else {
            renderGlobalTab(graphics, mouseX, mouseY);
        }
        // Buttons are drawn last so row text never bleeds through them.
        for (var widget : this.renderables) {
            if (widget instanceof Button button) {
                button.render(graphics, mouseX, mouseY, partialTick);
            }
        }
    }

    private void refreshButtonStates() {
        if (tab == Tab.SINGLE) {
            if (maid == null) {
                return;
            }
            for (MaidUpgrade upgrade : MaidUpgrade.values()) {
                Button button = singleButtons.get(upgrade);
                if (button == null) {
                    continue;
                }
                int level = maid.levelOf(upgrade);
                boolean maxed = level >= upgrade.maxLevel();
                boolean affordable = maid.experience >= upgrade.costFor(level);
                // Three distinct reasons a button is dark, so the label says which one.
                button.setMessage(Component.translatable(maxed
                        ? "gui.touhou_maid_legion.upgrade.maxed"
                        : "gui.touhou_maid_legion.upgrade.buy"));
                button.active = !maxed && affordable && maid.upgradable();
            }
        } else {
            ProgressionInfo info = progression();
            for (GlobalUpgrade ability : GlobalUpgrade.values()) {
                Button button = globalButtons.get(ability);
                if (button == null) {
                    continue;
                }
                boolean owned = info.has(ability);
                if (owned && ability.toggleable()) {
                    // Bought and switchable: the button becomes the switch, always live, so a
                    // player can park the ability without losing the purchase.
                    button.setMessage(Component.translatable(info.isEnabled(ability)
                            ? "gui.touhou_maid_legion.upgrade.enabled"
                            : "gui.touhou_maid_legion.upgrade.disabled"));
                    button.active = true;
                } else {
                    button.setMessage(Component.translatable(owned
                            ? "gui.touhou_maid_legion.upgrade.owned"
                            : "gui.touhou_maid_legion.upgrade.buy"));
                    if (ability.buyableWithShrines()) {
                    button.setMessage(Component.translatable("gui.touhou_maid_legion.global.g_shrine.buy"));
                    button.active = !owned && info.shrineCount() >= ability.shrineCost();
                } else {
                    button.active = !owned && info.bank() >= ability.powerCost();
                }
                }
            }
        }
    }

    private void renderSingleTab(GuiGraphics graphics, int mouseX, int mouseY) {
        MaidEntry entry = this.maid;
        if (entry == null) {
            // Reached only if the tab was switched without a maid selected; harmless to explain.
            graphics.drawString(this.font,
                    Component.translatable("gui.touhou_maid_legion.upgrade.pick_one").getString(),
                    contentLeft, listTop + 4, COLOUR_DIM);
            return;
        }
        String name = entry.name.getString();
        graphics.drawString(this.font, name + "  #" + entry.shortId(), contentLeft, SUBTITLE_Y,
                COLOUR_LABEL);
        String expText = Component.translatable("gui.touhou_maid_legion.upgrade.experience",
                String.format("%,d", entry.experience)).getString();
        graphics.drawString(this.font, expText, contentLeft + contentWidth - this.font.width(expText),
                SUBTITLE_Y, COLOUR_EXP);
        graphics.drawString(this.font,
                Component.translatable("gui.touhou_maid_legion.upgrade.currency_maid").getString(),
                contentLeft, SUBTITLE_Y + 10, COLOUR_FAINT);

        if (!entry.upgradable()) {
            // UNLOADED maids live in TLM's world data; we cannot read or write their experience.
            graphics.drawString(this.font,
                    Component.translatable("gui.touhou_maid_legion.upgrade.unreachable").getString(),
                    contentLeft, listTop + 4, COLOUR_RED);
            return;
        }

        graphics.enableScissor(contentLeft - 4, listTop, contentLeft + contentWidth, listBottom);
        int index = 0;
        for (MaidUpgrade upgrade : MaidUpgrade.values()) {
            int y = listTop + index * ROW_H - (int) scroll;
            index++;
            if (y + ROW_H < listTop || y > listBottom) {
                continue;
            }
            if (index % 2 == 1) {
                graphics.fill(contentLeft - 4, y, contentLeft + contentWidth, y + ROW_H - 2,
                        0x18FFFFFF);
            }
            int level = entry.levelOf(upgrade);
            int max = upgrade.maxLevel();

            graphics.drawString(this.font,
                    Component.translatable(upgrade.translationKey()).getString(),
                    contentLeft + 4, y + 6, COLOUR_LABEL);
            graphics.drawString(this.font, level + " / " + max,
                    contentLeft + COL_LEVEL, y + 6,
                    level >= max ? COLOUR_GREEN : COLOUR_GOLD);
            renderBar(graphics, contentLeft + COL_BAR, y + 8, level, max);
            graphics.drawString(this.font, effectText(upgrade, level).getString(),
                    contentLeft + COL_EFFECT, y + 6,
                    level > 0 ? COLOUR_LABEL : COLOUR_FAINT);

            if (level < max) {
                int cost = upgrade.costFor(level);
                String costText = Component.translatable("gui.touhou_maid_legion.upgrade.next",
                        String.format("%,d", cost)).getString();
                graphics.drawString(this.font, costText,
                        costRightAligned(costText), y + 6,
                        entry.experience >= cost ? COLOUR_DIM : COLOUR_RED);
            } else {
                graphics.drawString(this.font, "-", costRightAligned("-"), y + 6, COLOUR_FAINT);
            }
        }
        graphics.disableScissor();
    }

    private void renderBar(GuiGraphics graphics, int x, int y, int level, int max) {
        graphics.fill(x, y, x + BAR_W, y + BAR_H, COLOUR_BAR_BG);
        if (level > 0) {
            int filled = (int) (BAR_W * (level / (float) max));
            graphics.fill(x + 1, y + 1, x + Math.max(2, filled), y + BAR_H - 1,
                    level >= max ? COLOUR_BAR_MAX : COLOUR_BAR_FG);
        }
    }

    private Component effectText(MaidUpgrade upgrade, int level) {
        if (level <= 0) {
            return Component.translatable("gui.touhou_maid_legion.upgrade.none");
        }
        String value = upgrade.displayAt(level);
        if (upgrade.unit() == MaidUpgrade.Unit.PERCENT) {
            return Component.literal("+" + value);
        }
        return Component.translatable("gui.touhou_maid_legion.upgrade.effect_flat", value,
                Component.translatable("gui.touhou_maid_legion.unit." + upgrade.id()));
    }

    private void renderGlobalTab(GuiGraphics graphics, int mouseX, int mouseY) {
        ProgressionInfo info = progression();

        graphics.drawCenteredString(this.font,
                Component.translatable("gui.touhou_maid_legion.upgrade.currency_legion"),
                this.width / 2, SUBTITLE_Y, COLOUR_DIM);

        int fundsTop = GLOBAL_FUNDS_TOP;
        graphics.fill(contentLeft, fundsTop, contentLeft + contentWidth, fundsTop + GLOBAL_FUNDS_H,
                0xA0000000);
        graphics.drawString(this.font,
                Component.translatable("gui.touhou_maid_legion.bank.wallet").getString(),
                contentLeft + 8, fundsTop + 6, COLOUR_DIM);
        graphics.drawString(this.font, String.format("%.2f P", info.wallet()),
                contentLeft + 8, fundsTop + 20, COLOUR_GOLD);
        // Calling out the cap prevents "why did my points disappear" - TLM inlines 5.0 into its
        // own capability, so it genuinely cannot be raised.
        graphics.drawString(this.font,
                Component.translatable("gui.touhou_maid_legion.bank.wallet_cap").getString(),
                contentLeft + 8, fundsTop + 34, COLOUR_FAINT);

        graphics.drawString(this.font,
                Component.translatable("gui.touhou_maid_legion.bank.bank").getString(),
                contentLeft + 150, fundsTop + 6, COLOUR_DIM);
        graphics.drawString(this.font,
                String.format("%.1f / %.1f P", info.bank(), info.bankCap()),
                contentLeft + 150, fundsTop + 20, COLOUR_GREEN);
        // Both of these are spent from the bank, so they are shown against it rather than in the
        // ability rows: whether a lamp is bound decides whether the drain button can do anything,
        // and the shrine count is the price of the shrine revival unlock.
        graphics.drawString(this.font,
                Component.translatable(info.lampBound()
                        ? "gui.touhou_maid_legion.bank.lamp_yes"
                        : "gui.touhou_maid_legion.bank.lamp_none").getString(),
                contentLeft + 150, fundsTop + 54, info.lampBound() ? COLOUR_GREEN : COLOUR_DIM);
        graphics.drawString(this.font,
                Component.translatable("gui.touhou_maid_legion.bank.shrines",
                        String.valueOf(info.shrineCount()),
                        String.valueOf(com.dsh.maidmanager.logic.GlobalUpgrade.SHRINE_REVIVE.shrineCost())
                ).getString(),
                contentLeft + 150, fundsTop + 66, COLOUR_LABEL);

        int barX = contentLeft + 150;
        int barW = 200;
        graphics.fill(barX, fundsTop + 36, barX + barW, fundsTop + 42, COLOUR_BAR_BG);
        if (info.bankCap() > 0.0F) {
            int filled = (int) (barW * Math.min(1.0F, info.bank() / info.bankCap()));
            graphics.fill(barX + 1, fundsTop + 37, barX + Math.max(2, filled), fundsTop + 41,
                    COLOUR_BAR_FG);
        }

        String autoKey = info.autoDeposit()
                ? "gui.touhou_maid_legion.bank.auto_on"
                : "gui.touhou_maid_legion.bank.auto_off";
        // Rewrite the auto button's label with the live state.
        for (var widget : this.renderables) {
            if (widget instanceof Button button
                    && button.getMessage().getString()
                    .equals(Component.translatable("gui.touhou_maid_legion.bank.auto").getString())) {
                button.setMessage(Component.translatable(autoKey));
            }
        }

        int y = fundsTop + GLOBAL_FUNDS_H + 10;
        for (GlobalUpgrade ability : GlobalUpgrade.values()) {
            boolean owned = info.has(ability);
            if ((ability.ordinal() & 1) == 1) {
                graphics.fill(contentLeft - 4, y, contentLeft + contentWidth, y + GLOBAL_ROW_H - 6,
                        0x18FFFFFF);
            }
            graphics.drawString(this.font,
                    Component.translatable(ability.translationKey()).getString(),
                    contentLeft + 4, y + 4, COLOUR_TITLE);
            // The description is clipped to the gap before the price column. A long translation
            // used to run straight through the price and the button, which read as one smear of
            // overlapping text.
            int descLeft = contentLeft + 150;
            int descLimit = contentLeft + contentWidth - 160;
            String desc = this.font.plainSubstrByWidth(
                    Component.translatable(ability.descriptionKey()).getString(),
                    Math.max(20, descLimit - descLeft));
            graphics.drawString(this.font, desc, descLeft, y + 4, COLOUR_LABEL);

            // "Owned - permanent" is wrong for a toggleable ability: it can be switched off, so
            // say what the switch is actually set to instead of contradicting the button beside it.
            Component status;
            int statusColour;
            if (!owned) {
                status = Component.translatable("gui.touhou_maid_legion.global.status_locked");
                statusColour = COLOUR_FAINT;
            } else if (ability.toggleable()) {
                status = Component.translatable(info.isEnabled(ability)
                        ? "gui.touhou_maid_legion.global.status_on"
                        : "gui.touhou_maid_legion.global.status_off");
                statusColour = info.isEnabled(ability) ? COLOUR_GREEN : COLOUR_GOLD;
            } else {
                status = Component.translatable("gui.touhou_maid_legion.global.status_owned");
                statusColour = COLOUR_GREEN;
            }
            graphics.drawString(this.font, status.getString(), descLeft, y + 18, statusColour);

            // Say what it actually costs: shrines for the one unlocked with them, points otherwise.
            String cost = ability.buyableWithShrines()
                    ? ability.shrineCost() + " " + Component.translatable(
                            "gui.touhou_maid_legion.global.unit_shrine").getString()
                    : ability.powerCost() + " P";
            graphics.drawString(this.font, cost,
                    contentLeft + contentWidth - 150, y + 16,
                    owned ? COLOUR_FAINT : COLOUR_GOLD);
            y += GLOBAL_ROW_H;
        }

        String scope = Component.translatable("gui.touhou_maid_legion.global.scope",
                String.valueOf(entries.size())).getString();
        graphics.drawString(this.font, scope, contentLeft, y + 2, COLOUR_DIM);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab == Tab.SINGLE) {
            int total = MaidUpgrade.values().length * ROW_H;
            double max = Math.max(0, total - (listBottom - listTop));
            this.scroll = Mth.clamp(this.scroll - scrollY * ROW_H, 0, max);
            layoutSingleRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
