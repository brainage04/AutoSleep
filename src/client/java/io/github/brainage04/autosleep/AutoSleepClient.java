package io.github.brainage04.autosleep;

import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.network.chat.Component;

public class AutoSleepClient implements ClientModInitializer {
    /**
     * Baritone's standalone jar has the same mod id and version as the API jar AutoSleep bundles,
     * and Fabric Loader ranks a jar in the mods folder above a nested one, whatever the version
     * (ModPrioSorter.java:152-160 in Fabric Loader 0.19.2, "root mods first"), so the standalone
     * jar replaces the bundled one. It obfuscates the API: its v1.17.0 release asset has no {@code
     * baritone/api/BaritoneAPI.class}.
     */
    static final String BARITONE_API_CLASS = "baritone.api.BaritoneAPI";

    static final String DISABLED_MESSAGE = "autosleep.message.disabled";

    @Override
    public void onInitializeClient() {
        if (hasBaritoneApi(AutoSleepClient.class.getClassLoader())) {
            try {
                AutoSleeper autoSleeper = new AutoSleeper();
                ClientTickEvents.END_CLIENT_TICK.register(autoSleeper::tick);
                AutoSleep.LOGGER.info("{} client initialised.", AutoSleep.MOD_NAME);
                return;
            } catch (LinkageError e) {
                AutoSleep.LOGGER.error("Baritone's API failed to start.", e);
            }
        }
        disable();
    }

    static boolean hasBaritoneApi(ClassLoader loader) {
        try {
            Class.forName(BARITONE_API_CLASS, false, loader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Leaves the game alone and tells the player why, once, when they first join a world. */
    private static void disable() {
        AutoSleep.LOGGER.error(
                "{} is off: it needs Baritone's API ({}) from the baritone-api-fabric jar it bundles,"
                        + " and another Baritone jar in the mods folder, such as"
                        + " baritone-standalone-fabric, replaced it. Remove that jar.",
                AutoSleep.MOD_NAME,
                BARITONE_API_CLASS);
        AtomicBoolean told = new AtomicBoolean();
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> {
                    if (!told.getAndSet(true)) {
                        client.gui.getChat().addMessage(Component.translatable(DISABLED_MESSAGE));
                    }
                });
    }
}
