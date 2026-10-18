package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontMeetingTest {
    private static final int AGAIN = FrontMeeting.AGAIN, HEARD = FrontMeeting.HEARD, HIT = FrontMeeting.HIT;

    /** Фронт идёт от {@code from} до {@code to} блоков по блоку за тик; что случилось на каждом радиусе — в {@code out}. */
    private static int[] walk(FrontMeeting m, int from, int to, double ear, double body, boolean attached) {
        int[] out = new int[to + 1];
        for (int r = from; r <= to; r++) out[r] = m.tick(r, ear, body, attached);
        return out;
    }

    /**
     * Камера на игроке: от первого лица она отстаёт от глаз на кадр, от третьего — в 4 блоках за спиной, а звуки волны и
     * удар по ушам — в один тик (звуки волны слышно, как до неё), когда фронт дошёл до тела.
     */
    @Test
    void cameraOnThePlayerMeetsTheFrontWithTheBody() {
        for (double ear : new double[]{1000.4, 999.6, 1004, 996}) {
            int[] out = walk(new FrontMeeting(), 0, 1200, ear, 1000, true);
            for (int r = 0; r <= 1200; r++) assertEquals(r == 1000 ? HEARD | HIT : 0, out[r], "камера в " + ear + ", фронт " + r);
        }
    }

    /**
     * Камера снаряда облетает попадание: фронт у неё сразу, по ушам игрока за 3 км — когда дойдёт до него. Камера
     * вернулась к игроку, а фронт до него ещё не дошёл — слушатель встретит его снова, вместе с телом.
     */
    @Test
    void projectileCameraHearsTheBurstAndThePlayerTheWave() {
        FrontMeeting m = new FrontMeeting();
        int[] out = walk(m, 0, 700, 600, 3000, false);
        for (int r = 0; r <= 700; r++) assertEquals(r == 600 ? HEARD : 0, out[r], "облёт, фронт " + r);
        assertEquals(AGAIN, m.tick(701, 3000, 3000, true), "камера вернулась к игроку");
        out = walk(m, 702, 3100, 3000, 3000, true);
        for (int r = 702; r <= 3100; r++) assertEquals(r == 3000 ? HEARD | HIT : 0, out[r], "у игрока, фронт " + r);
    }

    /**
     * Камера далеко за игроком (смотрит глазами снаряда в 10 км): по ушам игроку — когда фронт у тела, звук — когда у
     * камеры. Камера вернулась к телу, которое фронт уже прошёл, — фронт мимо неё прошёл, удара не слышно.
     */
    @Test
    void cameraBackBehindTheFrontHearsNothing() {
        FrontMeeting m = new FrontMeeting();
        int[] out = walk(m, 0, 3100, 10_000, 3000, false);
        for (int r = 0; r <= 3100; r++) assertEquals(r == 3000 ? HIT : 0, out[r], "камера вдали, фронт " + r);
        out = walk(m, 3101, 12_000, 3050, 3050, true);
        for (int r = 3101; r <= 12_000; r++) assertEquals(0, out[r], "камера у тела за фронтом, фронт " + r);
        assertTrue(m.heard());
    }

    /** Камера летит вровень с фронтом, то чуть впереди, то позади: удар один, пока она не ушла вперёд на 64 блока. */
    @Test
    void cameraAlongTheFrontHearsItOnce() {
        FrontMeeting m = new FrontMeeting();
        int heard = 0;
        for (int r = 0; r <= 2000; r++) {
            if ((m.tick(r, Math.max(1, r + (r % 7 == 0 ? -3 : 30)), Double.NaN, false) & HEARD) != 0) heard++;
        }
        assertEquals(1, heard);
        assertEquals(AGAIN, m.tick(2001, 2001 + FrontMeeting.AHEAD + 1, Double.NaN, false));
    }

    /** Игрока нет (выход из мира в тот же тик): только звук у камеры. */
    @Test
    void noPlayerNoHit() {
        int[] out = walk(new FrontMeeting(), 0, 300, 200, Double.NaN, true);
        for (int r = 0; r <= 300; r++) assertEquals(r == 200 ? HEARD : 0, out[r], "фронт " + r);
    }
}
