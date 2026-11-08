package io.github.brainage04.autosleep;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.SharedConstants;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Top-left panel for client GameTest recordings: the day time, the Overworld's night by the clock, the dimension, and
 * whether the client sees it as night (dark outside, which is what vanilla's default {@code when_dark} bed rule asks).
 */
public final class DayNightCycleHud {
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(AutoSleep.MOD_ID, "gametest_day_night_cycle");
    private static final int SCREEN_MARGIN = 5;
    private static final int PANEL_PADDING = 4;
    private static final int LINE_GAP = 2;
    private static final int BACKGROUND_COLOR = 0xA0000000;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int LABEL_COLOR = 0xFFAAAAAA;
    private static final int DAY_COLOR = 0xFFFFD54F;
    private static final int NIGHT_COLOR = 0xFF7FA7FF;
    /**
     * Readout only; the mod does not use them. The day times at which a clear Overworld sky is dark enough for vanilla
     * to let players sleep (the {@code sky_light_level} track of {@code timeline/day.json} in 1.21.11).
     */
    static final long NIGHT_START = 12523L;
    static final long NIGHT_END = 23478L;

    private DayNightCycleHud() {
    }

    static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.SCOREBOARD, HUD_ID, DayNightCycleHud::render);
    }

    /** Where this panel ends on the right, so the step panel can keep clear of it. */
    static int rightEdge(Minecraft client) {
        int width = 0;
        for (Line line : lines(client)) {
            width = Math.max(width, client.font.width(line.text()));
        }
        return SCREEN_MARGIN + PANEL_PADDING * 2 + width;
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        List<Line> lines = lines(client);

        Font font = client.font;
        int width = 0;
        for (Line line : lines) {
            width = Math.max(width, font.width(line.text()));
        }
        int lineHeight = font.lineHeight + LINE_GAP;
        int panelHeight = lines.size() * lineHeight + PANEL_PADDING * 2 - LINE_GAP;
        graphics.fill(SCREEN_MARGIN, SCREEN_MARGIN, SCREEN_MARGIN + width + PANEL_PADDING * 2, SCREEN_MARGIN + panelHeight, BACKGROUND_COLOR);

        int textY = SCREEN_MARGIN + PANEL_PADDING;
        for (Line line : lines) {
            graphics.drawString(font, line.text(), SCREEN_MARGIN + PANEL_PADDING, textY, line.color(), true);
            textY += lineHeight;
        }
    }

    private static List<Line> lines(Minecraft client) {
        if (client.level == null) {
            return List.of();
        }
        long dayTime = Math.floorMod(client.level.getDayTime(), SharedConstants.TICKS_PER_GAME_DAY);
        Line state;
        if (client.level.dimensionType().hasFixedTime()) {
            state = new Line("No day-night cycle", DAY_COLOR);
        } else if (client.level.isDarkOutside()) {
            state = new Line("Night", NIGHT_COLOR);
        } else {
            state = new Line("Day", DAY_COLOR);
        }
        return List.of(
                new Line("Day time: " + dayTime + " / " + SharedConstants.TICKS_PER_GAME_DAY, TEXT_COLOR),
                new Line("Night starts: " + NIGHT_START, LABEL_COLOR),
                new Line("Night ends: " + NIGHT_END, LABEL_COLOR),
                new Line("Dimension: " + client.level.dimension().identifier(), LABEL_COLOR),
                state);
    }

    private record Line(String text, int color) {
    }
}
