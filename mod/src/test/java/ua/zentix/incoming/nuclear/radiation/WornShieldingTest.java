package ua.zentix.incoming.nuclear.radiation;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.registry.ModDataMaps;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Worn radiation shielding ({@link WornShielding}): the data map's value codec accepts [0, 1] and nothing else (a decode
 * error, which makes NeoForge's loader reject the file); per piece the data map wins over another source, which is
 * limited to [0, 1]; black rain under shielding gains only the transmitted share of its dust ({@link RadiationTicker#rained}).
 */
class WornShieldingTest {
    private static boolean decodes(String json) {
        return ModDataMaps.RADIATION_SHIELDING.codec().parse(JsonOps.INSTANCE, JsonParser.parseString(json)).isSuccess();
    }

    @Test
    void codecAcceptsOnlyShares() {
        for (String ok : List.of("0", "0.25", "1", "1.0")) assertTrue(decodes(ok), ok + " rejected");
        for (String bad : List.of("1.5", "-0.1", "1.0001", "\"0.25\"", "{\"value\": 0.25}")) assertTrue(!decodes(bad), bad + " accepted");
        assertEquals(0.25f, ModDataMaps.RADIATION_SHIELDING.codec().parse(JsonOps.INSTANCE, JsonParser.parseString("0.25")).getOrThrow());
    }

    @Test
    void dataMapWinsPerPiece() {
        assertEquals(0.1, WornShielding.piece(0.1f, 0.9), 1e-7, "both describe the item");
        assertEquals(0, WornShielding.piece(0f, 0.9), "a pack's 0 removes another source's shielding");
        assertEquals(0.4, WornShielding.piece(null, 0.4), "only the other source");
        assertEquals(0, WornShielding.piece(null, Double.NaN), "neither");
        assertEquals(1, WornShielding.piece(null, 1.5), "a broken foreign value is limited");
        assertEquals(0, WornShielding.piece(null, -0.3), "a negative foreign value cannot raise dose");
    }

    @Test
    void rainGainsTheTransmittedShare() {
        double field = 40;
        // unshielded: the law as it was, a twentieth of the field a second up to twice the field
        assertEquals(Math.max(1, Math.min(1 + field * 0.05, field * 2)), RadiationTicker.rained(1, field, 1), 1e-12);
        assertEquals(field * 2, RadiationTicker.rained(field * 2, field, 1), 1e-12);
        assertEquals(0.75 * field * 0.05, RadiationTicker.rained(0, field, 0.75), 1e-12, "one piece in four: three quarters of the dust");
        assertEquals(1e3, RadiationTicker.rained(1e3, field, 0.75), "rain never lowers the dust on the body");
        assertEquals(0.75 * field * 2, RadiationTicker.rained(0.75 * field * 2, field, 0.75), 1e-12, "ceiling under one piece");
        assertEquals(0, RadiationTicker.rained(0, field, 0), "the full set: no dust");
        assertEquals(3, RadiationTicker.rained(3, field, 0), "dust already on the body stays under a suit put on later");
    }
}
