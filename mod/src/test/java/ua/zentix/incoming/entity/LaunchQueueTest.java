package ua.zentix.incoming.entity;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launch queue of the package ({@link LaunchQueue}) and the rotation of the package ({@link Launcher#turnedYaw}) are common to the trailer and the
 *  stationary launcher: the cells take turns, the interval between ignitions, the first launch is not before the package is lifted;
 *  only the silent package is turned and only at a noticeable angle.
 */
class LaunchQueueTest {
    /** Packet of rockets: 2 cells, interval 16 tick, ignition not earlier than 12 tick, cell occupied 24 tick. */
    private static final LauncherRack RACK = LauncherRack.MISSILE;

    @Test
    void slotsInTurnWithSpacing() {
        LaunchQueue q = new LaunchQueue();
        assertArrayEquals(new int[]{0, 12}, q.reserve(RACK, 100, 0, RACK.minReady(), RACK.busyTicks()));
        // the second cell is free, but ignition is not earlier than the interval after the first
        assertArrayEquals(new int[]{1, 28}, q.reserve(RACK, 100, 0, RACK.minReady(), RACK.busyTicks()));
        // both are busy: the first one will be free in 136, the interval after the second one is 144
        assertArrayEquals(new int[]{0, 44}, q.reserve(RACK, 100, 0, RACK.minReady(), RACK.busyTicks()));
    }

    @Test
    void firstLaunchWaitsForRaisedRack() {
        LaunchQueue q = new LaunchQueue();
        assertArrayEquals(new int[]{0, 100}, q.reserve(RACK, 100, 200, RACK.minReady(), RACK.busyTicks()));
    }

    @Test
    void silentAfterSettling() {
        LaunchQueue q = new LaunchQueue();
        assertTrue(q.silent(0), "the new queue is silent");
        int start = 100 + q.reserve(RACK, 100, 0, RACK.minReady(), RACK.busyTicks())[1];
        assertFalse(q.silent(100), "arson ahead");
        assertFalse(q.silent(start + LaunchQueue.SETTLE_TICKS - 1));
        assertTrue(q.silent(start + LaunchQueue.SETTLE_TICKS));
    }

    @Test
    void turnsOnlySilentAndNotByTrifles() {
        Fixed l = new Fixed();
        assertEquals(0, l.turnedYaw(Launcher.TURN_THRESHOLD - 1, 0), "no minor adjustments needed");
        assertEquals(40, l.turnedYaw(40, 0), "the silent one turns around");
        l.queue().reserve(RACK, 0, 0, RACK.minReady(), RACK.busyTicks());
        assertEquals(0, l.turnedYaw(40, 1), "is not silent - the course is the same");
        l.turnTo(40, 1);
        assertEquals(0, l.yaw());
        l.turnTo(40, 200);
        assertEquals(40, l.yaw());
        assertEquals(LauncherEntity.DEPLOY_TICKS, l.raisingTicks(200), "the screwed one rises again");
    }

    @Test
    void padRailsAboveBlock() {
        // the axis of the stationary launcher is above the middle of the block: the cells of the package are symmetrical to its course and above the top of the block
        Vec3 pos = new Vec3(0.5, 64, 0.5);
        for (int slot = 0; slot < LauncherRack.MISSILE.slots(); slot++) {
            Vec3 rail = LauncherMount.PAD.railPoint(pos, 0, WeaponType.MISSILE, slot);
            assertTrue(rail.y > 65, "cell " + slot + " below the top of the block: " + rail);
        }
        Vec3 a = LauncherMount.PAD.railPoint(pos, 0, WeaponType.MISSILE, 0), b = LauncherMount.PAD.railPoint(pos, 0, WeaponType.MISSILE, 1);
        assertEquals(pos.x, (a.x + b.x) / 2, 1e-9);
        assertEquals(a.z, b.z, 1e-9);
    }

    /** launcher without a world: course and queue. */
    private static final class Fixed implements Launcher {
        private final LaunchQueue queue = new LaunchQueue();
        private float yaw;
        private long deployedAt = Long.MIN_VALUE / 2;

        @Override
        public WeaponType weapon() {
            return WeaponType.MISSILE;
        }

        @Override
        public Vec3 position() {
            return Vec3.ZERO;
        }

        @Override
        public float yaw() {
            return yaw;
        }

        @Override
        public LauncherMount mount() {
            return LauncherMount.PAD;
        }

        @Override
        public long deployedAt() {
            return deployedAt;
        }

        @Override
        public LaunchQueue queue() {
            return queue;
        }

        @Override
        public void turnTo(float yaw, long now) {
            if (turnedYaw(yaw, now) == this.yaw) return;
            this.yaw = yaw;
            deployedAt = now;
        }
    }
}
