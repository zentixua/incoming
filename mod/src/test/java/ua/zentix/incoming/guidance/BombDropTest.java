package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BombDropTest {
    /** B-2: эшелон 170, дальность сброса на нём 145, 12 блоков/тик. */
    private static final double RATIO = 145.0 / 170, SPEED = 12;
    /** Скорость B-2 и бомбы при сбросе: горизонтально, курсом +z. */
    private static final Vec3 LEVEL = new Vec3(0, 0, SPEED);

    /**
     * Где B-2 сбросит бомбу, идя по прямой на высоте {@code h} над точкой (на {@code side} в стороне от курса) из
     * {@code from} блоков до неё; -1 — нигде.
     */
    private static double releaseAt(double h, double from, double side) {
        Vec3 aim = Vec3.ZERO;
        for (double z = -from; z < 400; z += SPEED) {
            Vec3 pos = new Vec3(side, h, z);
            if (BombDrop.releaseNow(pos, LEVEL, aim, RATIO)) return pos.horizontalDistance();
        }
        return -1;
    }

    /**
     * На эшелоне — у черты 145 блоков (за тик B-2 проходит 12), с какого бы места он ни шёл: бомба сходит со скоростью
     * B-2 и ближе ~126 блоков со 170 не успевает опустить нос — перелетает точку.
     */
    @Test
    void releasesAtTheLineOnLevel() {
        for (int phase = 0; phase < SPEED; phase++) {
            double at = releaseAt(170, 1200 + phase, 0);
            assertTrue(at > 145 - SPEED && at <= 145 + 3, "сброс в " + at + " (сдвиг " + phase + ")");
        }
    }

    /**
     * С любой высоты от 20 до 490 блоков над точкой (вернулся в мир над горой, точку перенацелили выше или ниже) B-2
     * сбрасывает с первого захода и не дальше края окна: выше эшелона на черте для своей высоты, ниже — на краю, а он
     * по курсу не дальше высоты, дуги опускания носа и {@link BombDrop#EDGE_REACH} (дальше — рваные попадания). Точка сбоку
     * от курса — тоже, если над ней хватает высоты: бомба доворачивает к ней, пока падает, и низко (ниже ~50 блоков
     * сверх отступа) не успевает — тогда заход снова. Что бомба оттуда приходит в точку в мире, проверяет GameTest
     * {@code bomberReleasePointsHitInWorld}.
     */
    @Test
    void releasesFromAnyHeight() {
        for (int h = 20; h <= 490; h += 10) {
            for (double side : new double[]{0, 20, -35, 60}) {
                if (side != 0 && h < 50 + Math.abs(side)) continue;
                for (int phase = 0; phase < SPEED; phase++) {
                    double at = releaseAt(h, 1200 + phase, side);
                    String where = "с " + h + " (сбоку " + side + ", сдвиг " + phase + ")";
                    assertTrue(at > 0, where + " не сбросил");
                    double along = Math.sqrt(at * at - side * side);
                    assertTrue(at <= h * RATIO || along <= h + BombDrop.pullOver(SPEED) + BombDrop.EDGE_REACH, where + " сбросил в " + at);
                }
            }
        }
    }

    /**
     * Ближний край окна — там, где нос бомбы касается земли раньше, чем она подходит к точке: со 140 блоков со 126 и со
     * 170 со 118 бомба перелетает точку на 11–23 блока.
     */
    @Test
    void edgeCountsTheNoseAndGround() {
        assertTrue(BombDrop.miss(new Vec3(0, 140, -126), LEVEL, Vec3.ZERO) > 10);
        assertTrue(BombDrop.miss(new Vec3(0, 170, -118), LEVEL, Vec3.ZERO) > 10);
    }

    /**
     * Промахи, из-за которых сброс зависит от места: со 170 с 85 (прежняя черта, когда бомба сходила медленнее B-2)
     * бомба перелетает точку на ~100 блоков, на точку в 40 блоках впереди — тоже на десятки.
     * С 214 B-2 сбрасывает дальше эшелонной черты, а внутри ближнего края окна не сбрасывает вовсе и заходит снова.
     */
    @Test
    void doesNotReleaseWhereTheBombMisses() {
        assertTrue(BombDrop.miss(new Vec3(0, 170, -85), LEVEL, Vec3.ZERO) > 50);
        assertTrue(releaseAt(214, 700, 0) > 145, "с 214 — дальше черты");
        assertTrue(BombDrop.miss(new Vec3(0, 170, -40), LEVEL, Vec3.ZERO) > 30);
        assertEquals(-1, releaseAt(170, 100, 0), "внутри края окна не сбрасывает");
        assertEquals(-1, releaseAt(170, 40, 0), "точка в 40 блоках впереди");
    }

    /** Точка позади: бомба падает по курсу и назад не рулит — не сбрасывать. */
    @Test
    void neverReleasesOverPointBehind() {
        Vec3 pos = new Vec3(0, 170, 60);
        assertTrue(BombDrop.passed(pos, Vec3.ZERO, 0f));
        assertTrue(BombDrop.miss(pos, LEVEL, Vec3.ZERO) > 0);
    }

    /** B-2 ниже точки (перенацелили на вершину горы над ним): бомба туда не долетит — не сбрасывать. */
    @Test
    void neverReleasesBelowThePoint() {
        assertEquals(-1, releaseAt(-20, 1000, 0));
    }
}
