package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

class ImpactPreviewTest {
    @Test void acceptedReferenceRadiiUseTheProductionLaws() {
        double[][] rows = {{15, 592, 1605, 4457, 7186, 3359}, {100, 1114, 3021, 8389, 13525, 7387},
                {1000, 2400, 6509, 18073, 29139, 18045}};
        for (double[] row : rows) {
            var p = ImpactPreview.of(row[0], true, 20000, 1);
            assertEquals(row[1], p.height(), 1);
            for (int i = 1; i < 5; i++) assertEquals(row[i + 1], p.contours().get(i).radius(), 2);
            assertTrue(p.height() > p.contours().getFirst().radius());
        }
    }
    @Test void scalesEveryLengthOnceAndUsesWeatherForBurnsOnly() {
        var full = ImpactPreview.of(15, false, 20000, 1);
        var small = ImpactPreview.of(15, false, 20000, .1);
        var wet = ImpactPreview.of(15, false, 1000, 1);
        for (int i = 0; i < 5; i++) assertEquals(full.contours().get(i).radius() * .1, small.contours().get(i).radius(), 1e-8);
        assertEquals(0, full.height());
        assertEquals(full.contours().get(1), wet.contours().get(1));
        assertTrue(wet.contours().get(4).radius() < full.contours().get(4).radius());
    }
    @Test void yieldEntryRejectsRatherThanClampsAndPresetsRespectTheCap() {
        for (String s : new String[] {"", "0", "16", "-1", "1.5", "NaN", "10000000000000000"})
            assertTrue(ImpactPreview.parseYield(s, 15).isEmpty(), s);
        assertEquals(15, ImpactPreview.parseYield("15", 15).orElseThrow());
        assertArrayEquals(new int[] {1, 5, 15}, ImpactPreview.suggestions(15));
        assertEquals("1 Mt (1000 kt)", ImpactPreview.yieldText(1000));
    }
    @Test void unitsIgnoreHostLocaleAndFitIncludesTheWholeLargestContour() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ru-RU"));
            assertEquals("1.50 km", ImpactPreview.distance(1500));
            assertEquals("592 m", ImpactPreview.distance(592));
        } finally { Locale.setDefault(before); }
        var p = ImpactPreview.of(1000, true, 20000, 1);
        assertTrue(p.extent(true) * ImpactPreview.fitScale(p.extent(true), 320, 180) * 2 < 180);
    }
}
