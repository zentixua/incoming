package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.fx.plume.TrailModel.Trail;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Общий круговой буфер точек шлейфов: тают своим сроком, полный буфер затирает старые, отбой снимает шлейф сразу. */
class TrailPointsTest {
    private static final Trail STYLE = ClientWeaponSpec.of(WeaponType.ROCKET).airframe(false).far().stage(FlightPhase.BOOST).trail();
    /** Срок жизни точек, тиков: короткий и долгий след. */
    private static final float SHORT = 40, LONG = 1000;

    @Test
    void pointsMeltByTheirOwnLifetime() {
        TrailPoints p = new TrailPoints();
        long a = p.add(0, 0, 0, 100, STYLE, 0, 1, TrailPoints.NONE, 0, SHORT, 0, 0, 0, 0, 0);
        long b = p.add(1, 0, 0, 100, STYLE, 0, 2, TrailPoints.NONE, 0, LONG, 0, 0, 0, 0, 0);
        long c = p.add(2, 0, 0, 101, STYLE, 0, 1, a, 0, SHORT, 0, 0, 0, 0, 0);
        p.expire(139);
        assertTrue(p.holds(a) && p.holds(c), "короткий ещё жив");
        p.expire(140);
        assertFalse(p.holds(a), "растаял с хвоста");
        // за долгим шлейфом короткий растаял, но с хвоста его не убрать — его отсеет кадр по возрасту
        assertTrue(p.holds(b) && p.holds(c));
        assertTrue(141 - p.birth[TrailPoints.index(c)] >= p.life[TrailPoints.index(c)]);
        p.expire(1100);
        assertTrue(p.isEmpty());
    }

    @Test
    void frozenWorldStopsAgeing() {
        // /tick freeze на 30 тиков: точка 100-го тика тает к 170-му, а не к 140-му
        TrailPoints p = new TrailPoints();
        long a = p.add(0, 0, 0, 100, STYLE, 0, 1, TrailPoints.NONE, 0, SHORT, 0, 0, 0, 0, 0);
        for (int i = 0; i < 30; i++) p.hold();
        p.expire(169);
        assertTrue(p.holds(a), "в заморозке не стареет");
        p.expire(170);
        assertFalse(p.holds(a));
    }

    @Test
    void finishedRibbonEndsUntilThePathResumes() {
        TrailPoints p = new TrailPoints();
        long a = p.add(0, 0, 0, 0, STYLE, 0, 1, TrailPoints.NONE, 0, LONG, 0, 0, 0, 0, 0);
        long b = p.add(1, 0, 0, 4, STYLE, 0, 1, a, 0, LONG, 0, 0, 0, 0, 0);
        p.finish(b);
        assertTrue(p.last[TrailPoints.index(b)], "лента кончилась: там сходит на нет");
        assertFalse(p.last[TrailPoints.index(a)]);
        // путь снова есть — лента продолжилась, прошлый конец больше не конец
        long c = p.add(2, 0, 0, 8, STYLE, 0, 1, b, 0, LONG, 0, 0, 0, 0, 0);
        assertFalse(p.last[TrailPoints.index(b)]);
        assertFalse(p.last[TrailPoints.index(c)]);
        p.finish(TrailPoints.NONE);
    }

    @Test
    void fullBufferOverwritesOldestAndBreaksLinksToIt() {
        TrailPoints p = new TrailPoints();
        long prev = TrailPoints.NONE, firstLink = TrailPoints.NONE;
        for (int i = 0; i < TrailPoints.CAPACITY + 10; i++) {
            prev = p.add(i, 0, 0, i, STYLE, 0, 1, prev, 0, LONG, 0, 0, 0, 0, 0);
            if (i == 10) firstLink = prev;
        }
        assertEquals(10, p.first(), "затёрты десять самых старых");
        assertFalse(p.holds(9));
        assertTrue(p.holds(10));
        // точка 10 ссылается на затёртую 9: отрезка к ней нет
        assertFalse(p.holds(p.prev[TrailPoints.index(firstLink)]));
        assertEquals(TrailPoints.CAPACITY + 9, p.prev[TrailPoints.index(prev)] + 1);
    }

    @Test
    void cancelledFlightTrailVanishesAtOnce() {
        TrailPoints p = new TrailPoints();
        long a = p.add(0, 0, 0, 0, STYLE, 0, 7, TrailPoints.NONE, 0, LONG, 0, 0, 0, 0, 0);
        long b = p.add(0, 0, 0, 0, STYLE, 0, 8, TrailPoints.NONE, 0, LONG, 0, 0, 0, 0, 0);
        p.kill(7);
        assertNull(p.style[TrailPoints.index(a)]);
        assertNotNull(p.style[TrailPoints.index(b)], "чужой шлейф остаётся");
        p.expire(1);
        assertFalse(p.holds(a));
        assertTrue(p.holds(b));
    }

    @Test
    void wholeRocketPackFitsQuarterOfBuffer() {
        // пакет «Града» — 40 снарядов: точка раз в шаг, пока горит двигатель, и точка выгорания
        Trail trail = ClientWeaponSpec.of(WeaponType.ROCKET).airframe(false).far().stage(FlightPhase.BOOST).trail();
        assertNotNull(trail);
        int pack = LauncherEntity.ROCKET_COLUMNS * LauncherEntity.ROCKET_ROWS;
        int perRocket = RocketEntity.BURN_TICKS / trail.step() + 2;
        assertTrue(pack * perRocket <= TrailPoints.CAPACITY / 4, pack * perRocket + " точек");
    }
}
