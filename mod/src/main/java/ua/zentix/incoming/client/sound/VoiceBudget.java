package ua.zentix.incoming.client.sound;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

/**
 * Which projectile-engine layers hold an OpenAL channel (without Minecraft, covered by a unit test).
 * <p>
 * Static-sound channels are shared: OpenAL Soft has ~250 (without device information, 22), and when exhausted
 * engine silently refuses new sound, including blasts. A layer therefore plays only while audible, at most
 * {@link #CAP} engine layers simultaneously, the loudest; remaining channels are for one-shots. To avoid clicks
 * and engine churn, selection has margin: an inaudible layer holds its channel another {@link #HOLD} ticks;
 * a playing layer yields to a newcomer only if it is {@link #KEEP} times louder.
 */
final class VoiceBudget {
    /** Below this layer is inaudible: do not start (presentation: −46 dB relative to reference, not hearing threshold; in {@code Loudness.REF}). */
    static final float AUDIBLE = 0.005f;
    /** Layer inaudible for this many consecutive ticks releases channel (budget). */
    static final int HOLD = 20;
    /** Simultaneously playing engine layers (channel budget). */
    static final int CAP = 48;
    /** New layer must be this many times louder (gain, +6 dB) than playing one to take its slot (presentation). */
    static final float KEEP = 2;

    private VoiceBudget() {}

    /**
     * Which layers should play this tick.
     *
     * @param gain  each layer's gain this tick (target, before smoothing)
     * @param live  layer currently playing (holding channel)
     * @param quiet consecutive inaudible ticks ({@code gain < AUDIBLE})
     * @param cap   maximum playing layers
     */
    static boolean[] select(float[] gain, boolean[] live, int[] quiet, int cap) {
        int n = gain.length;
        boolean[] keep = new boolean[n];
        int[] wanted = IntStream.range(0, n).filter(i -> gain[i] >= AUDIBLE || live[i] && quiet[i] < HOLD).toArray();
        if (wanted.length > cap) {
            Integer[] order = Arrays.stream(wanted).boxed().toArray(Integer[]::new);
            Arrays.sort(order, Comparator.comparingDouble((Integer i) -> gain[i] * (live[i] ? KEEP : 1)).reversed());
            wanted = Arrays.stream(order, 0, cap).mapToInt(Integer::intValue).toArray();
        }
        for (int i : wanted) keep[i] = true;
        return keep;
    }
}
