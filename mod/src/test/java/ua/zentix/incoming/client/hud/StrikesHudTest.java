package ua.zentix.incoming.client.hud;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.render.ScreenProjection;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrikesHudTest {
    /** Numbers of shells at the target cross: consecutive ones - in a range, a pair - separated by a comma. */
    @Test
    void numbersCollapseIntoRanges() {
        assertEquals("3", StrikesHud.numbers(List.of(3)));
        assertEquals("1, 2", StrikesHud.numbers(List.of(2, 1)));
        assertEquals("1–4, 7", StrikesHud.numbers(List.of(4, 7, 1, 3, 2)));
        assertEquals("1, 3–5, 8, 9", StrikesHud.numbers(List.of(9, 5, 1, 8, 4, 3)));
    }

    @Test
    void overlappingLabelsStackDown() {
        List<int[]> placed = new java.util.ArrayList<>();
        placed.add(new int[] {100, 50, 80});
        assertEquals(60, StrikesHud.labelY(placed, 150, 60, 50), "runs over - line below");
        assertEquals(50, StrikesHud.labelY(placed, 300, 60, 50), "aside - in its place");
        placed.add(new int[] {150, 60, 60});
        assertEquals(72, StrikesHud.labelY(placed, 120, 40, 52), "below both");
    }

    /** Target frame on the screen: the angular size of the target, but not less than {@link StrikesHud#MIN_FRAME} pixels around its middle. */
    @Test
    void frameIsTheTargetButNotSmallerThanTheMinimum() {
        // target 40 × 80 pixels - frame exactly along it
        assertArrayEquals(new float[]{100, 200, 140, 280}, StrikesHud.frame(new float[]{0.1f, 0.2f, 0.14f, 0.28f}, 1000, 1000), 1e-3f);
        // pixel target - smallest frame around its middle
        float half = 0.5f * StrikesHud.MIN_FRAME;
        assertArrayEquals(new float[]{500 - half, 500 - half, 500 + half, 500 + half},
                StrikesHud.frame(new float[]{0.4995f, 0.4995f, 0.5005f, 0.5005f}, 1000, 1000), 1e-2f);
    }

    /**
     * Projectile mark during zoom ×1, ×4, ×64 (field of view 70° / k, interface 1280×800): frame - angular size of the body × k, and
     *  while it is smaller {@link StrikesHud#MIN_FRAME} is the smallest frame itself, one for any k; the signature is the same pixels above the
     *  frame and never on it (the complaint 23.10: “#1 0:16” was on Shahed and was larger than it when zoomed in).
     */
    @Test
    void markerFrameGrowsWithTheBodyAndItsLabelStaysInPixelsOutside() {
        int w = 1280, h = 800;
        double tan = Math.tan(Math.toRadians(35));
        // shahed 3,6 m to 700 m: ×1 - 2,6 px (smallest frame), ×4 - 10,6, ×64 - 169
        Vec3 shahed = new Vec3(0, 0, -700);
        float[] first = null;
        for (int k : new int[]{1, 4, 64}) {
            Matrix4f proj = new Matrix4f().perspective((float) (2 * Math.atan(tan / k)), (float) w / h, 0.05f, 4000);
            float[] r = ScreenProjection.rect(proj, new Matrix4f(), Vec3.ZERO, shahed, 1.8);
            assertNotNull(r);
            float[] fr = StrikesHud.frame(r, w, h);
            double body = 3.6 / 700 / (2 * tan / k) * h, tall = fr[3] - fr[1];
            assertEquals(Math.max(body, StrikesHud.MIN_FRAME), tall, 1e-2 * tall, "zoom ×" + k);
            if (first == null) first = fr;
            // signature above the frame: the same gap in pixels for any field of view, the bottom of the line is higher than the top of the frame
            int y = StrikesHud.labelAbove(fr);
            assertTrue(y + StrikesHud.LABEL_LINE <= fr[1], "signature on frame, zoom ×" + k);
            assertEquals(StrikesHud.LABEL_GAP, Math.floor(fr[1]) - (y + StrikesHud.LABEL_LINE), "zoom ×" + k);
            int below = StrikesHud.labelBelow(fr);
            assertTrue(below >= fr[3], "target signature on frame, zoom ×" + k);
            assertEquals(StrikesHud.LABEL_GAP, below - Math.ceil(fr[3]), "zoom ×" + k);
        }
        // ×1: the body is smaller than the smallest frame - it is the same as that of any distant body
        assertEquals(StrikesHud.MIN_FRAME, first[3] - first[1], 1e-3);
    }

    /** The dotted line from the projectile to the target is between the frames: from the edge of one to the edge of the other; the frames have merged - there is no dotted line. */
    @Test
    void dottedLineRunsBetweenFrames() {
        float[] a = {0, 0, 10, 10}, b = {100, 0, 120, 10};
        assertArrayEquals(new float[]{10, 5}, StrikesHud.edge(a, 110, 5), 1e-4f);
        assertArrayEquals(new float[]{100, 5}, StrikesHud.edge(b, 5, 5), 1e-4f);
        assertNull(StrikesHud.edge(a, 6, 6));
    }
}
