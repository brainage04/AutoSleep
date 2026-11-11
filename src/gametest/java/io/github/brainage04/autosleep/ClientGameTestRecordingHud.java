package io.github.brainage04.autosleep;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The recording panel of MinecraftModConventions' {@code ClientGameTestRecordingHud}, drawn with the 1.21.11 GUI API
 * (the conventions runtime itself needs Minecraft 26.2): the current step's id, title and expected result, and the last
 * few things the test saw, in the top-right corner clear of chat.
 */
public final class ClientGameTestRecordingHud {
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(AutoSleep.MOD_ID, "gametest_recording_feedback");
    private static final int SCREEN_MARGIN = 5;
    private static final int PANEL_PADDING = 4;
    private static final int LINE_GAP = 2;
    private static final int MAX_WIDTH = 360;
    private static final int MAX_LOG_LINES = 6;
    private static final int BACKGROUND_COLOR = 0xA0000000;
    private static final int HEADER_COLOR = 0xFF55FFFF;
    private static final int LABEL_COLOR = 0xFFAAAAAA;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int SUBTITLE_COLOR = 0xFF90EE90;
    private static final int LOG_COLOR = 0xFFFFD54F;
    private static final Object LOG_LOCK = new Object();
    private static final ArrayDeque<String> LOG_LINES = new ArrayDeque<>(MAX_LOG_LINES);
    private static volatile Step currentStep;
    private static int stepNumber;

    private ClientGameTestRecordingHud() {
    }

    static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.SCOREBOARD, HUD_ID, ClientGameTestRecordingHud::render);
    }

    static void step(String id, String title, String expected) {
        stepNumber++;
        String numberedId = stepNumber + ". " + id;
        System.out.println("[CLIENT_GAMETEST_RECORDER] " + System.currentTimeMillis() + " " + numberedId + " | " + title + " | " + expected);
        showStep(numberedId, title, expected);
    }

    static void showStep(String id, String title, String subtitle) {
        Step step = new Step(clean(id), clean(title), clean(subtitle));
        currentStep = step;
        addLogLine(step.id() + " - " + step.title());
    }

    static void log(String message) {
        String cleaned = clean(message);
        if (cleaned.isBlank()) {
            return;
        }
        // The epoch millis let the recording be trimmed against this log; the recorded capture starts at ffmpeg-start.
        System.out.println("[CLIENT_GAMETEST_RECORDER] " + System.currentTimeMillis() + " - " + cleaned);
        addLogLine(cleaned);
    }

    private static void addLogLine(String message) {
        synchronized (LOG_LOCK) {
            while (LOG_LINES.size() >= MAX_LOG_LINES) {
                LOG_LINES.removeFirst();
            }
            LOG_LINES.addLast(message);
        }
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }

        Font font = client.font;
        int screenWidth = client.getWindow().getGuiScaledWidth();
        int maxWidth = Math.min(MAX_WIDTH, screenWidth - DayNightCycleHud.rightEdge(client) - SCREEN_MARGIN * 2 - PANEL_PADDING * 2);
        // Long lines wrap instead of ending in an ellipsis: a caption that is cut off tells the viewer nothing.
        List<String> texts = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (HudLine line : lines()) {
            for (String piece : wrap(font, line.text(), maxWidth)) {
                texts.add(piece);
                colors.add(line.color());
            }
        }

        int width = 0;
        for (String text : texts) {
            width = Math.max(width, font.width(text));
        }
        int lineHeight = font.lineHeight + LINE_GAP;
        int panelWidth = width + PANEL_PADDING * 2;
        int panelHeight = texts.size() * lineHeight + PANEL_PADDING * 2 - LINE_GAP;
        int panelX = screenWidth - panelWidth - SCREEN_MARGIN;
        int panelY = SCREEN_MARGIN;
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, BACKGROUND_COLOR);

        int textY = panelY + PANEL_PADDING;
        for (int index = 0; index < texts.size(); index++) {
            graphics.drawString(font, texts.get(index), panelX + PANEL_PADDING, textY, colors.get(index), true);
            textY += lineHeight;
        }
    }

    private static List<HudLine> lines() {
        ArrayList<HudLine> lines = new ArrayList<>();
        lines.add(new HudLine("GameTest Recording", HEADER_COLOR));

        Step step = currentStep;
        if (step != null) {
            lines.add(new HudLine("Scenario: " + step.id(), LABEL_COLOR));
            lines.add(new HudLine(step.title(), TITLE_COLOR));
            if (!step.subtitle().isBlank()) {
                lines.add(new HudLine(step.subtitle(), SUBTITLE_COLOR));
            }
        }

        synchronized (LOG_LOCK) {
            for (String log : LOG_LINES) {
                lines.add(new HudLine("› " + log, LOG_COLOR));
            }
        }
        return lines;
    }

    private static List<String> wrap(Font font, String text, int maxWidth) {
        List<String> wrapped = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (current.length() > 0 && font.width(candidate) > maxWidth) {
                wrapped.add(current.toString());
                current.setLength(0);
            }
            current.append(current.length() == 0 ? word : " " + word);
        }
        if (current.length() > 0) {
            wrapped.add(current.toString());
        }
        if (wrapped.isEmpty()) {
            wrapped.add("");
        }
        wrapped.replaceAll(line -> fit(font, line, maxWidth));
        return wrapped;
    }

    private static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }

        String suffix = "…";
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return end == 0 ? suffix : text.substring(0, end).stripTrailing() + suffix;
    }

    static String clean(String value) {
        return value == null ? "" : value.strip().replace('\n', ' ');
    }

    private record Step(String id, String title, String subtitle) {
    }

    private record HudLine(String text, int color) {
    }
}
