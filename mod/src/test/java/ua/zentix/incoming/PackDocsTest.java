package ua.zentix.incoming;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.gametest.PackExamples;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.Penetration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guide's datapack examples for pack makers ({@code docs/guide/packs.md}, {@code docs/guide/ru/packs.md}) are
 * the files {@code PackExampleGameTests} loads into the game ({@link PackExamples}), and the numbers the guide gives for
 * the reinforced-concrete example are the laws': the B-2 bomb's path (Young, a full metre per block) and the breach
 * radius of each warhead's charge in reinforced concrete (FM 5-250), against walls of whole blocks.
 */
class PackDocsTest {
    private static final List<Path> PAGES = List.of(Path.of("../docs/guide/packs.md"), Path.of("../docs/guide/ru/packs.md"));

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    void guideShowsTheTestedFiles() throws Exception {
        for (Path page : PAGES) {
            String text = Files.readString(page);
            for (String example : List.of(PackExamples.PENETRATION, PackExamples.BLOCK_CLASS, PackExamples.IGNITION)) {
                assertTrue(text.contains("```json\n" + example + "```"), page + " does not show the tested example:\n" + example);
            }
        }
    }

    @Test
    void reinforcedConcreteNumbers() {
        Penetration.Body mop = BunkerBusterEntity.body();
        // "about 4 blocks at game flight speeds (about 9 with scale.flight_speed 1)": the bomb hits at about 235 and 510 m/s
        assertEquals(4, Math.round(Penetration.depth(mop, 0.84, 235)), "game speed");
        assertEquals(9, Math.round(Penetration.depth(mop, 0.84, 510)), "real speed");
        // walls of whole blocks the charge blows through: the largest whole number below the breach radius
        Map<WeaponType, Integer> walls = Map.of(WeaponType.MISSILE, 2, WeaponType.DRONE, 1, WeaponType.ROCKET, 0, WeaponType.LOITER, 0);
        for (var e : walls.entrySet()) {
            double r = BreachModel.Material.REINFORCED.radius(e.getKey().spec().charge(), BreachModel.UNTAMPED);
            assertEquals(e.getValue().intValue(), (int) Math.ceil(r) - 1, e.getKey() + ": breach radius " + r + " m");
        }
    }
}
