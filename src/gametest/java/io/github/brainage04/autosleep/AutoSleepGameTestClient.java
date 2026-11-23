package io.github.brainage04.autosleep;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class AutoSleepGameTestClient implements ClientModInitializer {
    private boolean clearedChat;

    @Override
    public void onInitializeClient() {
        // The recording shows Baritone's own overlay and chat: its defaults draw the goal box and path line
        // (Settings.java:695,705) and keep the path it reports in chat quiet (Settings.java:679).
        Settings settings = BaritoneAPI.getSettings();
        settings.chatDebug.value = true;

        ClientGameTestRecordingHud.register();
        DayNightCycleHud.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            client.getToastManager().clear();
            if (!clearedChat && client.player != null) {
                // Baritone warns about its settings file while it loads, before there is a world; the recording starts
                // from a clean chat and then keeps everything Baritone and the server say.
                client.gui.getChat().clearMessages(false);
                clearedChat = true;
            }
        });
    }
}
