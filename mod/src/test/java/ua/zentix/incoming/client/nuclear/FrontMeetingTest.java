package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontMeetingTest {
    private static final int AGAIN = FrontMeeting.AGAIN, HEARD = FrontMeeting.HEARD, HIT = FrontMeeting.HIT;

    /** The front goes from {@code from} to {@code to} blocks one block at a time; what happened at each radius - in {@code out}. */
    private static int[] walk(FrontMeeting m, int from, int to, double ear, double body, boolean attached) {
        int[] out = new int[to + 1];
        for (int r = from; r <= to; r++) out[r] = m.tick(r, ear, body, attached);
        return out;
    }

    /**
     * The camera is on the player: from the first person it lags behind the eyes by a frame, from the third - in 4 blocks behind the back, and the sounds of the wave and the
     *  blow to the ears - in one tick (the sounds of the wave are heard as if before it), when the front reached the body.
     */
    @Test
    void cameraOnThePlayerMeetsTheFrontWithTheBody() {
        for (double ear : new double[]{1000.4, 999.6, 1004, 996}) {
            int[] out = walk(new FrontMeeting(), 0, 1200, ear, 1000, true);
            for (int r = 0; r <= 1200; r++) assertEquals(r == 1000 ? HEARD | HIT : 0, out[r], "camera in " + ear + ", front " + r);
        }
    }

    /**
     * The camera is not on the player, but nearby (the filming camera is 3 blocks away from him): the front behind the tick passes 17 blocks, and it
     *  meets him with the body, in one tick.
     */
    @Test
    void cameraNearThePlayerMeetsTheFrontWithTheBody() {
        for (double ear : new double[]{1003.2, 996.8}) {
            FrontMeeting m = new FrontMeeting();
            for (int r = 0; r < 1500; r += 17) assertEquals(r == 1003 ? HEARD | HIT : 0, m.tick(r, ear, 1000, false), "camera in " + ear + ", front " + r);
        }
    }

    /**
     * The projectile camera flies around the hit: its front immediately hits the player’s ears 3 km away - when it reaches him. The camera
     *  returned to the player, but the front has not yet reached him - the listener will meet him again, along with the body.
     */
    @Test
    void projectileCameraHearsTheBurstAndThePlayerTheWave() {
        FrontMeeting m = new FrontMeeting();
        int[] out = walk(m, 0, 700, 600, 3000, false);
        for (int r = 0; r <= 700; r++) assertEquals(r == 600 ? HEARD : 0, out[r], "flyby, front " + r);
        assertEquals(AGAIN, m.tick(701, 3000, 3000, true), "the camera returned to the player");
        out = walk(m, 702, 3100, 3000, 3000, true);
        for (int r = 702; r <= 3100; r++) assertEquals(r == 3000 ? HEARD | HIT : 0, out[r], "at the player, front " + r);
    }

    /**
     * Salvo: the flyby of the first hit met the front, the camera went to the next projectile ahead of the front, and the flyby of the next
     *  hit was again at the explosion site, which the front had long passed: the hit was not repeated.
     */
    @Test
    void cameraBackAtTheBurstHearsNothing() {
        FrontMeeting m = new FrontMeeting();
        int[] out = walk(m, 0, 100, 40, 3000, false);
        for (int r = 0; r <= 100; r++) assertEquals(r == 40 ? HEARD : 0, out[r], "first flyby, front " + r);
        assertEquals(AGAIN, m.tick(101, 2000, 3000, false), "camera on the next projectile");
        out = walk(m, 102, 400, 40, 3000, false);
        for (int r = 102; r <= 400; r++) assertEquals(0, out[r], "second flyby, front " + r);
    }

    /**
     * The camera is far behind the player (looking through the eyes of a projectile at 10 km): in the player’s ears - when the front is near the body, sound - when the camera is
     * . The camera returned to the body, which the front had already passed - the front passed by it, the blow was not heard.
     */
    @Test
    void cameraBackBehindTheFrontHearsNothing() {
        FrontMeeting m = new FrontMeeting();
        int[] out = walk(m, 0, 3100, 10_000, 3000, false);
        for (int r = 0; r <= 3100; r++) assertEquals(r == 3000 ? HIT : 0, out[r], "camera in the distance, front " + r);
        out = walk(m, 3101, 12_000, 3050, 3050, true);
        for (int r = 3101; r <= 12_000; r++) assertEquals(0, out[r], "body camera behind the front, front " + r);
        assertTrue(m.heard());
    }

    /**
     * From the first person: the front passed the player, the player was carried far ahead of the front - he hears the front again when it catches up with him, and hits his ears once.
     */
    @Test
    void playerAheadAgainHearsTheFrontAgain() {
        FrontMeeting m = new FrontMeeting();
        int[] out = walk(m, 0, 1010, 1000, 1000, true);
        assertEquals(HEARD | HIT, out[1000]);
        assertEquals(AGAIN, m.tick(1011, 2000, 2000, true), "front shift forward");
        out = walk(m, 1012, 2100, 2000, 2000, true);
        for (int r = 1012; r <= 2100; r++) assertEquals(r == 2000 ? HEARD : 0, out[r], "front " + r);
    }

    /**
     * Towards the front: the player runs towards the explosion (0,28 blocks per tick), the projectile camera flies towards him (4 and 25 blocks per tick), and the
     *  front passes 17 blocks per tick - the point crosses the front between ticks, and there is only one encounter, for any shift of the front.
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
                    else assertEquals(0, out, "player, " + speed + " block per tick, shift " + shift + ", tick " + t);
                    out = camera.tick(front, at, 5000, false);
                    if ((out & HEARD) != 0) onCamera++;
                    if ((out & HIT) != 0) hits++;
                }
                String what = speed + " block per tick, shift " + shift;
                assertEquals(1, onPlayer, what + ": player");
                assertEquals(1, onCamera, what + ": camera");
                assertEquals(1, hits, what + ": body behind the camera");
            }
        }
    }

    /** The player was transported to where the front had already passed: no impact, no sound. */
    @Test
    void playerMovedBehindTheFrontIsNotHit() {
        FrontMeeting m = new FrontMeeting();
        walk(m, 0, 500, 3000, 3000, true);
        int[] out = walk(m, 501, 4000, 300, 300, true);
        for (int r = 501; r <= 4000; r++) assertEquals(0, out[r], "front " + r);
    }

    /**
     * The camera flies level with the front, then in front of 60 blocks, then behind: one hit. She went ahead more than
     * {@link FrontMeeting#AHEAD} - she will meet the front again.
     */
    @Test
    void cameraAlongTheFrontHearsItOnce() {
        // how far the camera is ahead of the front, along the radius of the front: broken line by points
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
            if (r == 1100) assertEquals(1, heard, "until the second breakaway");
        }
        assertEquals(2, heard);
        assertEquals(1, again);
    }

    /** There is no player (exit from the world at the same tick): only sound from the camera. */
    @Test
    void noPlayerNoHit() {
        int[] out = walk(new FrontMeeting(), 0, 300, 200, Double.NaN, true);
        for (int r = 0; r <= 300; r++) assertEquals(r == 200 ? HEARD : 0, out[r], "front " + r);
    }
}
