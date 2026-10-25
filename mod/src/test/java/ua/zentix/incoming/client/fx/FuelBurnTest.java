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
 * Очаг воронки у клиента идёт за состоянием сервера (ревью FV): стекло — очаг там, где топливо горит теперь; засыпало или
 * залило часть клеток — горит меньшая площадь; кончилось всё — очаг гаснет раньше срока закона.
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
        // настил под клетками сгорел — топливо стекло на 5 блоков ниже
        burn.update(CRATER.stream().map(p -> p.below(5)).toList(), 0);
        assertEquals(58, burn.centre().y, 1e-9);
        assertEquals(DRONE.share(60), burn.share(60), 1e-12, "стекло — тепло то же");
    }

    @Test
    void smotheredCellsTakeTheirShareAndAllSmotheredEnds() {
        FuelBurn burn = new FuelBurn(1, DRONE, 5, CRATER, 0);
        // две клетки засыпало: горят три из пяти, место — середина горящих
        burn.update(CRATER, 0b00110L);
        assertEquals(0.6 * DRONE.share(30), burn.share(30), 1e-12);
        assertTrue(burn.live().size() == 3 && !burn.live().contains(CRATER.get(1)) && !burn.live().contains(CRATER.get(2)));
        assertEquals(0.5, burn.centre().x, 1e-9);
        assertEquals(0.5, burn.centre().z, 1e-9);
        // залило всё — тепла нет задолго до срока закона
        burn.update(CRATER, 0b11111L);
        assertEquals(0, burn.share(30));
        assertTrue(burn.live().isEmpty());
        assertFalse(DRONE.share(30) == 0);
    }

    /** Засыпанная клетка из тех, что по закону уже погасли, тепла не уносит: доля — среди тех, кому ещё гореть. */
    @Test
    void smotheringACellThatIsOutByTheLawChangesNothing() {
        FuelBurn burn = new FuelBurn(1, DRONE, 5, CRATER, 0);
        double t = DRONE.cellSeconds(4, 5) + 1;
        burn.update(CRATER, 0b10000L);
        assertEquals(DRONE.share(t), burn.share(t), 1e-12);
    }

    /**
     * Обновление с сервера доходит до очага клиента (ревью FV, круг 2): пакет воронки, прошедший через свой формат на проводе,
     * заводит очаг ({@code BlastEffects.fuelFire} → {@link FireSmoke#fuel}); следующий пакет той же воронки — топливо стекло
     * на 5 блоков ниже, две клетки засыпало — нового очага не заводит, а переносит прежний туда, где топливо горит теперь:
     * его {@link FuelBurn} (по нему гул и тепло), клетки огня топлива ({@link FireSmoke#FUEL}) и место очага — откуда
     * светит пламя на дым и идёт шлейф; горящая площадь — три клетки из пяти.
     */
    @Test
    void serverUpdateMovesTheClientSource() {
        int drone = WeaponType.DRONE.id();
        S2C.FuelFire first = wire(new S2C.FuelFire(7, CRATER, 0, drone, 0));
        S2C.FuelFire later = wire(new S2C.FuelFire(7, CRATER.stream().map(p -> p.below(5)).toList(), 0b00110L, drone, 0));
        FireSmoke.reset();
        try {
            FuelBurn burn = FireSmoke.fuel(null, first.id(), law(first), first.cells(), first.smothered(), 10);
            assertNotNull(burn, "первый пакет воронки — новый очаг");
            near(new Vec3(0.5, 63, 0.5), source(), "очаг — не над клетками");
            assertTrue(FireSmoke.FUEL.covers(0, 63, 0));
            assertNull(FireSmoke.fuel(null, later.id(), law(later), later.cells(), later.smothered(), 11), "обновление — не второй очаг");
            assertEquals(58, burn.centre().y, 1e-9, "гул и тепло — не там, где топливо горит теперь");
            near(new Vec3(0.5, 58, 0.5), source(), "очаг клиента остался на прежнем месте");
            assertTrue(FireSmoke.FUEL.covers(0, 58, 0) && !FireSmoke.FUEL.covers(0, 63, 0), "огонь топлива — не в нынешних клетках");
            assertEquals(0.6 * DRONE.share(30), burn.share(30), 1e-12, "засыпанные клетки горят");
        } finally {
            FireSmoke.reset();
            SmokeGrid.reset();
        }
    }

    /** Закон воронки из пакета — как у клиента ({@code BlastEffects.fuelFire}): оружие и сколько клеток зажглось. */
    private static FuelPool law(S2C.FuelFire p) {
        return WeaponType.byId(p.weapon()).spec().pool(p.cells().size());
    }

    /** Пакет — через его формат на проводе, как его получает клиент. */
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

    /** Место — до тысячной блока (свет очага — во float). */
    private static void near(Vec3 want, Vec3 got, String what) {
        assertTrue(want.distanceTo(got) < 1e-3, what + ": " + got + " вместо " + want);
    }

    /** Место единственного очага клиента — откуда светит его пламя на дым ({@link FireSmoke#lights}: шар над очагом). */
    private static Vec3 source() {
        Matrix4f centres = new Matrix4f().zero(), light = new Matrix4f().zero();
        FireSmoke.lights(Vec3.ZERO, 1, 1, centres, light);
        Vector4f c = centres.getColumn(0, new Vector4f());
        assertTrue(c.w > 0, "пламени очага нет");
        return new Vec3(c.x, c.y - c.w, c.z);
    }
}
