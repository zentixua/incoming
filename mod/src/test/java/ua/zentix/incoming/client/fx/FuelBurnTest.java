package ua.zentix.incoming.client.fx;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.FuelPool;
import ua.zentix.incoming.strike.WeaponType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
