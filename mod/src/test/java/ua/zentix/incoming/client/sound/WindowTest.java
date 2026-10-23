package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.sound.Propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Окно громкости ({@link Loudness.Window}): в тишине громкий звук — у потолка, под взрывом — ниже на всю разницу уровней,
 * что громче, то и в игре громче, ядерка — на своей шкале. Прогон RV2 (23.10): пролёт шахеда в 54 м на неподвижной
 * шкале звучал на ~20 дБ тише 3.4.1.
 */
class WindowTest {
    private static final double OPEN = 200;
    private static final Loudness.Window W = Loudness.WINDOW;

    @BeforeEach
    void quiet() {
        W.reset();
    }

    private static double lufs(double gain) {
        return Loudness.ENGINE_LUFS + 20 * Math.log10(gain);
    }

    /** Шахед над головой (~90 дБА в 54 м, {@code WeaponSpec.DRONE_NOISE}) в тишине — 7,5 дБ под потолком, а не 23,5. */
    @Test
    void flybyInQuietIsNearTheCeiling() {
        double got = lufs(Loudness.gain(90, OPEN));
        assertEquals(Loudness.CEILING_LUFS - (Loudness.Window.FLOOR - 90) / 2, got, 1e-9);
        assertTrue(got > Loudness.CEILING_LUFS - 8, "пролёт: " + got + " LUFS");
    }

    /** Звук громче {@link Loudness.Window#FLOOR} в тишине — у потолка; окно не выше взрыва с 1 psi. */
    @Test
    void loudestSoundSitsAtTheCeiling() {
        for (double level = Loudness.Window.FLOOR; level < 170; level += 5) {
            W.reset();
            assertEquals(Loudness.CEILING_LUFS, lufs(Loudness.gain(level, OPEN)), 1e-9, level + " дБА");
            assertEquals(Math.min(level, Loudness.CEILING_LEVEL), W.top(), 1e-9);
        }
    }

    /** В каждый миг громче — значит громче и в игре: окно одно на все звуки, порядок не меняется. */
    @Test
    void louderStaysLouder() {
        W.hear(120);
        double prev = 0;
        for (double level = Propagation.NIGHT; level <= 120; level += 1) {
            double g = Loudness.windowed(level, W.top());
            assertTrue(g > prev, level + " дБА");
            prev = g;
        }
    }

    /**
     * Взрыв с 1 psi поднимает окно к {@link Loudness#CEILING_LEVEL}: мотор под ним — на половину разницы уровней ниже
     * потолка, и возвращается к тишине за (верх − пол) / {@link Loudness.Window#RELEASE} тиков после удержания.
     */
    @Test
    void blastDucksMotorsAndLetsThemBack() {
        W.blast(Loudness.CEILING_LUFS);
        assertEquals(Loudness.CEILING_LEVEL, W.top(), 1e-9);
        assertEquals(Loudness.CEILING_LUFS - (Loudness.CEILING_LEVEL - 90) / 2, lufs(Loudness.windowed(90, W.top())), 1e-9);
        int back = (int) Math.ceil((Loudness.CEILING_LEVEL - Loudness.Window.FLOOR) / Loudness.Window.RELEASE) + Loudness.Window.HOLD;
        for (int i = 0; i < back - 1; i++) W.tick();
        assertTrue(W.top() > Loudness.Window.FLOOR, "окно ещё не опустилось: " + W.top());
        W.tick();
        assertEquals(Loudness.Window.FLOOR, W.top(), 1e-9);
        assertTrue(back < 6 * 20, "моторы вернулись за " + back / 20.0 + " с");
    }

    /** Тише взрыва с 1 psi окно не встаёт выше его: дальний взрыв поднимает окно до своего уровня. */
    @Test
    void blastLevelFollowsItsLoudness() {
        W.blast(Loudness.CEILING_LUFS - 6);
        assertEquals(Loudness.CEILING_LEVEL - 12, W.top(), 1e-9);
    }

    /** Ракета уходит от пусковой быстрее, чем опускается окно: в игре она стихает, а не держится у потолка. */
    @Test
    void recedingSoundFades() {
        double first = lufs(Loudness.gain(130, OPEN));
        double level = 130, last = first;
        for (int t = 0; t < 40; t++) {
            W.tick();
            level -= 0.6;
            last = lufs(Loudness.gain(level, OPEN));
        }
        assertEquals(Loudness.CEILING_LUFS, first, 1e-9);
        assertTrue(last < first - 5, "через 2 с: " + last + " LUFS");
    }

    /** Звуки ядерного удара — на прежней неподвижной шкале: окно их не трогает и ими не двигается. */
    @Test
    void nukeIsOutsideTheWindow() {
        W.blast(Loudness.CEILING_LUFS);
        double a = Loudness.gain(110, OPEN, Loudness.ENGINE_LUFS);
        W.reset();
        double b = Loudness.gain(150, OPEN, Loudness.ENGINE_LUFS);
        assertEquals(Math.min(1, Math.pow(10, (110 - Loudness.REF) / 40)), a, 1e-12);
        assertEquals(1, b, 1e-12);
        assertEquals(Loudness.Window.FLOOR, W.top(), 1e-9);
    }

    /** Звук под фоном не слышно и окно не двигает. */
    @Test
    void unheardSoundsDoNotMoveTheWindow() {
        assertEquals(0, Loudness.gain(130, 0), 1e-12);
        assertEquals(Loudness.Window.FLOOR, W.top(), 1e-9);
    }
}
