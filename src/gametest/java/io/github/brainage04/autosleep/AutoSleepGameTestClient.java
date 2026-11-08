package io.github.brainage04.autosleep;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class AutoSleepGameTestClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientGameTestRecordingHud.register();
        DayNightCycleHud.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> client.getToastManager().clear());
    }
}
