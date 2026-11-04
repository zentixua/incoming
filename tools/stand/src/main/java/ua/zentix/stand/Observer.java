package ua.zentix.stand;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** Passive frame profiling for an existing local world or remote server; no world or player actions. */
final class Observer {
    private final Out out;
    private final FrameClock clock;
    private long lastSecond;

    Observer(Out out) {
        this.out = out;
        this.clock = new FrameClock(out);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::stop);
        long now = System.nanoTime();
        out.event("observe-start", now, "wall_ms", System.currentTimeMillis(), "mono_ns", now);
        out.flush();
        Stand.LOG.info("STAND: passive frame observation in {}", out.dir);
    }

    private void tick(ClientTickEvent.Post event) {
        long now = System.nanoTime();
        if (now - lastSecond < 1_000_000_000L) return;
        lastSecond = now;
        Minecraft mc = Minecraft.getInstance();
        var level = mc.level;
        var camera = mc.gameRenderer.getMainCamera();
        var pos = camera.getPosition();
        out.event("observe", now, "wall_ms", System.currentTimeMillis(), "mono_ns", now, "frame", clock.frame(),
                "world", level != null, "dimension", level == null ? null : level.dimension().location(),
                "tick", level == null ? null : level.getGameTime(),
                "x", pos.x, "y", pos.y, "z", pos.z, "yaw", camera.getYRot(), "pitch", camera.getXRot(),
                "gpu_ring_waits", clock.gpuRingWaits, "repeated_stages", clock.repeatedStages);
        out.flush();
    }

    private void stop(GameShuttingDownEvent event) {
        long now = System.nanoTime();
        out.event("observe-stop", now, "wall_ms", System.currentTimeMillis(), "mono_ns", now,
                "gpu_ring_waits", clock.gpuRingWaits);
        out.close();
    }
}
