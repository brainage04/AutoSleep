package io.github.brainage04.autosleep;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

import java.util.List;

/** Top-left panel for client GameTest recordings: the day time out of 24000, the mod's night window, and day or night. */
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

    private DayNightCycleHud() {
    }

    static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.SCOREBOARD, HUD_ID, DayNightCycleHud::render);
    }

    /** Where this panel ends on the right at its widest, so the step panel can keep clear of it. */
    static int rightEdge(Font font) {
        return SCREEN_MARGIN + PANEL_PADDING * 2 + font.width("Day time: 23999 / " + AutoSleeper.DAY_LENGTH_TICKS);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        long dayTime = Math.floorMod(client.level.getDayTime(), AutoSleeper.DAY_LENGTH_TICKS);
        boolean night = AutoSleeper.isNight(client.level);
        List<Line> lines = List.of(
                new Line("Day time: " + dayTime + " / " + AutoSleeper.DAY_LENGTH_TICKS, TEXT_COLOR),
                new Line("Night starts: " + AutoSleeper.NIGHT_START, LABEL_COLOR),
                new Line("Night ends: " + AutoSleeper.NIGHT_END, LABEL_COLOR),
                new Line(night ? "Night" : "Day", night ? NIGHT_COLOR : DAY_COLOR));

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

    private record Line(String text, int color) {
    }
}
