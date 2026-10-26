package ua.zentix.incoming.gametest.scenario;

import net.minecraft.world.phys.Vec3;

import java.util.HexFormat;

/**
 * Flight hash by tick (FNV-1a, 64 bit): tick from launch, which projectile of the scenario (in order of appearance), phase, out of world
 * or is it, location from the middle of the site accurate to 0,001 block. Any shift in trajectory, phase, or departure from the world
 *  changes the hash; the standard stores it instead of thousands of points.
 */
final class FlightTrace {
    private static final long OFFSET = 0xcbf29ce484222325L, PRIME = 0x100000001b3L;
    private long hash = OFFSET;

    void add(int tick, int order, int phase, boolean virtual, Vec3 rel) {
        mix(tick);
        mix(order);
        mix(phase);
        mix(virtual ? 1 : 0);
        mix(Math.round(rel.x * 1000));
        mix(Math.round(rel.y * 1000));
        mix(Math.round(rel.z * 1000));
    }

    private void mix(long v) {
        for (int i = 0; i < 8; i++) {
            hash ^= (v >>> (i * 8)) & 0xff;
            hash *= PRIME;
        }
    }

    String hex() {
        return HexFormat.of().toHexDigits(hash);
    }
}
