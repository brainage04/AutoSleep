package io.github.brainage04.autosleep;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class AutoSleepGameTestClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // The recording shows the bed, not Baritone's goal box, path line and selection boxes.
        Settings settings = BaritoneAPI.getSettings();
        settings.renderPath.value = false;
        settings.renderGoal.value = false;
        settings.renderGoalXZBeacon.value = false;
        settings.renderSelectionBoxes.value = false;

        ClientGameTestRecordingHud.register();
        DayNightCycleHud.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> client.getToastManager().clear());
    }
}
