package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Outside air window at the eye ({@link OpenAir}): by section, the eye is far from the edge at any location in the section. */
class OpenAirTest {
    /** The window size in the shader ({@code pall.glsl}: {@code PALL_OPEN_SIZE}) is the same as the texture. */
    @Test
    void shaderKnowsTheWindowSize() throws java.io.IOException {
        try (var in = OpenAirTest.class.getResourceAsStream("/assets/incoming/shaders/include/pall.glsl")) {
            String glsl = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(glsl.contains(String.format(java.util.Locale.ROOT, "const vec3 PALL_OPEN_SIZE = vec3(%d.0, %d.0, %d.0);",
                    OpenAir.SIZE_XZ, OpenAir.SIZE_Y, OpenAir.SIZE_XZ)), "PALL_OPEN_SIZE ≠ OpenAir.SIZE_*");
        }
    }

    @Test
    void eyeStaysFarFromTheWindowEdge() {
        for (int size : new int[]{OpenAir.SIZE_XZ, OpenAir.SIZE_Y}) {
            for (int eye = -100; eye <= 100; eye++) {
                int start = OpenAir.start(eye, size);
                assertEquals(0, Math.floorMod(start, 16), "window - in sections");
                assertTrue(eye - start >= size / 2 - 8 && start + size - 1 - eye >= size / 2 - 8,
                        "eye " + eye + " in the window " + start + "…" + (start + size - 1));
            }
        }
    }

    /**
     * A fresh window (first pall frame of a detonation, a teleport) reads at most {@link OpenAir#FILL} sections a frame,
     * nearest the eye first, and is whole within ⌈216 / FILL⌉ frames; a whole window keeps its rotation of
     * {@link OpenAir#REFRESH} sections a frame, each section reread in turn. All 216 in one frame cost 349 ms.
     */
    @Test
    void freshWindowFillsNearestFirstWithinTheBudget() {
        long[] stored = new long[OpenAir.SECTIONS], keys = new long[OpenAir.SECTIONS];
        java.util.Arrays.fill(stored, Long.MIN_VALUE);
        int ex = 120, ey = 4, ez = -37;
        int sx0 = OpenAir.start(ex * 16, OpenAir.SIZE_XZ) >> 4, sy0 = OpenAir.start(ey * 16, OpenAir.SIZE_Y) >> 4,
                sz0 = OpenAir.start(ez * 16, OpenAir.SIZE_XZ) >> 4;
        int i = 0;
        for (int sx = sx0; sx < sx0 + OpenAir.SIZE_XZ / 16; sx++)
            for (int sy = sy0; sy < sy0 + OpenAir.SIZE_Y / 16; sy++)
                for (int sz = sz0; sz < sz0 + OpenAir.SIZE_XZ / 16; sz++) keys[i++] = net.minecraft.core.SectionPos.asLong(sx, sy, sz);
        int[] due = new int[OpenAir.FILL];
        int frames = 0, lastDistance = -1;
        while (true) {
            int n = OpenAir.plan(keys, stored, ex, ey, ez, 0, due);
            assertTrue(n <= OpenAir.FILL);
            boolean fresh = false;
            for (int k = 0; k < n; k++) {
                long key = keys[due[k]];
                int slot = OpenAir.slot(net.minecraft.core.SectionPos.x(key), net.minecraft.core.SectionPos.y(key), net.minecraft.core.SectionPos.z(key));
                if (stored[slot] == key) continue;
                fresh = true;
                int dx = net.minecraft.core.SectionPos.x(key) - ex, dy = net.minecraft.core.SectionPos.y(key) - ey, dz = net.minecraft.core.SectionPos.z(key) - ez;
                int d = dx * dx + dy * dy + dz * dz;
                assertTrue(d >= lastDistance, "nearest first");
                lastDistance = d;
                stored[slot] = key;
            }
            if (!fresh) break;
            frames++;
        }
        assertEquals((OpenAir.SECTIONS + OpenAir.FILL - 1) / OpenAir.FILL, frames, "a whole window in ⌈216 / FILL⌉ frames");
        for (long key : keys)
            assertEquals(key, stored[OpenAir.slot(net.minecraft.core.SectionPos.x(key), net.minecraft.core.SectionPos.y(key), net.minecraft.core.SectionPos.z(key))]);
        // whole window: only the rotation, from where it stands
        int n = OpenAir.plan(keys, stored, ex, ey, ez, 210, due);
        assertEquals(OpenAir.REFRESH, n);
        for (int k = 0; k < n; k++) assertEquals((210 + k) % OpenAir.SECTIONS, due[k]);
    }
}
