package ua.zentix.incoming.scenario;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponType;

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
    }

    @Test
    void shotsKeepTimesInOrder() {
        SandboxCommand c = SandboxCommand.parse("shots drone_50 0,0.1,0.5,3 4");
        assertArrayEquals(new double[]{0, 0.1, 0.5, 3}, c.times());
        assertEquals(4, c.number(2, 0));
        assertThrows(IllegalArgumentException.class, () -> SandboxCommand.parse("shots a 1,0.5"));
        assertThrows(IllegalArgumentException.class, () -> SandboxCommand.parse("shots ../x 1"));
    }

    @Test
    void badCommandsFailBeforeRunning() {
        for (String bad : new String[]{"", "boom", "blast", "blast tank", "blast nuke", "nuke 40", "nuke 5 high", "cam far",
                "fire 99", "hud maybe", "again now", "time noon"}) {
            assertThrows(IllegalArgumentException.class, () -> SandboxCommand.parse(bad), bad);
        }
    }
}
