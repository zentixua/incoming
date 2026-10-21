package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Дым выбросами: подъём термиком, масса, жизнь по толще, общий расчёт термиков, срок волны и конец источника. */
class PuffsTest {
    /** Клубы одного кадра. */
    private record Blob(double y, double sh, double soot, double dust) {}

    private static List<Blob> frame(Puffs p, double seconds) {
        List<Blob> out = new ArrayList<>();
        p.emit(seconds, (x, y, z, ax, ay, az, sh, sv, soot, dust, water, r, g, b, kelvin, rise) -> out.add(new Blob(y, sh, soot, dust)));
        return out;
    }

    /**
     * Горящий обломок (как в {@code Exhaust.debris}): за 0,1 с — 17,5 кДж огня древесины, 70 % — в воздух, сажи
     * 0,015 кг на 12,4 МДж. Выброс всплывает своим теплом, растёт и тает за секунды; сажа — та, что сгорела.
     */
    @Test
    void debrisPuffRisesAndFades() {
        Puffs p = new Puffs(CloudModel.Air.STANDARD, 0.2f, 0.2f, 0.2f, 1);
        double watts = FirePlume.BLOCK_WATTS * 0.1;
        p.add(0, 10, 0, 0, FirePlume.CONVECTIVE * watts, 800, 0.5, FirePlume.SOOT_PER_JOULE * watts, 0, 0, Double.NaN);
        assertEquals(1, p.size());
        Blob a = frame(p, 0.5).getFirst(), b = frame(p, 3).getFirst();
        assertEquals(FirePlume.SOOT_PER_JOULE * watts, a.soot, 1e-12, "сажа — сгоревшая древесина");
        assertTrue(b.y > a.y, "всплывает: " + a.y + " → " + b.y);
        assertTrue(b.sh > a.sh, "растёт: " + a.sh + " → " + b.sh);
        // тает, когда толща поперёк середины ниже порога: у грамма сажи — секунды, не минуты
        List<Blob> late = frame(p, 120);
        assertTrue(late.isEmpty(), "через 2 минуты ещё виден");
    }

    /** Аэрозоль тления идёт пылью с тем же ослаблением: толща клуба — его масса × своё ослабление. */
    @Test
    void aerosolKeepsItsOwnExtinction() {
        Puffs p = new Puffs(CloudModel.Air.STANDARD, 0.71f, 0.68f, 0.65f, 2);
        double kg = 0.2, k = 4.4e3;
        p.add(0, 0, 0, 0, 1e6, 600, 3, 0, kg, k, Double.NaN);
        Blob b = frame(p, 1).getFirst();
        float w = SmokeGrid.dustAlbedo(0.68f);
        assertEquals(kg * k, b.dust * SmokeGrid.dustK(SmokeGrid.dustAlbedo(0.71f), w, SmokeGrid.dustAlbedo(0.65f)), 1e-6 * kg * k);
    }

    /** Волна сметает дымок: после срока выброса нет. Источник без новых выбросов кончается сам, когда выпущенные растаяли. */
    @Test
    void waveEndsThePuffAndTheSourceEndsByItself() {
        Puffs p = new Puffs(CloudModel.Air.STANDARD, 0.7f, 0.7f, 0.7f, 3);
        p.add(0, 0, 0, 0, 1e6, 600, 3, 0, 0.2, 4.4e3, 2.0);
        assertEquals(1, frame(p, 0.5).size());
        assertFalse(p.ended(), "только что кормили, выброс жив");
        assertEquals(1, frame(p, 1.9).size());
        assertTrue(frame(p, 2.1).isEmpty(), "волна прошла — дымок остался");
        assertTrue(p.ended(), "без выбросов дольше QUIET и без живых — конец");
        // тоньше порога с самого начала — не выброс
        p = new Puffs(CloudModel.Air.STANDARD, 0.7f, 0.7f, 0.7f, 4);
        p.add(0, 0, 0, 0, 1e3, 600, 3, 1e-9, 0, 0, Double.NaN);
        assertEquals(0, p.size());
    }

    /** Термики близкой плавучести и радиуса — один расчёт (тысячи шагов РК4 на выброс стоили бы кадров). */
    @Test
    void similarPuffsShareOneThermal() {
        Thermal a = Puffs.thermal(CloudModel.Air.STANDARD, 1.0e5, 800, 0.5), b = Puffs.thermal(CloudModel.Air.STANDARD, 1.1e5, 810, 0.52);
        assertSame(a, b);
    }
}
