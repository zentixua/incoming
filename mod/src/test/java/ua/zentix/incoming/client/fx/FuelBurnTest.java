package ua.zentix.incoming.client.fx;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.model.FuelPool;
import ua.zentix.incoming.strike.WeaponType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The source of the crater for the client follows the state of the server (review FV): glass - the source is where the fuel is now burning; If some of the cells are covered or
 *  is flooded, a smaller area is burning; It’s all over - the hearth goes out before the deadline of the law.
 */
class FuelBurnTest {
    private static final FuelPool DRONE = WeaponType.DRONE.spec().pool(5);
    private static final List<BlockPos> CRATER = List.of(new BlockPos(0, 63, 0), new BlockPos(1, 63, 0), new BlockPos(-1, 63, 0),
            new BlockPos(0, 63, 1), new BlockPos(0, 63, -1));

    @Test
    void wholePoolBurnsByTheLaw() {
        FuelBurn burn = new FuelBurn(1, DRONE, 5, CRATER, 0);
        for (double t : new double[]{0, 10, 60, 0.9 * DRONE.seconds(), DRONE.seconds()}) assertEquals(DRONE.share(t), burn.share(t), 1e-12, "t " + t);
        assertEquals(0.5, burn.centre().x, 1e-9);
        assertEquals(63, burn.centre().y, 1e-9);
    }

    @Test
    void driftMovesTheSourceToWhereTheFuelBurns() {
        FuelBurn burn = new FuelBurn(1, DRONE, 5, CRATER, 0);
        // the flooring under the cages burned out - the fuel glass is 5 blocks below
        burn.update(CRATER.stream().map(p -> p.below(5)).toList(), 0);
        assertEquals(58, burn.centre().y, 1e-9);
        assertEquals(DRONE.share(60), burn.share(60), 1e-12, "glass - same heat");
    }

    @Test
    void smotheredCellsTakeTheirShareAndAllSmotheredEnds() {
        FuelBurn burn = new FuelBurn(1, DRONE, 5, CRATER, 0);
        // two cells are covered: three out of five are burning, the place is in the middle of the burning ones
        burn.update(CRATER, 0b00110L);
        assertEquals(0.6 * DRONE.share(30), burn.share(30), 1e-12);
        assertTrue(burn.live().size() == 3 && !burn.live().contains(CRATER.get(1)) && !burn.live().contains(CRATER.get(2)));
        assertEquals(0.5, burn.centre().x, 1e-9);
        assertEquals(0.5, burn.centre().z, 1e-9);
        // everything was flooded - there was no heat long before the deadline
        burn.update(CRATER, 0b11111L);
        assertEquals(0, burn.share(30));
        assertTrue(burn.live().isEmpty());
        assertFalse(DRONE.share(30) == 0);
    }

    /** A filled cell from those that, according to the law, has already gone out, does not carry away heat: the share is among those who are still burning. */
    @Test
    void smotheringACellThatIsOutByTheLawChangesNothing() {
        FuelBurn burn = new FuelBurn(1, DRONE, 5, CRATER, 0);
        double t = DRONE.cellSeconds(4, 5) + 1;
        burn.update(CRATER, 0b10000L);
        assertEquals(DRONE.share(t), burn.share(t), 1e-12);
    }

    /**
     * The update from the server reaches the client's hearth (review FV, circle 2): the crater packet, having passed through its format on the wire,
     *  starts the hearth ({@code BlastEffects.fuelFire} → {@link FireSmoke#fuel}); the next package of the same crater - fuel glass
     *  on 5 blocks below, two cells fell asleep - does not start a new fireplace, but transfers the old one to where the fuel is burning now:
     *  its {@link FuelBurn} (there is a hum and warmth along it), fuel fire cells ({@link FireSmoke#FUEL}) and the place of the fireplace - from where
     *  the flame shines on the smoke and the trail goes; burning area - three cells out of five.
     */
    @Test
    void serverUpdateMovesTheClientSource() {
        int drone = WeaponType.DRONE.id();
        S2C.FuelFire first = wire(new S2C.FuelFire(7, CRATER, 0, drone, 0));
        S2C.FuelFire later = wire(new S2C.FuelFire(7, CRATER.stream().map(p -> p.below(5)).toList(), 0b00110L, drone, 0));
        FireSmoke.reset();
        try {
            FuelBurn burn = FireSmoke.fuel(null, first.id(), law(first), first.cells(), first.smothered(), 10);
            assertNotNull(burn, "first package crater - new hearth");
            near(new Vec3(0.5, 63, 0.5), source(), "focus - not above the cells");
            assertTrue(FireSmoke.FUEL.covers(0, 63, 0));
            assertNull(FireSmoke.fuel(null, later.id(), law(later), later.cells(), later.smothered(), 11), "renewal is not a second source");
            assertEquals(58, burn.centre().y, 1e-9, "hum and warmth - not where the fuel burns now");
            near(new Vec3(0.5, 58, 0.5), source(), "the client's hearth remained in the same place");
            assertTrue(FireSmoke.FUEL.covers(0, 58, 0) && !FireSmoke.FUEL.covers(0, 63, 0), "fire fuel - not in today's cells");
            assertEquals(0.6 * DRONE.share(30), burn.share(30), 1e-12, "the buried cells are burning");
        } finally {
            FireSmoke.reset();
            SmokeGrid.reset();
        }
    }

    /** Law crater from the package - like the client ({@code BlastEffects.fuelFire}): weapon and how many cells were lit. */
    private static FuelPool law(S2C.FuelFire p) {
        return WeaponType.byId(p.weapon()).spec().pool(p.cells().size());
    }

    /** The package - through its format on the wire, as the client receives it. */
    private static S2C.FuelFire wire(S2C.FuelFire p) {
        ByteBuf buf = Unpooled.buffer();
        try {
            S2C.FuelFire.CODEC.encode(buf, p);
            S2C.FuelFire got = S2C.FuelFire.CODEC.decode(buf);
            assertEquals(p, got);
            return got;
        } finally {
            buf.release();
        }
    }

    /** Place - up to the thousandth block (hearth light - in float). */
    private static void near(Vec3 want, Vec3 got, String what) {
        assertTrue(want.distanceTo(got) < 1e-3, what + ": " + got + " instead of " + want);
    }

    /** The location of the client’s only hearth is where his flame shines onto the smoke ({@link FireSmoke#lights}: the ball above the hearth). */
    private static Vec3 source() {
        Matrix4f centres = new Matrix4f().zero(), light = new Matrix4f().zero();
        FireSmoke.lights(Vec3.ZERO, 1, 1, centres, light);
        Vector4f c = centres.getColumn(0, new Vector4f());
        assertTrue(c.w > 0, "no hearth flame");
        return new Vec3(c.x, c.y - c.w, c.z);
    }
}
