package ua.zentix.replayscan.film;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A candidate camera is blocked only in a chunk the client holds, with a block at it; in a chunk the replay never held (the
 * rc-video reshoot's blanket and Silo eyes, far from where the recorder went) it is unknown and is scored, for a fixed camera
 * as for a roaming one.
 */
class CamerasBlockedTest {
    @Test
    void onlyALoadedChunkWithABlockBlocks() {
        assertFalse(Cameras.blocked(false, false), "absent chunk: unknown");
        assertFalse(Cameras.blocked(true, true), "loaded air");
        assertTrue(Cameras.blocked(true, false), "loaded block at the camera");
    }
}
