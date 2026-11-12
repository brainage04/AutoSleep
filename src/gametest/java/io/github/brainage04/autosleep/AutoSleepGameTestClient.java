package io.github.brainage04.autosleep;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class AutoSleepGameTestClient implements ClientModInitializer {
    private boolean clearedChat;

    @Override
    public void onInitializeClient() {
        // The recording shows the mod working with Baritone's own overlay and chat: the goal box
        // and path line in the
        // world, and the path Baritone reports in chat. Baritone's defaults draw the first and keep
        // the second quiet.
        Settings settings = BaritoneAPI.getSettings();
        settings.chatDebug.value = true;

        ClientGameTestRecordingHud.register();
        DayNightCycleHud.register();
        ClientTickEvents.END_CLIENT_TICK.register(
                client -> {
                    client.getToastManager().clear();
                    if (!clearedChat && client.player != null) {
                        // Baritone warns about its settings file while it loads, before there is a
                        // world; the recording starts
                        // from a clean chat and then keeps everything Baritone and the server say.
                        client.gui.getChat().clearMessages(false);
                        clearedChat = true;
                    }
                });
    }
}
