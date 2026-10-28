package ua.zentix.incoming.registry;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.Penetration;
import ua.zentix.incoming.strike.WeaponType;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PenetrabilityTest {
    private static ModDataMaps.Penetrability read(String json) {
        return ModDataMaps.Penetrability.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    void oldEntriesAndUnknownBlocksRemainSolidMetres() {
        assertEquals(1, read("{\"kind\":\"rock\",\"s\":0.84}").thickness());
        assertEquals(1, ModDataMaps.Penetrability.OTHER.thickness());
        assertEquals(1, ModDataMaps.Penetrability.WATER.thickness());
    }

    @Test
    void thicknessRoundTripsAndRejectsInvalidDepths() {
        var entry = read("{\"kind\":\"concrete\",\"s\":1,\"thickness\":0.38}");
        assertEquals(entry, ModDataMaps.Penetrability.CODEC.parse(JsonOps.INSTANCE,
                ModDataMaps.Penetrability.CODEC.encodeStart(JsonOps.INSTANCE, entry).getOrThrow()).getOrThrow());
        for (String number : new String[]{"0", "-1", "1e999"}) {
            assertTrue(ModDataMaps.Penetrability.CODEC.parse(JsonOps.INSTANCE,
                    JsonParser.parseString("{\"kind\":\"rock\",\"thickness\":" + number + "}")).error().isPresent(), number);
        }
    }

    @Test
    void shippedDataRepresentsBuildingsAndPreservesNaturalMaterial() throws Exception {
        var values = JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/incoming/data_maps/block/penetration.json")))
                .getAsJsonObject().getAsJsonObject("values");
        for (String key : new String[]{"#minecraft:dirt", "#minecraft:sand", "#minecraft:base_stone_overworld",
                "#minecraft:iron_ores", "#minecraft:terracotta", "#minecraft:logs", "minecraft:deepslate",
                "minecraft:smooth_stone", "minecraft:polished_andesite", "minecraft:quartz_block"}) {
            assertEquals(1, read(values.get(key).toString()).thickness(), key);
        }
        var brick = read(values.get("minecraft:bricks").toString());
        double radius = brick.breaching().radius(WeaponType.ROCKET.spec().charge(), BreachModel.UNTAMPED);
        assertTrue(brick.thickness() < radius);
        assertTrue(3 * brick.thickness() > radius);
        for (var value : values.entrySet()) {
            var json = value.getValue().getAsJsonObject();
            if (json.has("thickness")) assertTrue(json.get("_comment").getAsString().contains("(est.)"), value.getKey());
            read(json.toString());
        }
    }

    @Test
    void representedPathConsumesYoungDepthOnce() {
        var body = Penetration.body(WeaponType.BUNKER.spec().payload().article());
        var thin = read("{\"kind\":\"concrete\",\"s\":0.84,\"thickness\":0.2}");
        var run = new Penetration.Run(body, 250);
        double material = run.through(thin.kind(), thin.s(), 2.5 * thin.thickness());
        assertEquals(2.5, material / thin.thickness(), 1e-9);
        assertEquals(250 * Math.sqrt(1 - 0.5 / Penetration.depth(body, 0.84, 250)), run.speed(), 1e-9);
    }
}
