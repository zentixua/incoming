package ua.zentix.incoming.util;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Прямая видимость над рельефом ({@link LineOfSight}): холм закрывает низкую цель, неизвестная колонка — нет. */
class LineOfSightTest {
    /** Ровная земля (первый воздух — y 64) и холм до y 90 в колонках x 100–104. */
    private static final LineOfSight.Columns HILL = (x, z) -> x >= 100 && x <= 104 ? 90 : 64;

    @Test
    void hillHidesLowTargetNotHighOne() {
        Vec3 radar = new Vec3(0.5, 65.5, 0.5);
        assertFalse(LineOfSight.clear(radar, new Vec3(300, 76, 0.5), HILL), "ракета на бреющем за холмом");
        assertTrue(LineOfSight.clear(radar, new Vec3(300, 160, 0.5), HILL), "цель высоко над холмом");
        assertTrue(LineOfSight.clear(radar, new Vec3(95, 66, 0.5), HILL), "цель перед холмом");
        // цель выходит из-за холма, поднимаясь: видна, как только прямая проходит над его вершиной у ближнего края
        // (x 100: прямая идёт вверх, ниже всего над холмом она там)
        double rise = 65.5 + (90 - 65.5) * (300 - 0.5) / (100 - 0.5);
        assertFalse(LineOfSight.clear(radar, new Vec3(300, rise - 1, 0.5), HILL));
        assertTrue(LineOfSight.clear(radar, new Vec3(300, rise + 1, 0.5), HILL));
    }

    /** Прямая наискось не проскальзывает между колонками на срезанном углу. */
    @Test
    void diagonalDoesNotSlipThroughCorner() {
        LineOfSight.Columns post = (x, z) -> x == 10 && z == 11 ? 200 : 64;
        // прямая (0,0) → (20,20) проходит ровно через угол колонок (10,10)/(11,11) — сдвиг на 0,3 задевает (10, 11)
        assertFalse(LineOfSight.clear(new Vec3(0.2, 70, 0.5), new Vec3(20.2, 70, 20.5), post));
    }

    /** Неготовый чанк (колонка не известна) прямую не закрывает: чанков не грузим, догадок о рельефе нет. */
    @Test
    void unknownColumnsDoNotBlock() {
        LineOfSight.Columns unknown = (x, z) -> x >= 100 ? LineOfSight.UNKNOWN : 64;
        assertTrue(LineOfSight.clear(new Vec3(0.5, 65.5, 0.5), new Vec3(300, 66, 0.5), unknown));
    }

    /** Отвесная прямая — одна колонка. */
    @Test
    void verticalLine() {
        assertTrue(LineOfSight.clear(new Vec3(0.5, 65, 0.5), new Vec3(0.5, 200, 0.5), HILL));
        assertFalse(LineOfSight.clear(new Vec3(102.5, 65, 0.5), new Vec3(102.5, 200, 0.5), HILL));
    }
}
