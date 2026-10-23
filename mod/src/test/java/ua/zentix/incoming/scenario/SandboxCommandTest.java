package ua.zentix.incoming.scenario;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.strike.Waypoints;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Команды песочницы эффектов: из чата ({@code /fx …}) и из файла — одна строка, ошибка — до того, как что-то сыграло. */
class SandboxCommandTest {
    @Test
    void parsesEffectsFromChatAndFile() {
        SandboxCommand c = SandboxCommand.parse("/fx blast missile 7");
        assertEquals(SandboxCommand.Verb.BLAST, c.verb());
        assertEquals(WeaponType.MISSILE, c.weapon());
        assertEquals(7, c.seed());
        assertTrue(c.effect());
        assertEquals("blast missile 7", c.toString());
        assertEquals(SandboxCommand.Verb.FLIGHT, SandboxCommand.parse("flight drone 400\n").verb());
        assertFalse(SandboxCommand.parse("cam 60 10 90 8").effect());
        assertEquals(16, SandboxCommand.parse("zoom 16").number(0));
        assertTrue(SandboxCommand.parse("flight drone 600 own").own());
        assertTrue(SandboxCommand.parse("launch missile own").own());
        assertFalse(SandboxCommand.parse("flight drone 600").own());
    }

    /**
     * Пролёт идёт по прямой над точкой: у оружия с маршрутом оператора точка маршрута — сама точка, на одной прямой
     * между местом пуска и целью, и путь в его дальности (иначе сервер отклонил бы приказ, {@code routeProblem}); у
     * оружия без маршрута точек нет (с {@code via} приказ не прошёл бы). Без точки шахед и ракета летели петлёй и
     * проходили в 200–350 блоках от точки — песочница их не дожидалась.
     */
    @Test
    void flightPassesOverThePointInAStraightLine() {
        int x = 12, z = -40;
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE, WeaponType.LOITER, WeaponType.ROCKET, WeaponType.BUNKER}) {
            for (String args : new String[]{"", " 400", " 5000 own"}) {
                SandboxCommand c = SandboxCommand.parse("flight " + w.getSerializedName() + args);
                int from = c.flightFrom();
                String order = c.order(x, z);
                String path = String.format(Locale.ROOT, "incoming salvo %s 1 0 at %d %d from %d %d", w.getSerializedName(), x, z - from, x, z + from);
                if (w.spec().route().waypoints()) {
                    assertEquals(path + " via " + x + " " + z, order, c.toString());
                    Vec3 site = new Vec3(x, 0, z), start = new Vec3(x, 0, z + from), aim = new Vec3(x, 0, z - from);
                    Waypoints via = new Waypoints(List.of(site));
                    assertEquals(2.0 * from, via.length(start, aim), 1e-6, "по прямой: " + c);
                    assertTrue(via.within(w, start, aim), "в дальности маршрута: " + c);
                } else {
                    assertEquals(path, order, c.toString());
                }
            }
        }
        assertEquals(SandboxCommand.FLIGHT_FROM, SandboxCommand.parse("flight drone own").flightFrom());
        assertEquals("incoming salvo missile 1 0 at 3 -2995 from 3 5", SandboxCommand.parse("launch missile").order(3, 5));
    }

    @Test
    void shotsKeepTimesInOrder() {
        SandboxCommand c = SandboxCommand.parse("shots drone_50 0,0.1,0.5,3 4");
        assertArrayEquals(new double[]{0, 0.1, 0.5, 3}, c.times());
        assertEquals(4, c.number(2, 0));
        assertThrows(IllegalArgumentException.class, () -> SandboxCommand.parse("shots a 1,0.5"));
        assertThrows(IllegalArgumentException.class, () -> SandboxCommand.parse("shots ../x 1"));
        // до начала эффекта — не раньше −10 с (у nuke … near начало — приход фронта к домам)
        assertArrayEquals(new double[]{-0.5, 0.1, 1}, SandboxCommand.parse("shots wall -0.5,0.1,1 3").times());
        assertThrows(IllegalArgumentException.class, () -> SandboxCommand.parse("shots wall -11,0 3"));
    }

    @Test
    void nukeNearTakesBurstAndRange() {
        SandboxCommand c = SandboxCommand.parse("nuke 15 ground near");
        assertTrue(c.near());
        assertFalse(c.air());
        assertEquals(SandboxCommand.NEAR_RANGE, c.nearRange());
        c = SandboxCommand.parse("nuke 2 air near 1200");
        assertTrue(c.near() && c.air());
        assertEquals(1200, c.nearRange());
        c = SandboxCommand.parse("nuke 15 near 1500");
        assertTrue(c.near());
        assertEquals(1500, c.nearRange());
        assertFalse(SandboxCommand.parse("nuke 15 ground").near());
        for (String bad : new String[]{"nuke 15 near 50", "nuke 15 near far", "nuke 15 ground near 2000 x", "nuke 15 near ground"}) {
            assertThrows(IllegalArgumentException.class, () -> SandboxCommand.parse(bad), bad);
        }
    }

    @Test
    void timeTakesDayNightOrTick() {
        for (String ok : new String[]{"time day", "time night", "time 0", "time 13000"}) {
            assertEquals(SandboxCommand.Verb.TIME, SandboxCommand.parse(ok).verb(), ok);
        }
    }

    @Test
    void badCommandsFailBeforeRunning() {
        for (String bad : new String[]{"", "boom", "blast", "blast tank", "blast nuke", "nuke 40", "nuke 5 high", "cam far",
                "fire 99", "hud maybe", "again now", "time noon", "time -100", "time 3e9", "time 13000.5", "zoom 0.5", "zoom",
                "zoom 1000", "flight drone own 600", "flight drone 600 mine", "flight drone 0", "flight drone 9000", "flight drone NaN", "track maybe"}) {
            assertThrows(IllegalArgumentException.class, () -> SandboxCommand.parse(bad), bad);
        }
    }
}
