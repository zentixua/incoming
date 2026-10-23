package ua.zentix.incoming.client.hud;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
