package ua.zentix.incoming.util;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Client particles with command semantics {@code particle <type> x y z dx dy dz speed count force}: count = 0 means one
 * particle with velocity (dx, dy, dz)·speed; otherwise count particles with Gaussian spread (dx, dy, dz) and random
 * velocity·speed. “force” means visible from afar (beyond 32 blocks). Thus a command-tuned {@code particle} effect
 * transfers without retuning. Does nothing on the server.
 */
public final class Particles {
    private Particles() {}

    public static void burst(Level level, ParticleOptions type, double x, double y, double z,
                             double dx, double dy, double dz, double speed, int count) {
        if (!level.isClientSide) return;
        RandomSource r = level.random;
        if (count == 0) {
            level.addAlwaysVisibleParticle(type, true, x, y, z, dx * speed, dy * speed, dz * speed);
            return;
        }
        for (int i = 0; i < count; i++) {
            double ox = r.nextGaussian() * dx, oy = r.nextGaussian() * dy, oz = r.nextGaussian() * dz;
            double vx = r.nextGaussian() * speed, vy = r.nextGaussian() * speed, vz = r.nextGaussian() * speed;
            level.addAlwaysVisibleParticle(type, true, x + ox, y + oy, z + oz, vx, vy, vz);
        }
    }

    public static void burst(Level level, ParticleOptions type, Vec3 p, double dx, double dy, double dz, double speed, int count) {
        burst(level, type, p.x, p.y, p.z, dx, dy, dz, speed, count);
    }
}
