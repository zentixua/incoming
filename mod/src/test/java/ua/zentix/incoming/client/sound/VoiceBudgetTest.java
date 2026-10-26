package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Motor channels: only audible sounds, no more than the limit, the loudest; without rattling at the borders. */
class VoiceBudgetTest {
    private static final float LOUD = 0.5f, SILENT = 0;

    private static int count(boolean[] keep) {
        int n = 0;
        for (boolean k : keep) if (k) n++;
        return n;
    }

    @Test
    void silentLayerDoesNotStart() {
        boolean[] keep = VoiceBudget.select(new float[]{SILENT, VoiceBudget.AUDIBLE / 2, LOUD}, new boolean[3], new int[]{99, 99, 0}, 48);
        assertArrayEquals(new boolean[]{false, false, true}, keep);
    }

    @Test
    void silentLayerKeepsChannelForHoldTicksThenReleases() {
        boolean[] live = {true};
        assertTrue(VoiceBudget.select(new float[]{SILENT}, live, new int[]{1}, 48)[0], "change layers to tick - do not break the loop");
        assertTrue(VoiceBudget.select(new float[]{SILENT}, live, new int[]{VoiceBudget.HOLD - 1}, 48)[0]);
        assertFalse(VoiceBudget.select(new float[]{SILENT}, live, new int[]{VoiceBudget.HOLD}, 48)[0], "silent for a long time - the channel is free");
    }

    @Test
    void capKeepsLoudest() {
        int n = 100;
        float[] gain = new float[n];
        for (int i = 0; i < n; i++) gain[i] = 0.01f + i * 0.001f;
        boolean[] keep = VoiceBudget.select(gain, new boolean[n], new int[n], 48);
        assertEquals(48, count(keep));
        for (int i = 0; i < n; i++) assertEquals(i >= n - 48, keep[i], "layer " + i);
    }

    @Test
    void playingLayerYieldsOnlyToMuchLouder() {
        boolean[] live = {true, false};
        // the new one is a little louder than the sounding one - it doesn’t take up space (otherwise, at the border of the limit, the layers would twitch every tick)
        assertArrayEquals(new boolean[]{true, false}, VoiceBudget.select(new float[]{0.1f, 0.15f}, live, new int[2], 1));
        // twice as loud and longer - takes up
        assertArrayEquals(new boolean[]{false, true}, VoiceBudget.select(new float[]{0.1f, 0.21f}, live, new int[2], 1));
    }

    @Test
    void quietHeldLayersGiveWayFirst() {
        // the limit is occupied by silent layers that still hold the channel: the audible new one displaces them
        boolean[] keep = VoiceBudget.select(new float[]{SILENT, SILENT, LOUD}, new boolean[]{true, true, false}, new int[]{3, 3, 0}, 2);
        assertTrue(keep[2]);
        assertEquals(2, count(keep));
    }
}
