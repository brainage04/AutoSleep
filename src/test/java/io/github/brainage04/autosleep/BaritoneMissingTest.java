package io.github.brainage04.autosleep;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import net.fabricmc.api.ClientModInitializer;
import org.junit.jupiter.api.Test;

/**
 * Baritone's standalone jar replaces the API jar AutoSleep bundles and has no {@code baritone.api}
 * classes the mod uses. This loads AutoSleep's own classes afresh in a class loader that hides
 * {@code baritone.api}, as that jar does.
 */
class BaritoneMissingTest {
    private static final String MOD_PACKAGE = "io.github.brainage04.autosleep.";

    @Test
    void startsWithoutCrashingWhenTheBaritoneApiIsMissing() throws ReflectiveOperationException {
        ClassLoader withoutApi = new WithoutBaritoneApi(getClass().getClassLoader());
        ClientModInitializer client =
                (ClientModInitializer)
                        withoutApi
                                .loadClass(MOD_PACKAGE + "AutoSleepClient")
                                .getDeclaredConstructor()
                                .newInstance();

        assertDoesNotThrow(client::onInitializeClient);
    }

    @Test
    void seesWhetherTheBaritoneApiIsThere() {
        assertTrue(AutoSleepClient.hasBaritoneApi(getClass().getClassLoader()));
        assertFalse(
                AutoSleepClient.hasBaritoneApi(
                        new WithoutBaritoneApi(getClass().getClassLoader())));
    }

    /**
     * Defines AutoSleep's classes itself, so they resolve Baritone through it, and hides its API.
     */
    private static final class WithoutBaritoneApi extends ClassLoader {
        WithoutBaritoneApi(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("baritone.api.")) {
                throw new ClassNotFoundException(name);
            }
            if (!name.startsWith(MOD_PACKAGE)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = defineModClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private Class<?> defineModClass(String name) throws ClassNotFoundException {
            try (InputStream in =
                    getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
}
