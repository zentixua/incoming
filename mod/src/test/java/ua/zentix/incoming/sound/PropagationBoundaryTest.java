package ua.zentix.incoming.sound;

import org.junit.jupiter.api.Test;

import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PropagationBoundaryTest {
    @Test
    void weatherAndPropagationRunWithoutMinecraftOrWorldAir() throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{Propagation.class.getProtectionDomain().getCodeSource().getLocation()},
                ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.minecraft.") || name.equals("ua.zentix.incoming.util.Air")) {
                    throw new ClassNotFoundException("World dependency in sound: " + name);
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> weather = loader.loadClass("ua.zentix.incoming.sound.Propagation$Weather");
            Object noon = weather.getField("NOON").get(null);
            assertEquals(0.7, weather.getMethod("humidity").invoke(noon));
            Class<?> propagation = loader.loadClass("ua.zentix.incoming.sound.Propagation");
            assertEquals(Propagation.background(Propagation.Weather.NOON), propagation.getMethod("background", weather).invoke(null, noon));
            assertEquals(Propagation.refraction(3.5), propagation.getMethod("refraction", double.class).invoke(null, 3.5));
            assertEquals(Propagation.alpha(6, 0.7), propagation.getMethod("alpha", int.class, double.class).invoke(null, 6, 0.7));
        }
    }
}
