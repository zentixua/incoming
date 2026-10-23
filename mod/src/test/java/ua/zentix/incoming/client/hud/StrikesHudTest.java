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
    /** Номера снарядов у крестика цели: подряд идущие — диапазоном, пара — через запятую. */
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
        assertEquals(60, StrikesHud.labelY(placed, 150, 60, 50), "наезжает — строкой ниже");
        assertEquals(50, StrikesHud.labelY(placed, 300, 60, 50), "в стороне — на своём месте");
        placed.add(new int[] {150, 60, 60});
        assertEquals(72, StrikesHud.labelY(placed, 120, 40, 52), "ниже обеих");
    }

    /** Рамка цели на экране: угловой размер цели, но не меньше {@link StrikesHud#MIN_FRAME} пикселей вокруг её середины. */
    @Test
    void frameIsTheTargetButNotSmallerThanTheMinimum() {
        // цель 40 × 80 пикселей — рамка ровно по ней
        assertArrayEquals(new float[]{100, 200, 140, 280}, StrikesHud.frame(new float[]{0.1f, 0.2f, 0.14f, 0.28f}, 1000, 1000), 1e-3f);
        // цель в пиксель — рамка наименьшая вокруг её середины
        float half = 0.5f * StrikesHud.MIN_FRAME;
        assertArrayEquals(new float[]{500 - half, 500 - half, 500 + half, 500 + half},
                StrikesHud.frame(new float[]{0.4995f, 0.4995f, 0.5005f, 0.5005f}, 1000, 1000), 1e-2f);
    }

    /**
     * Метка снаряда при зуме ×1, ×4, ×64 (поле зрения 70° / k, интерфейс 1280×800): рамка — угловой размер корпуса × k, а
     * пока он мельче {@link StrikesHud#MIN_FRAME} — сама наименьшая рамка, одна при любом k; подпись — те же пиксели над
     * рамкой и никогда не на ней (жалоба 23.10: «#1 0:16» сидела на шахеде и при зуме была больше него).
     */
    @Test
    void markerFrameGrowsWithTheBodyAndItsLabelStaysInPixelsOutside() {
        int w = 1280, h = 800;
        double tan = Math.tan(Math.toRadians(35));
        // шахед 3,6 м в 700 м: ×1 — 2,6 px (наименьшая рамка), ×4 — 10,6, ×64 — 169
        Vec3 shahed = new Vec3(0, 0, -700);
        float[] first = null;
        for (int k : new int[]{1, 4, 64}) {
            Matrix4f proj = new Matrix4f().perspective((float) (2 * Math.atan(tan / k)), (float) w / h, 0.05f, 4000);
            float[] r = ScreenProjection.rect(proj, new Matrix4f(), Vec3.ZERO, shahed, 1.8);
            assertNotNull(r);
            float[] fr = StrikesHud.frame(r, w, h);
            double body = 3.6 / 700 / (2 * tan / k) * h, tall = fr[3] - fr[1];
            assertEquals(Math.max(body, StrikesHud.MIN_FRAME), tall, 1e-2 * tall, "зум ×" + k);
            if (first == null) first = fr;
            // подпись над рамкой: тот же зазор в пикселях при любом поле зрения, низ строки — выше верха рамки
            int y = StrikesHud.labelAbove(fr);
            assertTrue(y + StrikesHud.LABEL_LINE <= fr[1], "подпись на рамке, зум ×" + k);
            assertEquals(StrikesHud.LABEL_GAP, Math.floor(fr[1]) - (y + StrikesHud.LABEL_LINE), "зум ×" + k);
            int below = StrikesHud.labelBelow(fr);
            assertTrue(below >= fr[3], "подпись цели на рамке, зум ×" + k);
            assertEquals(StrikesHud.LABEL_GAP, below - Math.ceil(fr[3]), "зум ×" + k);
        }
        // ×1: тело мельче наименьшей рамки — она та же, что у любого далёкого тела
        assertEquals(StrikesHud.MIN_FRAME, first[3] - first[1], 1e-3);
    }

    /** Пунктир от снаряда к цели — между рамками: от края одной до края другой; рамки слились — пунктира нет. */
    @Test
    void dottedLineRunsBetweenFrames() {
        float[] a = {0, 0, 10, 10}, b = {100, 0, 120, 10};
        assertArrayEquals(new float[]{10, 5}, StrikesHud.edge(a, 110, 5), 1e-4f);
        assertArrayEquals(new float[]{100, 5}, StrikesHud.edge(b, 5, 5), 1e-4f);
        assertNull(StrikesHud.edge(a, 6, 6));
    }
}
