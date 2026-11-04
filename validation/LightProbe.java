package ua.zentix.stand;

import java.lang.reflect.*;
import java.util.*;

/** Read-only dedicated compatibility observation; called on the client thread, never a frame-time gate. */
public final class LightProbe {
    private LightProbe() {}
    private record Pool(Iterable<?> values, boolean fast) {}
    public static String read() {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcClass.getMethod("getInstance").invoke(null);
            Object engine = mcClass.getField("particleEngine").get(mc);
            Field queuesField = engine.getClass().getDeclaredField("particles");
            queuesField.setAccessible(true);
            List<Pool> queues = new ArrayList<>();
            addQueues(queues, queuesField.get(engine), false);
            Class<?> gpu = null;
            try {
                Class<?> behavior = Class.forName("neoforge.fun.qu_an.minecraft.asyncparticles.client.core.particle.gpu_acceleration.GpuParticleBehavior");
                Object instance = behavior.getMethod("getInstance").invoke(null);
                addQueues(queues, behavior.getField("gpuParticles").get(instance), true);
                gpu = Class.forName("neoforge.fun.qu_an.minecraft.asyncparticles.client.addon.GpuParticleAddon");
            } catch (ClassNotFoundException ignored) { }
            Class<?> particle = Class.forName("net.minecraft.client.particle.Particle");
            Field x = field(particle, "x"), y = field(particle, "y"), z = field(particle, "z");
            Method light = particle.getDeclaredMethod("getLightColor", float.class);
            light.setAccessible(true);
            Method gpuLight = gpu == null ? null : gpu.getMethod("asyncparticles$getGpuLightCoords", float.class);
            Method tracked = null;
            try {
                tracked = Class.forName("dev.ryanhcode.sable.mixinterface.particle.ParticleExtension").getMethod("sable$getTrackingSubLevel");
            } catch (ReflectiveOperationException ignored) { }
            Map<String, Integer> observations = new TreeMap<>();
            int count = 0;
            for (Pool queue : queues) for (Object p : queue.values()) {
                if (!p.getClass().getName().equals("net.minecraft.client.particle.TerrainParticle")) continue;
                double px = x.getDouble(p), py = y.getDouble(p), pz = z.getDouble(p);
                if (py < 65 || py > 96 || pz < 3 || pz > 9) continue;
                String place = px > 1 && px < 11 ? "inside" : px > 18 && px < 23 ? "outside" : "other";
                if (place.equals("other")) continue;
                boolean fast = queue.fast() && gpu != null && gpu.isInstance(p);
                int value = (Integer) (fast ? gpuLight.invoke(p, 0f) : light.invoke(p, 0f));
                boolean following = tracked != null && tracked.invoke(p) != null;
                String key = place + ":gpu=" + fast + ":tracked=" + following + ":sky=" + ((value >>> 20) & 15) + ":block=" + ((value >>> 4) & 15);
                observations.merge(key, 1, Integer::sum);
                count++;
            }
            return "observed=" + count + " " + observations;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "unavailable " + e;
        }
    }
    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
    private static void addQueues(List<Pool> into, Object groups, boolean fast) {
        if (groups instanceof Map<?, ?> map) for (Object queue : map.values())
            if (queue instanceof Iterable<?> values) into.add(new Pool(values, fast));
    }
}
