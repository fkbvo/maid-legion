package com.dsh.maidmanager.client;

import com.dsh.maidmanager.logic.MaidEntry;
import com.dsh.maidmanager.logic.MaidState;
import com.dsh.maidmanager.network.C2SMaidActionPacket;
import com.dsh.maidmanager.network.NetworkHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The maid terminal: a full-screen list of every maid with per-row selection, a status
 * badge and a per-maid force-load switch.
 *
 * <p>Rendering is kept to plain fills and text so the screen does not depend on TLM's own
 * GUI code or assets, which keeps the mod clear of TLM's asset licence.
 */
public class MaidManagerScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    /**
     * Vertical layout, top to bottom:
     * <pre>
     *   8 .. 24   title
     *  22 .. 38   search box and toolbar buttons
     *  42 .. 54   column headers          &lt;- own band, nothing else may draw here
     *  56 ..      rows (listTop)
     *             footer buttons (listBottom)
     * </pre>
     */
    private static final int HEADER_HEIGHT = 56;
    private static final int COLUMN_HEADER_Y = 44;
    private static final int FOOTER_HEIGHT = 32;
    private static final int BADGE_W = 54;
    private static final int BAR_W = 40;
    /** Gap between the health bar and the force-load switch column. */
    private static final int HP_GAP = 60;
    /** Width of the force-load switch box. */
    private static final int SWITCH_W = 30;
    /** Horizontal padding to the right of the status badge. */
    private static final int BADGE_PAD = 4;
    /** Width of the favourite star column, left of the tick box. */
    private static final int STAR_W = 14;

    // Footer button widths. Fixed rather than measured from the label, so a longer translation
    // cannot push the row off screen - the wrap check uses these numbers.
    private static final int OPEN_GUI_W = 110;
    private static final int UPGRADE_W = 56;
    private static final int SUMMON_W = 96;
    private static final int STORE_W = 96;
    private static final int REFRESH_W = 60;

    private final List<Row> rows = new ArrayList<>();
    private List<MaidEntry> entries = List.of();
    private EditBox searchBox;
    private Button summonButton;
    private Button storeButton;
    private Button favouritesButton;
    private Button openGuiButton;
    private Button upgradeButton;
    private double scroll;
    private int listTop;
    private int listBottom;
    private int listLeft;
    private int listRight;
    private String filter = "";
    /** When true only starred maids are listed. */
    private boolean favouritesOnly;

    /** Tooltip queued during row rendering, flushed at the end of render(). */
    private List<Component> pendingTooltip;
    private int pendingTooltipX;
    private int pendingTooltipY;

    public MaidManagerScreen() {
        super(Component.translatable("gui.maid_legion.title"));
    }

    /** Opens the screen and asks the server for a fresh snapshot. */
    public void openAndRefresh() {
        net.minecraft.client.Minecraft.getInstance().setScreen(this);
        ClientInput.requestRefresh();
    }

    @Override
    protected void init() {
        this.listLeft = 8;
        this.listRight = this.width - 8;
        this.listTop = HEADER_HEIGHT;
        this.listBottom = this.height - FOOTER_HEIGHT;

        this.searchBox = new EditBox(this.font, listLeft + 4, 22, 132, 16,
                Component.translatable("gui.maid_legion.search"));
        this.searchBox.setMaxLength(48);
        this.searchBox.setResponder(text -> {
            this.filter = text.toLowerCase(Locale.ROOT);
            rebuildRows();
        });
        addRenderableWidget(this.searchBox);

        // Footer: five buttons, centred, wrapped onto two rows when the window is too narrow to
        // hold them side by side. Widths are fixed rather than text-measured so the layout cannot
        // shift when a translation changes.
        // Footer: five buttons in two visually separate groups, centred as a whole. The first
        // group acts on one chosen maid, the second on the whole batch; the wider gap between
        // them is what makes that difference readable at a glance.
        int[] widths = {OPEN_GUI_W, UPGRADE_W, SUMMON_W, STORE_W, REFRESH_W};
        int gap = 6;
        // Larger than `gap`, so the two groups do not read as one run of five.
        int groupGap = 18;
        int total = widths[0] + widths[1] + widths[2] + widths[3] + widths[4]
                + 3 * gap + groupGap;
        boolean wrap = total > this.width - 16;
        // A wrapped footer is taller, so the list has to stop higher up or the second row would
        // be drawn over the last maid.
        this.listBottom = this.height - (wrap ? FOOTER_HEIGHT * 2 - 8 : FOOTER_HEIGHT);

        int row2Y = this.height - 28;
        int row1Y = wrap ? row2Y - 24 : row2Y;

        if (wrap) {
            // Row 1: the single-maid actions. Row 2: the batch actions.
            int row1 = widths[0] + gap + widths[1];
            int x = this.width / 2 - row1 / 2;
            this.openGuiButton = addRenderableWidget(Button.builder(
                            Component.translatable("gui.maid_legion.open_gui"),
                            b -> openSelectedMaidGui())
                    .bounds(x, row1Y, widths[0], 20).build());
            this.upgradeButton = addRenderableWidget(Button.builder(
                            Component.translatable("gui.maid_legion.upgrade"),
                            b -> openUpgradeScreen())
                    .bounds(x + widths[0] + gap, row1Y, widths[1], 20).build());
            int row2 = widths[2] + gap + widths[3] + gap + widths[4];
            x = this.width / 2 - row2 / 2;
            this.summonButton = addRenderableWidget(Button.builder(
                            Component.translatable("gui.maid_legion.summon"), b -> summonSelected())
                    .bounds(x, row2Y, widths[2], 20).build());
            x += widths[2] + gap;
            this.storeButton = addRenderableWidget(Button.builder(
                            Component.translatable("gui.maid_legion.store"), b -> storeSelected())
                    .bounds(x, row2Y, widths[3], 20).build());
            x += widths[3] + gap;
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.maid_legion.refresh"),
                            b -> ClientInput.requestRefresh())
                    .bounds(x, row2Y, widths[4], 20).build());
        } else {
            int x = this.width / 2 - total / 2;
            this.openGuiButton = addRenderableWidget(Button.builder(
                            Component.translatable("gui.maid_legion.open_gui"),
                            b -> openSelectedMaidGui())
                    .bounds(x, row2Y, widths[0], 20).build());
            x += widths[0] + gap;
            this.upgradeButton = addRenderableWidget(Button.builder(
                            Component.translatable("gui.maid_legion.upgrade"),
                            b -> openUpgradeScreen())
                    .bounds(x, row2Y, widths[1], 20).build());
            x += widths[1] + groupGap;
            this.summonButton = addRenderableWidget(Button.builder(
                            Component.translatable("gui.maid_legion.summon"), b -> summonSelected())
                    .bounds(x, row2Y, widths[2], 20).build());
            x += widths[2] + gap;
            this.storeButton = addRenderableWidget(Button.builder(
                            Component.translatable("gui.maid_legion.store"), b -> storeSelected())
                    .bounds(x, row2Y, widths[3], 20).build());
            x += widths[3] + gap;
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.maid_legion.refresh"),
                            b -> ClientInput.requestRefresh())
                    .bounds(x, row2Y, widths[4], 20).build());
        }
        // There is deliberately no revive button here. Revive lives on the fallen maid's own
        // status badge, which turns into a clickable "revive" while the cursor is over it, so
        // the action sits on the row it applies to instead of in a separate row at the bottom
        // that has to stay greyed out whenever nothing dead is ticked.

        addRenderableWidget(Button.builder(
                        Component.translatable("gui.maid_legion.select_all"), b -> selectAll(true))
                .bounds(listRight - 128, 22, 60, 16).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.maid_legion.clear"), b -> selectAll(false))
                .bounds(listRight - 62, 22, 60, 16).build());

        // A help button opening the full usage guide. The force-load prompt is only one part of
        // how the mod works, and a player who has forgotten the hotkeys or the states has
        // nowhere else to look them up in game.
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.maid_legion.help"),
                        b -> net.minecraft.client.Minecraft.getInstance().setScreen(
                                new HelpScreen(this)))
                .bounds(listRight - 164, 22, 30, 16).build());

        // Toggle that narrows the list to starred maids only.
        this.favouritesButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.maid_legion.only_favourites"), b -> {
                            favouritesOnly = !favouritesOnly;
                            b.setMessage(Component.translatable(favouritesOnly
                                    ? "gui.maid_legion.only_favourites.on"
                                    : "gui.maid_legion.only_favourites"));
                            rebuildRows();
                        })
                .bounds(listRight - 256, 22, 86, 16).build());

        rebuildRows();
        ClientInput.requestRefresh();
    }

    public void updateEntries(List<MaidEntry> newEntries) {
        this.entries = sort(newEntries);
        rebuildRows();
    }

    private static List<MaidEntry> sort(List<MaidEntry> in) {
        List<MaidEntry> out = new ArrayList<>(in);
        // Starred maids are pinned above everything, regardless of state - that is the whole
        // point of favouriting. Within each of the two groups the order is:
        // state, newest stored first, name, then UUID so it is fully deterministic even when
        // two maids share a name.
        out.sort(Comparator
                .comparing((MaidEntry e) -> !e.favourite)
                .thenComparingInt(e -> e.state.ordinal())
                .thenComparing(Comparator.comparingLong((MaidEntry e) -> e.storedAt).reversed())
                .thenComparing(e -> e.name.getString(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> e.id));
        return out;
    }

    private void rebuildRows() {
        rows.clear();
        for (MaidEntry entry : entries) {
            if (favouritesOnly && !entry.favourite) {
                continue;
            }
            if (!filter.isEmpty()
                    && !entry.name.getString().toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            rows.add(new Row(entry));
        }
        clampScroll();
    }

    private void clampScroll() {
        double max = Math.max(0, rows.size() * ROW_HEIGHT - (listBottom - listTop));
        scroll = Mth.clamp(scroll, 0, max);
    }

    private void selectAll(boolean selected) {
        for (Row row : rows) {
            if (row.selectable()) {
                ClientSelection.set(row.entry.id, selected);
            }
        }
    }

    /**
     * The one maid the selection-based footer buttons apply to, or null.
     *
     * <p>These buttons act on a single maid and have no sensible meaning for a batch. Returning
     * null for any selection other than exactly one also gives the buttons their enable state, so
     * "why is this greyed out" always has the same answer.
     */
    private MaidEntry soleSelected() {
        MaidEntry found = null;
        for (Row row : rows) {
            if (row.selectable() && ClientSelection.isSelected(row.entry.id)) {
                if (found != null) {
                    return null;
                }
                found = row.entry;
            }
        }
        return found;
    }

    private List<UUID> selectedIds() {
        List<UUID> ids = new ArrayList<>();
        for (Row row : rows) {
            if (row.selectable() && ClientSelection.isSelected(row.entry.id)) {
                ids.add(row.entry.id);
            }
        }
        return ids;
    }

    /**
     * Summons the selected maids that are actually summonable.
     *
     * <p>Filtered here for the same reason revive is: a mixed selection should do the sensible
     * thing rather than have the whole batch rejected for containing one dead maid.
     */
    private void summonSelected() {
        List<UUID> ids = selectedFor(entry -> entry.summonable());
        if (ids.isEmpty()) {
            return;
        }
        ClientInput.sendAction(C2SMaidActionPacket.Action.SUMMON, ids);
    }

    private void storeSelected() {
        List<UUID> ids = selectedFor(entry -> entry.storeable());
        if (ids.isEmpty()) {
            return;
        }
        ClientInput.sendAction(C2SMaidActionPacket.Action.STORE, ids);
    }

    /** The ticked ids whose entry satisfies {@code test}, in list order. */
    private List<UUID> selectedFor(java.util.function.Predicate<MaidEntry> test) {
        List<UUID> ids = new ArrayList<>();
        for (Row row : rows) {
            if (row.selectable() && test.test(row.entry) && ClientSelection.isSelected(row.entry.id)) {
                ids.add(row.entry.id);
            }
        }
        return ids;
    }

    /** True when at least one selected row can be summoned right now. */
    private boolean hasSummonableSelected() {
        return !selectedFor(entry -> entry.summonable()).isEmpty();
    }

    /** True when at least one selected row can be stored right now. */
    private boolean hasStoreableSelected() {
        return !selectedFor(entry -> entry.storeable()).isEmpty();
    }

    /**
     * Revives one fallen maid, triggered by clicking her own status badge.
     *
     * <p>Per-maid rather than batched on purpose: a revive costs real materials, so the action
     * belongs on the row it applies to. A batch button had to live in its own row at the
     * bottom of the screen and stayed greyed out until the right kind of maid was ticked,
     * which is a lot of interface for something the player aims at directly anyway.
     */
    private void reviveOne(MaidEntry entry) {
        if (!entry.revivable()) {
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(
                new com.dsh.maidmanager.network.C2SReviveMaidPacket(entry.id));
    }

    /**
     * Asks the server to open the sole selected maid's own TLM GUI.
     *
     * <p>The server does the work, so this is deliberately fire-and-forget: she may be stored,
     * unloaded or in another dimension, and all three are answered by the server rather than
     * guessed at here.
     */
    private void openSelectedMaidGui() {
        MaidEntry sole = soleSelected();
        if (sole == null) {
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(
                new com.dsh.maidmanager.network.C2SOpenMaidGuiPacket(sole.id));
    }

    /** Opens the two-tab upgrade panel, on the per-maid tab with the sole selection. */
    private void openUpgradeScreen() {
        MaidEntry sole = soleSelected();
        if (sole == null) {
            return;
        }
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new MaidUpgradeScreen(this, sole, entries));
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);

        // Each button lights up only for the rows it can actually act on. Selection is now
        // allowed to include fallen maids, so "something is ticked" is no longer enough to
        // mean "summon works" - a dead-only selection would otherwise enable Summon and
        // Store, which reject every id in it.
        if (summonButton != null) {
            summonButton.active = hasSummonableSelected();
        }
        if (storeButton != null) {
            storeButton.active = hasStoreableSelected();
        }
        // Both new buttons act on one maid and need a genuine single selection. A dead maid still
        // counts: the upgrade panel can sell her upgrades from her death record, and opening her
        // GUI is the server's call to accept or refuse.
        MaidEntry sole = soleSelected();
        if (openGuiButton != null) {
            openGuiButton.active = sole != null;
        }
        if (upgradeButton != null) {
            upgradeButton.active = sole != null && sole.upgradable();
        }

        // Column headers first, so the toolbar widgets drawn by super.render() sit above them
        // rather than being overdrawn. The two bands do not overlap by design.
        renderHeader(graphics);

        graphics.fill(listLeft, listTop - 2, listRight, listTop, 0xFF808080);
        graphics.fill(listLeft, listBottom, listRight, listBottom + 1, 0xFF808080);

        graphics.enableScissor(listLeft, listTop, listRight, listBottom);
        int y = listTop - (int) scroll;
        for (Row row : rows) {
            if (y + ROW_HEIGHT >= listTop && y <= listBottom) {
                renderRow(graphics, row, y, mouseX, mouseY);
            }
            y += ROW_HEIGHT;
        }
        graphics.disableScissor();

        if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("gui.maid_legion.empty"),
                    this.width / 2, listTop + 16, 0xFFA0A0A0);
        }

        renderScrollbar(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        // The selection counter sits in the footer band, *below* the list's bottom rule.
        // Drawing it above the rule made it overlap the last visible maid's name.
        graphics.drawString(this.font,
                Component.translatable("gui.maid_legion.count", selectedIds().size(), rows.size()),
                listLeft + 4, listBottom + 11, 0xFFC0C0C0, false);

        // Tooltips go last so they are always fully visible, never clipped by the scissor
        // used for the row list nor overdrawn by the toolbar buttons.
        if (this.pendingTooltip != null) {
            graphics.renderComponentTooltip(this.font, this.pendingTooltip,
                    this.pendingTooltipX, this.pendingTooltipY);
            this.pendingTooltip = null;
        }
    }

    private void renderRow(GuiGraphics graphics, Row row, int y, int mouseX, int mouseY) {
        MaidEntry entry = row.entry;
        boolean hovered = mouseY >= y && mouseY < y + ROW_HEIGHT
                && mouseX >= listLeft && mouseX <= listRight;

        if (hovered) {
            graphics.fill(listLeft, y, listRight, y + ROW_HEIGHT, 0x30FFFFFF);
        }

        boolean selected = ClientSelection.isSelected(entry.id);

        // Favourite star, left of the tick box.
        int starX = listLeft + 2;
        boolean starHovered = hovered && mouseX >= starX && mouseX < starX + STAR_W;
        graphics.drawString(this.font, entry.favourite ? "*" : "-",
                starX + 3, y + 8,
                entry.favourite ? 0xFFFFD54F : (starHovered ? 0xFFA0A0A0 : 0xFF505050), false);

        int boxX = starX + STAR_W;
        graphics.fill(boxX, y + 6, boxX + 12, y + 18, 0xFF000000);
        graphics.fill(boxX + 1, y + 7, boxX + 11, y + 17,
                row.selectable() ? (selected ? 0xFF4CAF50 : 0xFF404040) : 0xFF2A2A2A);
        if (selected) {
            graphics.drawString(this.font, "x", boxX + 3, y + 8, 0xFFFFFF, false);
        }

        int textX = boxX + 18;
        int textColor = row.selectable() ? 0xFFFFFFFF : 0xFF808080;

        // Reserve the exact space the health readout will occupy so a long name cannot run
        // under it. Maids without a health row may use the full width up to the switch.
        int nameLimit = nameRightLimit(entry);
        graphics.drawString(this.font, trim(entry.name.getString(), nameLimit - textX),
                textX, y + 3, textColor, false);

        // Second line: state + location.
        Component sub = subtitle(entry);
        graphics.drawString(this.font, sub, textX, y + 13, 0xFFA0A0A0, false);

        // Health readout for loaded maids: the number sits immediately left of the bar so the
        // two read as one unit, instead of the number living far away in the subtitle line.
        if (entry.state == MaidState.PRESENT) {
            int barW = BAR_W;
            int barX = listRight - BADGE_W - barW - HP_GAP;
            Component hp = Component.literal(String.format(Locale.ROOT, "%.0f/%.0f",
                    entry.health, entry.maxHealth));
            int hpWidth = this.font.width(hp);
            int hpX = barX - hpWidth - 3;

            graphics.drawString(this.font, hp, hpX, y + 8, 0xFFFF8080, false);
            graphics.fill(barX, y + 8, barX + barW, y + 14, 0xFF200000);
            int filled = (int) (barW * entry.healthFraction());
            graphics.fill(barX, y + 8, barX + filled, y + 14, 0xFFD32F2F);
        }

        // Status badge. For a fallen maid this doubles as the revive control: hovering it turns
        // the label into an action, so the cost-bearing revive sits on the row it applies to
        // rather than in a separate button row that had to be greyed out most of the time.
        int badgeX = listRight - BADGE_W - BADGE_PAD;
        boolean badgeHovered = hovered && mouseX >= badgeX && mouseX <= badgeX + BADGE_W;
        boolean revivable = entry.revivable();
        boolean showRevive = revivable && badgeHovered;
        int badgeColor = switch (entry.state) {
            case PRESENT -> 0xFF2E7D32;
            case STORED -> 0xFF1565C0;
            case UNLOADED -> 0xFF6A1B9A;
            // Red-grey: visibly different from the three live states at a glance, without
            // competing with the health bar's red for attention.
            case DEAD -> showRevive ? 0xFF2E7D32 : 0xFF8E2424;
        };
        graphics.fill(badgeX, y + 6, badgeX + BADGE_W, y + 18, badgeColor);
        graphics.drawCenteredString(this.font,
                Component.translatable(showRevive
                        ? "gui.maid_legion.revive"
                        : entry.state.translationKey()),
                badgeX + BADGE_W / 2, y + 8, 0xFFFFFFFF);

        if (showRevive) {
            // Say what it costs before the click, since reviving spends real materials.
            List<Component> tip = new ArrayList<>();
            tip.add(Component.translatable("gui.maid_legion.revive.tooltip.title")
                    .withStyle(ChatFormatting.BOLD));
            tip.add(Component.translatable("gui.maid_legion.revive.tooltip.materials"));
            tip.add(Component.translatable("gui.maid_legion.revive.tooltip.click"));
            this.pendingTooltip = tip;
            this.pendingTooltipX = mouseX;
            this.pendingTooltipY = mouseY;
        }

        // Per-maid force-load switch.
        int swX = badgeX - SWITCH_W - 4;
        if (row.switchVisible()) {
            boolean allowed = ClientPayloadHandlers.forceLoadAllowed();
            boolean swHovered = hovered && mouseX >= swX && mouseX <= swX + SWITCH_W;
            int swColor = entry.forceLoad ? 0xFF4CAF50 : 0xFF404040;
            graphics.fill(swX, y + 6, swX + SWITCH_W, y + 18, allowed ? swColor : 0xFF303030);
            // Draw a label, not just ON/OFF: a bare coloured box tells the player nothing.
            Component label = entry.forceLoad
                    ? Component.translatable("gui.maid_legion.load.on")
                    : Component.translatable("gui.maid_legion.load.off");
            graphics.drawCenteredString(this.font, label, swX + SWITCH_W / 2, y + 9,
                    allowed ? 0xFFFFFFFF : 0xFF909090);

            if (swHovered) {
                List<Component> tip = new ArrayList<>();
                tip.add(Component.translatable("gui.maid_legion.load.tooltip.title")
                        .withStyle(ChatFormatting.BOLD));
                tip.add(Component.translatable("gui.maid_legion.load.tooltip.what"));
                tip.add(Component.translatable("gui.maid_legion.load.tooltip.what2"));
                tip.add(Component.translatable("gui.maid_legion.load.tooltip.state",
                        Component.translatable(entry.forceLoad
                                ? "gui.maid_legion.load.on"
                                : "gui.maid_legion.load.off")));
                if (allowed) {
                    tip.add(Component.translatable("gui.maid_legion.load.tooltip.click"));
                } else {
                    tip.add(Component.translatable("gui.maid_legion.load.tooltip.disabled")
                            .withStyle(ChatFormatting.RED));
                }
                // Deferred: drawn last so no widget can paint over it. See render().
                this.pendingTooltip = tip;
                this.pendingTooltipX = mouseX;
                this.pendingTooltipY = mouseY;
            }
        } else if (row.switchNotApplicable()) {
            // A dim dash marks the cell as deliberately empty, not broken.
            graphics.drawCenteredString(this.font, Component.literal("-"),
                    swX + SWITCH_W / 2, y + 9, 0xFF606060);
            if (hovered && mouseX >= swX && mouseX <= swX + SWITCH_W) {
                List<Component> tip = new ArrayList<>();
                tip.add(Component.translatable("gui.maid_legion.load.tooltip.title")
                        .withStyle(ChatFormatting.BOLD));
                tip.add(Component.translatable(entry.state == MaidState.DEAD
                        ? "gui.maid_legion.load.tooltip.dead"
                        : "gui.maid_legion.load.tooltip.stored"));
                this.pendingTooltip = tip;
                this.pendingTooltipX = mouseX;
                this.pendingTooltipY = mouseY;
            }
        }
    }

    /**
     * Draws the column headers once above the list, so the badges and the switch are
     * self-explanatory even before the player hovers anything.
     */
    private void renderHeader(GuiGraphics graphics) {
        int headerY = COLUMN_HEADER_Y;
        int badgeX = listRight - BADGE_W - BADGE_PAD;
        int swX = badgeX - SWITCH_W - 4;
        // The HP text is right-aligned against the bar, so anchor its header to the bar's
        // left edge and let it run backwards, matching how the rows look.
        int barX = listRight - BADGE_W - BAR_W - HP_GAP;

        graphics.drawString(this.font, Component.translatable("gui.maid_legion.col.maid"),
                listLeft + 2 + STAR_W + 18, headerY, 0xFFB0B0B0, false);
        Component hpHeader = Component.translatable("gui.maid_legion.col.health");
        graphics.drawString(this.font, hpHeader, barX + BAR_W / 2 - this.font.width(hpHeader) / 2,
                headerY, 0xFFB0B0B0, false);
        graphics.drawCenteredString(this.font, Component.translatable("gui.maid_legion.col.load"),
                swX + SWITCH_W / 2, headerY, 0xFFB0B0B0);
        graphics.drawCenteredString(this.font, Component.translatable("gui.maid_legion.col.state"),
                badgeX + BADGE_W / 2, headerY, 0xFFB0B0B0);

        // A hairline under the headers to separate them from the first row.
        graphics.fill(listLeft, headerY + 10, listRight, headerY + 11, 0x40FFFFFF);
    }

    /**
     * The x coordinate the maid's name may grow to before it would collide with something.
     *
     * <p>Computed from the real layout rather than a fixed number, so the name uses whatever
     * room the current window gives it. The limit is the left edge of the health readout for
     * loaded maids, and the left edge of the force-load switch otherwise.
     */
    private int nameRightLimit(MaidEntry entry) {
        int barX = listRight - BADGE_W - BAR_W - HP_GAP;
        if (entry.state == MaidState.PRESENT) {
            // Reserve the widest plausible readout so the limit does not jitter per row.
            return barX - this.font.width("999/999") - 3 - 4;
        }
        // Maids without a health row can run right up to the switch, or the badge when the
        // switch is not shown at all.
        int switchLeft = listRight - BADGE_W - BADGE_PAD - SWITCH_W - 4;
        return entry.state == MaidState.STORED ? listRight - BADGE_W - BADGE_PAD - 4 : switchLeft - 4;
    }

    private Component subtitle(MaidEntry entry) {
        // Health deliberately lives next to the bar now, so the subtitle only carries
        // location information - plus, for stored maids, when she was recalled and a short
        // id, which is the only way to tell two identically named maids apart.
        return switch (entry.state) {
            case PRESENT -> Component.literal(shortDim(entry.dimension) + " "
                    + entry.pos.getX() + "," + entry.pos.getY() + "," + entry.pos.getZ());
            case STORED -> Component.translatable("gui.maid_legion.sub.stored_at",
                    ago(entry.storedAt), entry.shortId());
            case UNLOADED -> Component.literal(shortDim(entry.dimension) + " "
                    + entry.pos.getX() + "," + entry.pos.getZ()
                    + (entry.sameDimension ? "" : " (other dim)"));
            // Death count is the number the player actually needs here: it drives the revive
            // delay, and it is the only field that distinguishes one death from the next.
            case DEAD -> Component.translatable("gui.maid_legion.sub.dead",
                    ago(entry.storedAt), entry.deathCount, entry.shortId());
        };
    }

    /** Renders a stored timestamp as a rough "how long ago" in the player's language. */
    private static String ago(long storedAt) {
        if (storedAt <= 0L) {
            return "?";
        }
        long seconds = Math.max(0L, (System.currentTimeMillis() - storedAt) / 1000L);
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h";
        }
        return (hours / 24) + "d";
    }

    private static String shortDim(String dim) {
        int idx = dim.indexOf(':');
        return idx >= 0 ? dim.substring(idx + 1) : dim;
    }

    private String trim(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        return this.font.plainSubstrByWidth(text, maxWidth - 6) + "...";
    }

    private void renderScrollbar(GuiGraphics graphics) {
        int contentHeight = rows.size() * ROW_HEIGHT;
        int viewHeight = listBottom - listTop;
        if (contentHeight <= viewHeight) {
            return;
        }
        int barX = listRight - 3;
        int barH = Math.max(16, viewHeight * viewHeight / contentHeight);
        int barY = listTop + (int) ((viewHeight - barH) * (scroll / (contentHeight - viewHeight)));
        graphics.fill(barX, listTop, barX + 3, listBottom, 0x40000000);
        graphics.fill(barX, barY, barX + 3, barY + barH, 0xFFA0A0A0);
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0 || mouseY < listTop || mouseY > listBottom
                || mouseX < listLeft || mouseX > listRight) {
            return false;
        }
        int index = (int) ((mouseY - listTop + scroll) / ROW_HEIGHT);
        if (index < 0 || index >= rows.size()) {
            return false;
        }
        Row row = rows.get(index);
        int badgeX = listRight - BADGE_W - BADGE_PAD;
        int swX = badgeX - SWITCH_W - 4;

        // Favourite star.
        int starX = listLeft + 2;
        if (mouseX >= starX && mouseX < starX + STAR_W) {
            toggleFavourite(row.entry);
            return true;
        }
        // Force-load switch hitbox - shares SWITCH_W with the renderer so they cannot drift.
        if (row.switchVisible() && mouseX >= swX && mouseX <= swX + SWITCH_W) {
            toggleForceLoad(row.entry);
            return true;
        }
        // Revive badge hitbox - the same rectangle the renderer paints, so hovering it and
        // clicking it agree. Checked before the generic tick toggle because for a dead maid
        // the badge is the action; the tick box means nothing for her.
        if (row.entry.revivable() && mouseX >= badgeX && mouseX <= badgeX + BADGE_W) {
            reviveOne(row.entry);
            return true;
        }
        if (row.selectable()) {
            ClientSelection.toggle(row.entry.id);
            return true;
        }
        return false;
    }

    private void toggleFavourite(MaidEntry entry) {
        NetworkHandler.CHANNEL.sendToServer(
                new com.dsh.maidmanager.network.C2SToggleFavouritePacket(
                        List.of(entry.id), !entry.favourite));
    }

    private void toggleForceLoad(MaidEntry entry) {
        if (!ClientPayloadHandlers.forceLoadAllowed()) {
            return;
        }
        // First time the player flips this on, show the explanation before applying.
        if (!entry.forceLoad && !entry.acknowledged) {
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new HeavyLoadWarningScreen(this, () -> {
                        NetworkHandler.CHANNEL.sendToServer(new com.dsh.maidmanager.network.C2SToggleLoadPacket(
                                List.of(entry.id), true, true));
                    }));
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(new com.dsh.maidmanager.network.C2SToggleLoadPacket(
                List.of(entry.id), !entry.forceLoad, true));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseY >= listTop && mouseY <= listBottom) {
            scroll -= delta * ROW_HEIGHT;
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Ctrl+A selects everything visible.
        if (keyCode == GLFW.GLFW_KEY_A && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            selectAll(true);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_SPACE) {
            List<UUID> ids = selectedIds();
            // Space with nothing selected toggles the hovered row; keep it simple and select all.
            if (ids.isEmpty()) {
                selectAll(true);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** One rendered line of the list. */
    private static final class Row {
        final MaidEntry entry;

        Row(MaidEntry entry) {
            this.entry = entry;
        }

        /**
         * Whether this row can be ticked in the list.
         *
         * <p>The tick box feeds the summon and store batches, so only rows those can act on
         * are tickable. A fallen maid is deliberately excluded: reviving her is her badge's
         * job, not a batch, and a tick box that did nothing would just be a dead control.
         */
        boolean selectable() {
            return entry.summonable() || entry.storeable();
        }

        /**
         * Shown for maids that can actually be kept loaded: one standing in the world
         * (so her chunk is held from now on) or one TLM has as unloaded (so enabling it
         * brings her back).
         *
         * <p>Two states are excluded, because in both the switch would be a lie. A maid stored
         * in our own NBT has no entity to hold a chunk. A fallen maid has no entity either, and
         * although her force-load flag is remembered and applies again once she is revived, a
         * control that cannot do anything right now should not look clickable.
         */
        boolean switchVisible() {
            return entry.state != MaidState.STORED && entry.state != MaidState.DEAD;
        }

        /** Those maids show why the switch is absent instead of leaving the cell blank. */
        boolean switchNotApplicable() {
            return !switchVisible();
        }
    }
}
