package ua.zentix.incoming;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.gametest.PackExamples;
import ua.zentix.incoming.nuclear.world.BlockResponse;
import ua.zentix.incoming.strike.WeaponSpec;
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
 * the reinforced-concrete example are the laws' and appear in both pages: the B-2 bomb's path (Young, a full metre per
 * block) and the breach radius of each warhead's charge in reinforced concrete (FM 5-250), against walls of whole blocks;
 * the blast-class table's psi values for the thresholds kept as constants.
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

    /**
     * Impact speed of the B-2's bomb after its fall at game flight speeds, m/s: the figure of the guide's weapon page and
     * {@code BombGameTests} (a drop at 12 blocks/tick from 170 blocks).
     */
    private static final double GAME_IMPACT = 235;

    @Test
    void reinforcedConcreteNumbers() throws Exception {
        Penetration.Body mop = BunkerBusterEntity.body();
        long game = Math.round(Penetration.depth(mop, 0.84, GAME_IMPACT));
        long real = Math.round(Penetration.depth(mop, 0.84, WeaponSpec.BUNKER.payload().article().impact().speed()));
        // walls of whole blocks each charge blows through: the largest whole number below its breach radius
        Map<WeaponType, Integer> walls = new java.util.EnumMap<>(WeaponType.class);
        for (WeaponType w : List.of(WeaponType.MISSILE, WeaponType.DRONE, WeaponType.ROCKET, WeaponType.LOITER)) {
            walls.put(w, (int) Math.ceil(BreachModel.Material.REINFORCED.radius(w.spec().charge(), BreachModel.UNTAMPED)) - 1);
        }
        assertEquals(List.of(2, 1, 0, 0), List.of(walls.get(WeaponType.MISSILE), walls.get(WeaponType.DRONE),
                walls.get(WeaponType.ROCKET), walls.get(WeaponType.LOITER)), "the guide's sentence names missile, Shahed, Grad and Lancet so");
        String en = flat(PAGES.get(0)), ru = flat(PAGES.get(1));
        for (String s : List.of("goes about " + game + " blocks into this concrete", "(about " + real + " with real ones",
                "through a wall of up to " + walls.get(WeaponType.MISSILE) + " blocks of it", "a Shahed-136's through " + walls.get(WeaponType.DRONE) + " block")) {
            assertTrue(en.contains(s), "packs.md lacks \"" + s + "\"");
        }
        for (String s : List.of("примерно на " + game + " блока при игровых", "(примерно на " + real + " при настоящих",
                "стену до " + walls.get(WeaponType.MISSILE) + " блоков", "«Шахеда-136» — в " + walls.get(WeaponType.DRONE) + " блок")) {
            assertTrue(ru.contains(s), "ru/packs.md lacks \"" + s + "\"");
        }
        // the blast-class table against the thresholds the game reads
        assertTrue(en.contains("| `incoming:nuke_fragile` | " + BlockResponse.FRAGILE_PSI + " psi"), "fragile psi");
        assertTrue(ru.contains("| `incoming:nuke_fragile` | " + String.valueOf(BlockResponse.FRAGILE_PSI).replace('.', ',') + " psi"), "fragile psi (ru)");
        assertTrue(en.contains("| `incoming:nuke_plants` | " + Math.round(BlockResponse.PLANT_PSI) + " psi"), "plant psi");
        assertTrue(ru.contains("| `incoming:nuke_plants` | " + Math.round(BlockResponse.PLANT_PSI) + " psi"), "plant psi (ru)");
    }

    /** The page with every run of whitespace as one space (the guide wraps lines anywhere). */
    private static String flat(Path page) throws Exception {
        return Files.readString(page).replaceAll("\\s+", " ");
    }
}
