package io.github.brainage04.autosleep;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Sets up client GameTest runs for recording: the step panel (top right) and the day/night panel (top left), and no
 * toasts (advancements, recipes, "chat messages can't be verified") covering the picture.
 */
public final class AutoSleepGameTestClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientGameTestRecordingHud.register();
        DayNightCycleHud.register();
        // Cleared at the end of every tick, so a toast added while handling a packet is gone before the frame is drawn.
        ClientTickEvents.END_CLIENT_TICK.register(client -> client.getToastManager().clear());
    }
}
