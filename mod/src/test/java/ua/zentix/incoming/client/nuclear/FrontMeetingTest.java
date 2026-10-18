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
     * Камера не на игроке, но рядом (съёмочная камера в 3 блоках от него): фронт за тик проходит 17 блоков, и она
     * встречает его с телом, в один тик.
     */
    @Test
    void cameraNearThePlayerMeetsTheFrontWithTheBody() {
        for (double ear : new double[]{1003.2, 996.8}) {
            FrontMeeting m = new FrontMeeting();
            for (int r = 0; r < 1500; r += 17) assertEquals(r == 1003 ? HEARD | HIT : 0, m.tick(r, ear, 1000, false), "камера в " + ear + ", фронт " + r);
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
     * Залп: облёт первого попадания встретил фронт, камера ушла к следующему снаряду впереди фронта, а облёт следующего
     * попадания — снова у места взрыва, которое фронт давно прошёл: удар не повторяется.
     */
    @Test
    void cameraBackAtTheBurstHearsNothing() {
        FrontMeeting m = new FrontMeeting();
        int[] out = walk(m, 0, 100, 40, 3000, false);
        for (int r = 0; r <= 100; r++) assertEquals(r == 40 ? HEARD : 0, out[r], "первый облёт, фронт " + r);
        assertEquals(AGAIN, m.tick(101, 2000, 3000, false), "камера на следующем снаряде");
        out = walk(m, 102, 400, 40, 3000, false);
        for (int r = 102; r <= 400; r++) assertEquals(0, out[r], "второй облёт, фронт " + r);
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

    /**
     * От первого лица: фронт прошёл игрока, игрока перенесло далеко вперёд фронта — он слышит фронт снова, когда тот
     * его догонит, а по ушам бьёт один раз.
     */
    @Test
    void playerAheadAgainHearsTheFrontAgain() {
        FrontMeeting m = new FrontMeeting();
        int[] out = walk(m, 0, 1010, 1000, 1000, true);
        assertEquals(HEARD | HIT, out[1000]);
        assertEquals(AGAIN, m.tick(1011, 2000, 2000, true), "перенос вперёд фронта");
        out = walk(m, 1012, 2100, 2000, 2000, true);
        for (int r = 1012; r <= 2100; r++) assertEquals(r == 2000 ? HEARD : 0, out[r], "фронт " + r);
    }

    /**
     * Навстречу фронту: игрок бежит к взрыву (0,28 блока за тик), камера снаряда летит к нему (4 и 25 блоков за тик), а
     * фронт проходит 17 блоков за тик — точка пересекает фронт между тиками, и встреча одна, при любом сдвиге фронта.
     */
    @Test
    void movingTowardTheBurstMeetsTheFront() {
        for (double speed : new double[]{0.28, 4, 25}) {
            for (double shift = 0; shift < 17; shift += 0.25) {
                FrontMeeting player = new FrontMeeting(), camera = new FrontMeeting();
                int onPlayer = 0, onCamera = 0, hits = 0;
                for (int t = 0; t < 400; t++) {
                    double front = shift + 17 * t, at = Math.max(0, 1000 - speed * t);
                    int out = player.tick(front, at, at, true);
                    if (out == (HEARD | HIT)) onPlayer++;
                    else assertEquals(0, out, "игрок, " + speed + " блока за тик, сдвиг " + shift + ", тик " + t);
                    out = camera.tick(front, at, 5000, false);
                    if ((out & HEARD) != 0) onCamera++;
                    if ((out & HIT) != 0) hits++;
                }
                String what = speed + " блока за тик, сдвиг " + shift;
                assertEquals(1, onPlayer, what + ": игрок");
                assertEquals(1, onCamera, what + ": камера");
                assertEquals(1, hits, what + ": тело за камерой");
            }
        }
    }

    /** Игрока перенесло туда, где фронт уже прошёл: ни удара, ни звука. */
    @Test
    void playerMovedBehindTheFrontIsNotHit() {
        FrontMeeting m = new FrontMeeting();
        walk(m, 0, 500, 3000, 3000, true);
        int[] out = walk(m, 501, 4000, 300, 300, true);
        for (int r = 501; r <= 4000; r++) assertEquals(0, out[r], "фронт " + r);
    }

    /**
     * Камера летит вровень с фронтом, то впереди до 60 блоков, то позади: удар один. Ушла вперёд больше чем на
     * {@link FrontMeeting#AHEAD} — встретит фронт снова.
     */
    @Test
    void cameraAlongTheFrontHearsItOnce() {
        // насколько камера впереди фронта, по радиусу фронта: ломаная по точкам
        double[][] ahead = {{0, 30}, {500, 30}, {600, -20}, {800, 60}, {1000, -10}, {1200, 70}, {1400, -10}};
        FrontMeeting m = new FrontMeeting();
        int heard = 0, again = 0;
        for (int r = 0; r <= 1400; r++) {
            int i = 0;
            while (ahead[i + 1][0] < r) i++;
            double k = (r - ahead[i][0]) / (ahead[i + 1][0] - ahead[i][0]), a = ahead[i][1] + k * (ahead[i + 1][1] - ahead[i][1]);
            int out = m.tick(r, r + a, Double.NaN, false);
            if ((out & HEARD) != 0) heard++;
            if ((out & AGAIN) != 0) again++;
            if (r == 1100) assertEquals(1, heard, "до второго отрыва");
        }
        assertEquals(2, heard);
        assertEquals(1, again);
    }

    /** Игрока нет (выход из мира в тот же тик): только звук у камеры. */
    @Test
    void noPlayerNoHit() {
        int[] out = walk(new FrontMeeting(), 0, 300, 200, Double.NaN, true);
        for (int r = 0; r <= 300; r++) assertEquals(r == 200 ? HEARD : 0, out[r], "фронт " + r);
    }
}
