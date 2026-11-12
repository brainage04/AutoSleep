package io.github.brainage04.autosleep;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModMetadata;
import org.junit.jupiter.api.Test;

class AutoSleepMetadataTest {
    @Test
    void fabricLoaderBootsInClientModeForTests() {
        assertEquals(EnvType.CLIENT, FabricLoader.getInstance().getEnvironmentType());
    }

    @Test
    void modIsDeclaredClientOnly() {
        ModContainer mod =
                FabricLoader.getInstance()
                        .getModContainer(AutoSleep.MOD_ID)
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "Expected AutoSleep to be loaded for tests."));
        ModMetadata metadata = mod.getMetadata();

        assertAll(
                () -> assertEquals(AutoSleep.MOD_ID, metadata.getId()),
                () -> assertEquals(AutoSleep.MOD_NAME, metadata.getName()),
                () -> assertEquals(ModEnvironment.CLIENT, metadata.getEnvironment()),
                () -> assertTrue(metadata.getLicense().contains("MIT")));
    }
}
