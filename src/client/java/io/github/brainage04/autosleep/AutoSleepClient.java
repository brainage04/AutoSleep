package io.github.brainage04.autosleep;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class AutoSleepClient implements ClientModInitializer {
    private static volatile boolean initialized;

    @Override
    public void onInitializeClient() {
        // Walk to the bed only: never dig through or build on someone's base on the way.
        Settings settings = BaritoneAPI.getSettings();
        settings.allowBreak.value = false;
        settings.allowPlace.value = false;

        AutoSleeper autoSleeper = new AutoSleeper();
        ClientTickEvents.END_CLIENT_TICK.register(autoSleeper::tick);
        initialized = true;

        AutoSleep.LOGGER.info("{} client initialised.", AutoSleep.MOD_NAME);
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
