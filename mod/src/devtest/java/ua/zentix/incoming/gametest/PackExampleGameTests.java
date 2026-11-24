package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.model.PulseIgnition;
import ua.zentix.incoming.nuclear.world.BlockResponse;
import ua.zentix.incoming.nuclear.world.Kindling;
import ua.zentix.incoming.registry.ModDataMaps;
import ua.zentix.incoming.registry.ModTags;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.FuelProbe;
import ua.zentix.incoming.warhead.Penetration;
import ua.zentix.incoming.warhead.Warheads;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The pack-maker examples of the guide ("For server owners and pack makers", {@link PackExamples}) loaded as a real
 * datapack ({@link DataPackFixture}) with a vanilla block standing in for the example mod's block, each with the effect
 * the guide claims; before the pack the block behaves as an unlisted block, after it again.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PackExampleGameTests {
    private static final Vec3 DOWN = new Vec3(0, -1, 0);

    private PackExampleGameTests() {}

    /**
     * Reinforced concrete ({@link PackExamples#PENETRATION}, packed mud standing in): the entry is read as written, the
     * B-2's bomb at game speed (235 m/s) goes through it as Young's concrete path (S 0.84, a full metre per block, about
     * 4 blocks) instead of the unlisted brick (S 1), and a cruise missile's charge breaches it with the reinforced-concrete
     * radius: 2 blocks over a void are breached, 3 hold.
     */
    @GameTest(template = "range", batch = "pack_penetration", skyAccess = true)
    public static void reinforcedConcreteEntry(GameTestHelper h) {
        BlockState block = Blocks.PACKED_MUD.defaultBlockState();
        Penetration.Body mop = BunkerBusterEntity.body();
        BlockPos column = new BlockPos(12, 30, 12);
        DataPackFixture.run(h, "incoming_test_penetration", Map.of("data/incoming/data_maps/block/penetration.json",
                        PackExamples.PENETRATION.replace(PackExamples.CONCRETE, "minecraft:packed_mud")),
                () -> {
                    h.assertTrue(ModDataMaps.Penetrability.find(block) == null, "packed mud has an entry before the pack");
                    fill(h, column.offset(-1, -28, -1), column.offset(1, 0, 1), block);
                    double unlisted = bombPath(h, column);
                    h.assertTrue(Math.abs(unlisted - Penetration.depth(mop, 1, 235)) < 1e-6, "unlisted block: bomb path " + m(unlisted));
                },
                () -> {
                    ModDataMaps.Penetrability p = ModDataMaps.Penetrability.find(block);
                    h.assertTrue(p != null && p.kind() == Penetration.Kind.CONCRETE && p.s() == 0.84 && p.thickness() == 1.0
                            && p.breach().equals(Optional.of(BreachModel.Material.REINFORCED)), "entry as loaded: " + p);
                    double path = bombPath(h, column), law = Penetration.depth(mop, 0.84, 235);
                    h.assertTrue(Math.abs(path - law) < 1e-6 && path > 3.5 && path < 4.5, "bomb path " + m(path) + " m, Young " + m(law));
                    double tnt = WeaponType.MISSILE.spec().charge(), r = BreachModel.Material.REINFORCED.radius(tnt, BreachModel.UNTAMPED);
                    S2C.Blast.Support two = slab(h, new BlockPos(16, 11, 44), 2, block), three = slab(h, new BlockPos(46, 11, 44), 3, block);
                    h.assertTrue(two != null && two.slab() > 0 && Math.abs(two.breach() - r) < 0.01,
                            "2 blocks: breach " + (two == null ? "none" : m(two.breach()) + " m, slab " + m(two.slab())) + ", law " + m(r));
                    h.assertTrue(three != null && three.slab() == 0, "3 blocks breached: " + three);
                },
                () -> h.assertTrue(ModDataMaps.Penetrability.find(block) == null, "entry left after the pack"));
    }

    /**
     * Sheet metal as a light structure ({@link PackExamples#BLOCK_CLASS}, an iron block standing in): its hardness puts it
     * at 12 psi; tagged {@code incoming:nuke_light} its threshold is 4 psi within the ±15 % spread, as both the nuclear
     * front ({@link BlockResponse#breaksAt(double, int)}, for a one-block element) and a conventional wave with a long
     * impulse read it. The before step fills the response cache at 12 psi: the reload must clear it.
     */
    @GameTest(template = "range", batch = "pack_block_class")
    public static void lightStructureTag(GameTestHelper h) {
        BlockState block = Blocks.IRON_BLOCK.defaultBlockState();
        DataPackFixture.run(h, "incoming_test_block_class", Map.of("data/incoming/tags/block/nuke_light.json",
                        PackExamples.BLOCK_CLASS.replace(PackExamples.SHEET_METAL, "minecraft:iron_block")),
                () -> h.assertTrue(BlockResponse.of(block).thresholdPsi() == 12f, "iron block before the pack: " + BlockResponse.of(block)),
                () -> {
                    BlockResponse r = BlockResponse.of(block);
                    h.assertTrue(block.is(ModTags.NUKE_LIGHT) && r.thresholdPsi() == 4f && r.kind() == BlockResponse.Kind.BREAK, "tagged: " + r);
                    for (int seed = 0; seed < 4096; seed++) {
                        h.assertTrue(r.breaksAt(4 * BlockResponse.JITTER_MAX + 0.01, seed), "holds above 4.6 psi, seed " + seed);
                        h.assertFalse(r.breaksAt(4 * BlockResponse.JITTER_MIN - 0.01, seed), "breaks below 3.4 psi, seed " + seed);
                        h.assertTrue(r.breaksAt(4 * BlockResponse.JITTER_MAX + 0.01, 1e6, seed), "conventional wave: holds above 4.6 psi");
                    }
                },
                () -> h.assertTrue(BlockResponse.of(block).thresholdPsi() == 12f, "class left after the pack: " + BlockResponse.of(block)));
    }

    /**
     * Straw as dry fuel ({@link PackExamples#IGNITION}, a target block standing in: flammable for vanilla fire, which the
     * fireball needs, and slow-burning, so untagged it counts as wood): the nuclear flash's fuel classes ({@link Kindling})
     * and the fuel fireball's ({@code Craters.fuel}, through {@code pulse_grass}) take it as dry grass.
     */
    @GameTest(template = "range", batch = "pack_ignition")
    public static void dryFuelTag(GameTestHelper h) {
        BlockState block = Blocks.TARGET.defaultBlockState();
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(32, 2, 32));
        DataPackFixture.run(h, "incoming_test_ignition", Map.of("data/incoming/tags/block/pulse_dry.json",
                        PackExamples.IGNITION.replace(PackExamples.STRAW, "minecraft:target")),
                () -> {
                    h.assertTrue(block.isFlammable(level, at, Direction.UP), "the stand-in is not flammable for vanilla fire");
                    h.assertTrue(Kindling.of(block) == null, "flash fuel before the pack: " + Kindling.of(block));
                    h.assertTrue(FuelProbe.fireball(level, at, block) == PulseIgnition.Fuel.SOLID, "fireball fuel before the pack: "
                            + FuelProbe.fireball(level, at, block));
                },
                () -> {
                    h.assertTrue(Kindling.of(block) == PulseIgnition.Fuel.GRASS, "flash fuel class: " + Kindling.of(block));
                    h.assertTrue(FuelProbe.fireball(level, at, block) == PulseIgnition.Fuel.GRASS, "fireball fuel class: "
                            + FuelProbe.fireball(level, at, block));
                },
                () -> h.assertTrue(Kindling.of(block) == null && FuelProbe.fireball(level, at, block) == PulseIgnition.Fuel.SOLID,
                        "fuel class left after the pack"));
    }

    // ---------------------------------------------------------------- helpers

    private static double bombPath(GameTestHelper h, BlockPos top) {
        Vec3 face = Vec3.atBottomCenterOf(h.absolutePos(top)).add(0, 1, 0);
        BunkerBusterEntity.Path path = BunkerBusterEntity.plan(h.getLevel(), face, DOWN, 235, 0);
        h.assertTrue(path.end() == BunkerBusterEntity.Path.End.REST, "bomb did not come to rest: " + path.end());
        return path.length();
    }

    /**
     * A {@code thickness}-block slab of {@code block} under {@code top} over a 3-block void, 8 blocks to each side: the
     * breach a cruise missile's nose on its top face would make (the explosion package's support, {@link Warheads#support}).
     */
    private static S2C.Blast.Support slab(GameTestHelper h, BlockPos top, int thickness, BlockState block) {
        int half = 8;
        fill(h, top.offset(-half - 1, -thickness + 1, -half - 1), top.offset(half + 1, 0, half + 1), block);
        fill(h, top.offset(-half, -thickness - 2, -half), top.offset(half, -thickness, half), Blocks.AIR.defaultBlockState());
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(top).above());
        return Warheads.support(level, at, WeaponType.MISSILE.spec().charge(), S2C.Blast.DOWN, null).orElse(null);
    }

    private static void fill(GameTestHelper h, BlockPos from, BlockPos to, BlockState state) {
        for (BlockPos p : BlockPos.betweenClosed(from, to)) h.getLevel().setBlock(h.absolutePos(p), state, Block.UPDATE_CLIENTS);
    }

    private static String m(double x) {
        return String.format(Locale.ROOT, "%.2f", x);
    }
}
