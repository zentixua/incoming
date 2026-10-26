package ua.zentix.incoming.gametest;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.EndRodBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.grid.BlackoutWorld;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.grid.ChunkLights;
import ua.zentix.incoming.grid.ChunkSaves;
import ua.zentix.incoming.grid.GridLights;
import ua.zentix.incoming.grid.Node;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.grid.SubstationBlock;
import ua.zentix.incoming.grid.block.Unlit;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.world.NuclearTickets;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.util.Palettes;
import ua.zentix.incoming.work.WorkScheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * blackout: the lamps go out as doubles and come back exactly the same, the cascade from the knocked-out substation goes under budget,
 *  unloaded chunks are not loaded (translated during loading), the doubles never get onto the disk.
 *  Each test is in its own batch: there is only one network per world. Before each one, the light, extinguished by other tests (nuclear tests
 *  also de-energize the area), returns.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GridGameTests {
    private static final BlockPos CENTER = new BlockPos(32, 12, 32);
    private static final TicketType<ChunkPos> HOLD = TicketType.create("incoming_test_grid_hold", Comparator.comparingLong(ChunkPos::toLong));

    private GridGameTests() {}

    /**
     * Disabling other checks - the lights are on in all blocks now, so that they don’t turn off the site in the middle of this
     *  (with the spread of the team, the site block would remain dark until 10 s - longer than the period of other checks).
     */
    private static void quiet(ServerLevel level) {
        Blackouts.restore(level, null, 0, 0);
        // substations of other checks remain in the world: a nuclear strike from this one would knock them out (batch order - by name hash)
        PowerGrid grid = PowerGrid.get(level);
        List<Integer> ids = new ArrayList<>();
        for (Node n : grid.nodes()) ids.add(n.id());
        ids.forEach(grid::removeNode);
    }

    /**
     * Each state of each lamp of the network back and forth is the same state; the double has no light. On the site -
     *  lamps in “inconvenient” states (hanging lantern under water, edge rod to the east, red stone lamp under
     *  signal, copper lamp under signal, light block 7 under water): after extinguishing and neighboring updates, the
     *  doubles are the same, after the return of light - exactly the same condition. Each pair for the world is one block, except for light
     * ({@link GridLights#inPlace}): the queue changes them directly in the section palette.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "grid_round_trip", skyAccess = true)
    public static void lampsRoundTripExactly(GameTestHelper h) {
        int pairs = 0;
        for (ModBlocks.UnlitPair pair : ModBlocks.UNLIT) {
            pairs++;
            Block lit = pair.lit().get(), unlit = pair.unlit().get();
            h.assertTrue(unlit instanceof Unlit u && u.lit() == lit, BuiltInRegistries.BLOCK.getKey(unlit) + " - not a double " + lit);
            for (BlockState s : lit.getStateDefinition().getPossibleStates()) {
                BlockState off = GridLights.unlit(s);
                h.assertTrue(off.is(unlit), s + " goes out in " + off);
                int emission = off.getLightEmission(h.getLevel(), h.absolutePos(CENTER));
                h.assertTrue(emission == 0, off + " shines " + emission);
                h.assertTrue(GridLights.lit(off) == s, s + " → " + off + " → " + GridLights.lit(off));
                h.assertTrue(off.getValues().equals(s.getValues()), "properties " + s + " and " + off + " different");
                // the queue changes lamps directly in the palette: for the world of steam - one block, except for light
                h.assertTrue(GridLights.inPlace(s) && GridLights.inPlace(off), s + " and " + off + " differ not only in light");
            }
        }
        h.assertTrue(pairs == 19, "network lamps: " + pairs);

        ServerLevel level = h.getLevel();
        // in a chunk in the middle of the platform (he’s all over it)
        ChunkPos mid = new ChunkPos(h.absolutePos(CENTER));
        Map<BlockPos, BlockState> placed = lampCases(level, new BlockPos(mid.getMinBlockX() + 3, h.absolutePos(CENTER).getY() + 1, mid.getMinBlockZ() + 3));
        List<ChunkPos> chunks = placed.keySet().stream().map(ChunkPos::new).distinct().toList();
        h.assertTrue(chunks.size() == 1, "lamps in " + chunks.size() + " chunks");
        // unit of work - no more than a given number of lamps, the rest - the next
        LevelChunk chunk = level.getChunk(chunks.get(0).x, chunks.get(0).z);
        ChunkLights.Pass first = ChunkLights.apply(level, chunk, true, 5);
        int dark = first.changed();
        h.assertTrue(dark == 5 && !first.done(), "per unit redeemed " + dark + " instead of 5 (chunk passed: " + first.done() + ")");
        // exactly as many lamps as are left from the stopping place - the chunk is passed through the same passage
        ChunkLights.Pass rest = ChunkLights.apply(level, chunk, true, placed.size() - 5, first.next());
        dark += rest.changed();
        h.assertTrue(rest.done(), "the chunk exactly with the lamp limit has not been completed to the end");
        h.assertTrue(ChunkLights.apply(level, chunk, true, 1).changed() == 0, "after going all the way there are lamps left");
        h.assertTrue(dark == placed.size(), "repaid " + dark + " from " + placed.size());
        for (var e : placed.entrySet()) {
            BlockState now = level.getBlockState(e.getKey());
            h.assertTrue(now == GridLights.unlit(e.getValue()), e.getValue() + " went out in " + now);
        }
        // neighbors are updated: the red stone lamp under the signal and the copper lamps remain extinguished as they were
        for (BlockPos p : placed.keySet()) level.updateNeighborsAt(p, Blocks.STONE);
        for (var e : placed.entrySet()) {
            BlockState now = level.getBlockState(e.getKey());
            h.assertTrue(now == GridLights.unlit(e.getValue()), "double " + e.getValue() + " changed from neighbors: " + now);
        }
        int light = 0;
        for (ChunkPos c : chunks) light += ChunkLights.apply(level, level.getChunk(c.x, c.z), false);
        h.assertTrue(light == placed.size(), "lit " + light + " from " + placed.size());
        for (var e : placed.entrySet()) {
            BlockState now = level.getBlockState(e.getKey());
            h.assertTrue(now == e.getValue(), "it was " + e.getValue() + ", it became " + now);
        }
        h.succeed();
    }

    /**
     * All 19 network lamps are in “inconvenient” states - a hanging lantern under water, toad lights along the axes, an edge rod
     *  to the east, a red stone and copper lamp under the signal, a light block 7 under water - in two rows along x
     * from {@code origin} (10 × 3 block). Returns the supplied: position → state.
     */
    private static Map<BlockPos, BlockState> lampCases(ServerLevel level, BlockPos origin) {
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        List<BlockState> states = new ArrayList<>(List.of(
                Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true).setValue(LanternBlock.WATERLOGGED, true),
                Blocks.SOUL_LANTERN.defaultBlockState(),
                Blocks.SEA_LANTERN.defaultBlockState(),
                Blocks.GLOWSTONE.defaultBlockState(),
                Blocks.SHROOMLIGHT.defaultBlockState(),
                Blocks.OCHRE_FROGLIGHT.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X),
                Blocks.VERDANT_FROGLIGHT.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z),
                Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState(),
                Blocks.END_ROD.defaultBlockState().setValue(EndRodBlock.FACING, Direction.EAST),
                Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true),
                Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 7).setValue(BlockStateProperties.WATERLOGGED, true)));
        Block[] bulbs = {Blocks.COPPER_BULB, Blocks.EXPOSED_COPPER_BULB, Blocks.WEATHERED_COPPER_BULB, Blocks.OXIDIZED_COPPER_BULB,
                Blocks.WAXED_COPPER_BULB, Blocks.WAXED_EXPOSED_COPPER_BULB, Blocks.WAXED_WEATHERED_COPPER_BULB, Blocks.WAXED_OXIDIZED_COPPER_BULB};
        for (int i = 0; i < bulbs.length; i++) {
            // after one - under the signal
            states.add(bulbs[i].defaultBlockState().setValue(CopperBulbBlock.LIT, true).setValue(CopperBulbBlock.POWERED, i % 2 == 1));
        }
        for (int i = 0; i < states.size(); i++) {
            BlockState s = states.get(i);
            BlockPos p = origin.offset(i % 10, 0, i / 10 * 3);
            // support for a hanging lantern, a signal for a red stone lamp and a copper “under the signal”
            boolean powered = s.is(Blocks.REDSTONE_LAMP) || s.hasProperty(CopperBulbBlock.POWERED) && s.getValue(CopperBulbBlock.POWERED);
            if (s.is(Blocks.LANTERN) || powered) level.setBlock(p.above(), (powered ? Blocks.REDSTONE_BLOCK : Blocks.STONE).defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(p, s, Block.UPDATE_ALL);
            placed.put(p, level.getBlockState(p));
        }
        for (var e : placed.entrySet()) {
            if (!GridLights.isLit(e.getValue())) throw new IllegalStateException("not lamp network: " + e.getValue() + " in " + e.getKey());
        }
        if (placed.size() != 19) throw new IllegalStateException("lamps " + placed.size());
        return placed;
    }

    /**
     * A red stone lamp and a copper lamp, for which the signal disappeared or appeared in the dark, after the return of light
     *  behaves as if a neighbor were updating: the first goes out, the second switches.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "grid_signal", skyAccess = true)
    public static void restoredLampsFollowSignalChangedInTheDark(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        // both are in a chunk in the middle of the platform (he is all on it)
        ChunkPos mid = new ChunkPos(h.absolutePos(CENTER));
        BlockPos lamp = new BlockPos(mid.getMinBlockX() + 4, h.absolutePos(CENTER).getY() + 1, mid.getMinBlockZ() + 4), bulb = lamp.east(4);
        level.setBlock(lamp.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true), Block.UPDATE_ALL);
        level.setBlock(bulb, Blocks.COPPER_BULB.defaultBlockState(), Block.UPDATE_ALL);
        h.assertTrue(level.getBlockState(bulb).is(Blocks.COPPER_BULB) && !level.getBlockState(bulb).getValue(CopperBulbBlock.LIT), "the copper lamp is wrong");
        LevelChunk chunk = level.getChunkAt(lamp);
        h.assertTrue(ChunkLights.apply(level, chunk, true) == 2, "the lights didn't go out");
        // in the dark: the red stone lamp lost its signal, the copper one appeared
        level.setBlock(lamp.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(bulb.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        h.assertTrue(GridLights.isUnlit(level.getBlockState(lamp)) && GridLights.isUnlit(level.getBlockState(bulb)), "doubles were replaced by neighbors");
        h.assertTrue(ChunkLights.apply(level, chunk, false) == 2, "the lamps didn't light up");
        h.startSequence()
                .thenWaitUntil(() -> {
                    BlockState l = level.getBlockState(lamp), b = level.getBlockState(bulb);
                    h.assertTrue(l.is(Blocks.REDSTONE_LAMP) && !l.getValue(RedstoneLampBlock.LIT), "lamp lights up without signal: " + l);
                    h.assertTrue(b.is(Blocks.COPPER_BULB) && b.getValue(CopperBulbBlock.LIT) && b.getValue(CopperBulbBlock.POWERED),
                            "the copper lamp did not notice the signal: " + b);
                })
                .thenSucceed();
    }

    /**
     * An extinguished lamp gives the same loot as a lamp (the double does not have its own table): the same items with a pickaxe and a
     *  pickaxe with silk touch, and if broken in the world, it drops.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "grid_loot", skyAccess = true)
    public static void brokenTwinDropsTheLampsLoot(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(CENTER);
        ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE), silk = new ItemStack(Items.DIAMOND_PICKAXE);
        silk.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH), 1);
        for (ModBlocks.UnlitPair pair : ModBlocks.UNLIT) {
            BlockState lamp = pair.lit().get().defaultBlockState(), twin = GridLights.unlit(lamp);
            h.assertTrue(twin.getBlock().getLootTable() == lamp.getBlock().getLootTable(),
                    "loot table " + twin + ": " + twin.getBlock().getLootTable().location());
            for (ItemStack tool : List.of(pickaxe, silk)) {
                var want = Block.getDrops(lamp, level, at, null, null, tool).stream().map(ItemStack::getItem).collect(Collectors.toSet());
                var got = Block.getDrops(twin, level, at, null, null, tool).stream().map(ItemStack::getItem).collect(Collectors.toSet());
                h.assertTrue(want.equals(got), twin + " gives " + got + ", lamp - " + want + " (" + tool + ")");
            }
        }
        Map<Block, Item> drops = Map.of(Blocks.LANTERN, Items.LANTERN, Blocks.OCHRE_FROGLIGHT, Items.OCHRE_FROGLIGHT,
                Blocks.REDSTONE_LAMP, Items.REDSTONE_LAMP, Blocks.WAXED_COPPER_BULB, Items.WAXED_COPPER_BULB);
        int i = 0;
        for (var e : drops.entrySet()) {
            BlockPos p = new BlockPos(16 + 8 * i++, 13, 16);
            h.setBlock(p, GridLights.unlit(e.getKey().defaultBlockState()));
            level.destroyBlock(h.absolutePos(p), true);
            h.assertItemEntityPresent(e.getValue(), p, 2);
        }
        h.succeed();
    }

    /**
     * The double is not from blackout in the bright quarter again a lamp: placed in the world (piston, craft) - immediately, but in the chunk
     *  from the disk (saved with the double) - still in the palette, before the chunk becomes part of the world.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "grid_stray", skyAccess = true)
    public static void strayTwinRelightsInLitDistrict(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        BlockPos moved = new BlockPos(20, 13, 20), legacy = new BlockPos(24, 13, 20);
        BlockState froglight = Blocks.VERDANT_FROGLIGHT.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        h.setBlock(moved, GridLights.unlit(froglight));
        LevelChunk chunk = level.getChunkAt(h.absolutePos(legacy));
        BlockPos abs = h.absolutePos(legacy);
        chunk.getSection(chunk.getSectionIndex(abs.getY())).setBlockState(abs.getX() & 15, abs.getY() & 15, abs.getZ() & 15,
                GridLights.unlit(Blocks.GLOWSTONE.defaultBlockState()), false);
        ChunkSaves.onLoad(new ChunkDataEvent.Load(chunk, new CompoundTag(), ChunkType.LEVELCHUNK));
        h.assertTrue(level.getBlockState(abs).is(Blocks.GLOWSTONE), "the double from the save remains: " + level.getBlockState(abs));
        h.startSequence()
                .thenWaitUntil(() -> h.assertBlockState(moved, s -> s == froglight, () -> "the shifted twin did not light up: " + h.getBlockState(moved)))
                .thenSucceed();
    }

    /**
     * craft Sable, collected in the dark quarter, takes the extinguished lamps to its plot - not a chunk of the world: the blackout queue
     *  does not see it, and saves it Sable with its codec, past {@code ChunkDataEvent.Save}. The craft has its own
     * power: doubles in the plot again lamps - in the same tick as the assembly (neighbor update), before any save.
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "grid_sable", skyAccess = true)
    public static void twinsCarriedIntoSableShipRelight(GameTestHelper h) {
        if (!ModList.get().isLoaded("sable")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        var server = level.getServer();
        quiet(level);
        BlockPos from = CENTER.offset(-2, 3, -2), to = from.offset(4, 0, 4);
        BlockPos sea = from.offset(1, 1, 1), lantern = from.offset(3, 1, 3);
        // the block is dark at first: the doubles placed in it do not light up on their own
        Blackouts.blackout(level, Vec3.atCenterOf(h.absolutePos(CENTER)), 200, 1000, -1);
        BlockPos a = h.absolutePos(from), b = h.absolutePos(lantern);
        Vec3 craft = Vec3.atCenterOf(h.absolutePos(from.offset(2, 0, 2)));
        SubLevelAccess[] sub = new SubLevelAccess[1];
        int[] assembled = {0};
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(PowerGrid.get(level).dark(a.getX() >> 4, a.getZ() >> 4, level.getGameTime())
                        && PowerGrid.get(level).dark(b.getX() >> 4, b.getZ() >> 4, level.getGameTime()), "the quarter is still bright"))
                .thenExecute(() -> {
                    BlockPos.betweenClosed(from, to).forEach(p -> h.setBlock(p, Blocks.OAK_PLANKS));
                    h.setBlock(sea, GridLights.unlit(Blocks.SEA_LANTERN.defaultBlockState()));
                    h.setBlock(lantern, GridLights.unlit(Blocks.LANTERN.defaultBlockState()));
                })
                .thenIdle(5)
                // immediately after plot traversal: until the next traversal - PLOT_SCAN tick, only the assembly can ignite doubles
                .thenWaitUntil(() -> h.assertTrue(server.getTickCount() % BlackoutWorld.PLOT_SCAN == 1, "wait for the tick after traversing the plot"))
                .thenExecute(() -> {
                    h.assertTrue(GridLights.isUnlit(h.getBlockState(sea)), "the double in the dark quarter lit up: " + h.getBlockState(sea));
                    assembled[0] = server.getTickCount();
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                            String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d", Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                                    Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
                })
                .thenWaitUntil(() -> {
                    List<SubLevelAccess> near = SubLevels.near(level, craft, 8);
                    h.assertFalse(near.isEmpty(), "craft is not assembled");
                    sub[0] = near.getFirst();
                })
                .thenExecute(() -> {
                    h.assertTrue(server.getTickCount() - assembled[0] < BlackoutWorld.PLOT_SCAN - 1, "craft was found after traversing plot - there is nothing to check");
                    // there was no plot bypass yet: the doubles were lit from the assembly (neighbor update), before any saving
                    int[] lamps = plotLamps(level, sub[0]);
                    h.assertTrue(lamps[0] == 2 && lamps[1] == 0, "before traversing plot in plot is lit " + lamps[0] + ", extinguished " + lamps[1]);
                })
                .thenIdle(BlackoutWorld.PLOT_SCAN + 1)
                .thenExecute(() -> {
                    int[] lamps = plotLamps(level, sub[0]);
                    h.assertTrue(lamps[0] == 2 && lamps[1] == 0, "lit in plot craft " + lamps[0] + ", extinguished " + lamps[1]);
                })
                .thenExecute(() -> Blackouts.restore(level, null, 0))
                .thenSucceed();
    }

    /** Lamps around the craft plot center: {lit, extinguished}. */
    private static int[] plotLamps(ServerLevel level, SubLevelAccess sub) {
        BlockPos c = BlockPos.containing(SubLevels.toPlot(sub, SubLevels.center(sub)));
        int lit = 0, dark = 0;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-6, -6, -6), c.offset(6, 6, 6))) {
            BlockState st = level.getBlockState(p);
            if (st.is(Blocks.SEA_LANTERN) || st.is(Blocks.LANTERN)) lit++;
            if (GridLights.isUnlit(st)) dark++;
        }
        return new int[]{lit, dark};
    }

    /**
     * Substation - network node; an explosion nearby (without destruction) knocks it out: it “burns out”, the site lights go out, not a single
     *  tick goes over the budget (counting clocks: unit of work = 1 ms). The light returns on command, the substation works again, the lamps are the same.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "grid_cascade", skyAccess = true)
    public static void struckSubstationBlacksOutAndRestores(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        WorkClock clock = WorkClock.counting(1_000_000L);
        Blackouts.useClock(level.getServer(), clock);
        h.setBlock(CENTER, ModBlocks.SUBSTATION.get());
        Node node = PowerGrid.get(level).nodeAt(h.absolutePos(CENTER));
        h.assertTrue(node != null && node.block(), "the substation did not become a network node");
        // lamps - in chunks whose neighbors are also on the site: the cascade comes to them without waiting for neighbors
        List<BlockPos> lamps = List.of(new BlockPos(18, 12, 18), new BlockPos(45, 12, 45), new BlockPos(32, 12, 20), new BlockPos(20, 12, 44));
        for (BlockPos p : lamps) h.setBlock(p, Blocks.LANTERN);
        int budgetMs = (int) Math.round(WorkScheduler.gridCapNanos() / 1e6);
        h.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    Vec3 c = Vec3.atCenterOf(h.absolutePos(CENTER.east(3)));
                    // a gust of wind charge (radius 1.2, “hit blocks”) is not an explosion for a substation
                    level.explode(null, null, null, c.x, c.y, c.z, 1.2f, false, Level.ExplosionInteraction.TRIGGER,
                            ParticleTypes.GUST_EMITTER_SMALL, ParticleTypes.GUST_EMITTER_LARGE, SoundEvents.WIND_CHARGE_BURST);
                    h.assertTrue(PowerGrid.get(level).downOutage(node.id(), level.getGameTime()).isEmpty(), "a wind charge knocked out a substation");
                    level.explode(null, c.x, c.y, c.z, 2f, Level.ExplosionInteraction.NONE);
                    h.assertTrue(PowerGrid.get(level).downOutage(node.id(), level.getGameTime()).isPresent(), "the explosion did not knock out the substation");
                })
                .thenWaitUntil(() -> {
                    h.assertFalse(h.getBlockState(CENTER).getValue(SubstationBlock.POWERED), "the substation is knocked out, but “under current”");
                    for (BlockPos p : lamps) h.assertTrue(GridLights.isUnlit(h.getBlockState(p)), "lantern " + p + " lit");
                    h.assertTrue(level.getChunkAt(h.absolutePos(lamps.get(0))).hasData(ModAttachments.GRID_DARK), "chunk is not marked dark");
                })
                .thenExecute(() -> {
                    h.assertTrue(clock.maxUnitsPerTick() <= budgetMs, "per tick " + clock.maxUnitsPerTick() + " units by 1 ms with budget " + budgetMs + " ms");
                    h.assertTrue(clock.ticksWorked() > 1, "all the work fit into one tick - the budget has not been verified");
                    h.assertTrue(Blackouts.restore(level, Vec3.atCenterOf(h.absolutePos(CENTER)), 8) >= 1, "there is nothing to return");
                })
                .thenWaitUntil(() -> {
                    for (BlockPos p : lamps) h.assertBlockState(p, s -> s == Blocks.LANTERN.defaultBlockState(), () -> "lantern " + p + " didn't light up the same");
                    h.assertTrue(h.getBlockState(CENTER).getValue(SubstationBlock.POWERED), "the substation did not work");
                    h.assertFalse(level.getChunkAt(h.absolutePos(lamps.get(0))).hasData(ModAttachments.GRID_DARK), "the dark chunk mark remains");
                })
                .thenExecute(() -> Blackouts.useClock(level.getServer(), Blackouts.newClock()))
                .thenSucceed();
    }

    /**
     * A nuclear strike extinguishes its radius and knocks out the substations in it: the substation is not “energized” (does not hum, sparks) until the light is returned; after returning, the lamps are the same, the substation is working.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "grid_nuke", skyAccess = true)
    public static void nukeBlacksOutAndKnocksOutSubstations(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        h.setBlock(CENTER, ModBlocks.SUBSTATION.get());
        Node node = PowerGrid.get(level).nodeAt(h.absolutePos(CENTER));
        h.assertTrue(node != null && node.block(), "the substation did not become a network node");
        List<BlockPos> lamps = List.of(new BlockPos(18, 12, 18), new BlockPos(45, 12, 45));
        for (BlockPos p : lamps) h.setBlock(p, Blocks.LANTERN);
        // air blast away from the substation (as in nuclear inspections: 15 kt, scale 0.1); the explosion itself is not needed here
        BlockPos g = h.absolutePos(CENTER.east(8));
        Detonation d = new Detonation(NuclearGameTests.IDS.incrementAndGet(), new Vec3(g.getX() + 0.5, g.getY() + 300, g.getZ() + 0.5), g.getY(),
                15, false, level.getGameTime(), 0, 0, 20_000, 7, 0.1f, false, Detonation.OLD_WAVE);
        h.assertTrue(d.radiusMax() > 64, "detonation radius " + d.radiusMax() + " does not cover the area");
        Blackouts.nuke(level, d);
        h.assertTrue(PowerGrid.get(level).downOutage(node.id(), level.getGameTime()).isPresent(), "nuclear strike did not knock out the substation");
        h.startSequence()
                .thenWaitUntil(() -> {
                    h.assertFalse(h.getBlockState(CENTER).getValue(SubstationBlock.POWERED), "substation within the blast radius “under current”");
                    for (BlockPos p : lamps) h.assertTrue(GridLights.isUnlit(h.getBlockState(p)), "lantern " + p + " lit");
                })
                // explosion and a substation knocked out by it - two shutdowns (no others: quiet)
                .thenExecute(() -> h.assertTrue(Blackouts.restore(level, null, 0) == 2, "returned not two shutdowns"))
                .thenWaitUntil(() -> {
                    for (BlockPos p : lamps) h.assertBlockState(p, s -> s == Blocks.LANTERN.defaultBlockState(), () -> "lantern " + p + " didn't light up the same");
                    h.assertTrue(h.getBlockState(CENTER).getValue(SubstationBlock.POWERED), "the substation did not work");
                })
                .thenSucceed();
    }

    /**
     * City shutdown radius: the city corner is ~373 blocks from the center, and the seed of the quarter (crater cell) lies up to ~60
     *  blocks further than its chunks; with 400, the corner chunk in some places on the site remained outside the shutdown.
     */
    private static final int CITY_RADIUS = 480;

    /**
     * City: 1024 loaded chunks of 8 lamps. The unit of work of the queue is {@code UNIT_WORK} of work in as many chunks as
     *  will fit (the lamp in the palette is 1, the passage of sections of a chunk with lamps is {@code SCAN_COST}), and the chunk ends
     *  with the same passage that transferred its last lamp: on the counting clock and extinguishing and returning light go no less than
     * 1.2 chunk per unit of work (with a full queue - 2, but the cascade goes in quarters, and the last unit of the quarter
     *  is incomplete; with a separate idle passage at the end of the chunk - half a chunk). Then the same cascade on a real blackout clock - log chunks for tick and the longest tick, and time
     *  for the lamp (not a check: the wall time on CI floats).
     */
    @GameTest(template = "range", timeoutTicks = 4800, batch = "grid_city", skyAccess = true)
    public static void cityQueueRunsManyChunksPerTick(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos mid = new ChunkPos(h.absolutePos(CENTER));
        int y = h.absolutePos(CENTER).getY() + 1;
        List<ChunkPos> held = new ArrayList<>();
        List<BlockPos> lamps = new ArrayList<>();
        for (int dx = -16; dx < 16; dx++) {
            for (int dz = -16; dz < 16; dz++) {
                ChunkPos p = new ChunkPos(mid.x + dx, mid.z + dz);
                chunks.addRegionTicket(HOLD, p, 1, p);
                held.add(p);
            }
        }
        for (ChunkPos p : held) {
            level.getChunk(p.x, p.z);
            for (int i = 0; i < 8; i++) {
                BlockPos at = new BlockPos(p.getMinBlockX() + 1 + i, y, p.getMinBlockZ() + 1 + i);
                level.setBlock(at, Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS);
                lamps.add(at);
            }
        }
        // time per lamp: one setBlock and the entire chunk through ChunkLights.apply (with sections passing through) - according to 128 chunks
        long t0 = System.nanoTime();
        for (int i = 0; i < 128 * 8; i++) level.setBlock(lamps.get(i), GridLights.unlit(Blocks.SEA_LANTERN.defaultBlockState()), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        long set = System.nanoTime() - t0;
        for (int i = 0; i < 128 * 8; i++) level.setBlock(lamps.get(i), Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        t0 = System.nanoTime();
        int n = 0;
        for (int i = 128; i < 256; i++) n += ChunkLights.apply(level, level.getChunk(held.get(i).x, held.get(i).z), true);
        long apply = System.nanoTime() - t0;
        for (int i = 128; i < 256; i++) ChunkLights.apply(level, level.getChunk(held.get(i).x, held.get(i).z), false);
        // and lamps from neighboring areas, if they are in these chunks
        h.assertTrue(n >= 128 * 8, "repaid " + n);
        t0 = System.nanoTime();
        int m = 0;
        for (int i = 256; i < 384; i++) {
            m += ChunkLights.applyInPlace(level, level.getChunk(held.get(i).x, held.get(i).z), true, Integer.MAX_VALUE, 0, true, new LongArrayList()).changed();
        }
        long inPlace = System.nanoTime() - t0;
        for (int i = 256; i < 384; i++) ChunkLights.apply(level, level.getChunk(held.get(i).x, held.get(i).z), false);
        Incoming.LOG.info("GRIDBENCH lamp: setBlock {} μs, ChunkLights.apply {} μs, in the palette {} μs (with passage of sections, according to {} and {} lamps)",
                String.format(Locale.ROOT, "%.1f", set / 1e3 / (128 * 8)), String.format(Locale.ROOT, "%.1f", apply / 1e3 / n),
                String.format(Locale.ROOT, "%.1f", inPlace / 1e3 / m), n, m);

        Vec3 at = Vec3.atCenterOf(h.absolutePos(CENTER));
        WorkClock counting = WorkClock.counting(1_000_000L);
        WorkClock[] real = {null};
        int[] ticks = new int[4], units = new int[2];
        long[] maxTick = new long[2];
        Blackouts.useClock(level.getServer(), counting);
        // work counters for /incoming grid status (from loading the world: window in 100 tick server GameTest on CI
        //  passes entirely during the clearing time, and the previous window is empty) - units, lamps, passed chunks
        long[] before = BlackoutWorld.get(level).totals();
        Blackouts.blackout(level, at, CITY_RADIUS, 1000, -1);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "extinguishing is in progress"))
                .thenExecute(() -> {
                    for (BlockPos p : lamps) h.assertTrue(GridLights.isUnlit(level.getBlockState(p)), "lamp " + p + " lit");
                    long[] after = BlackoutWorld.get(level).totals();
                    for (BlackoutWorld.Work w : List.of(BlackoutWorld.Work.UNIT, BlackoutWorld.Work.LAMPS, BlackoutWorld.Work.PASS_DONE)) {
                        h.assertTrue(after[w.ordinal()] > before[w.ordinal()], "counter " + w + " did not rise for cancellation");
                    }
                    ticks[0] = counting.ticksWorked();
                    units[0] = counting.units();
                    Blackouts.restore(level, null, 0, 0);
                })
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "refund is in progress"))
                .thenExecute(() -> {
                    ticks[1] = counting.ticksWorked() - ticks[0];
                    units[1] = counting.units() - units[0];
                    assertAllLit(h, level, lamps, mid);
                    // on a real blackout watch - just like in the game
                    real[0] = Blackouts.newClock();
                    Blackouts.useClock(level.getServer(), real[0]);
                    Blackouts.blackout(level, at, CITY_RADIUS, 1000, -1);
                })
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "extinguishing is in progress"))
                .thenExecute(() -> {
                    // the step above fell (test verification): without a clock - do not measure anything further, do not drop the server
                    h.assertTrue(real[0] != null, "the real clock is not set - the last step has fallen");
                    ticks[2] = real[0].ticksWorked();
                    maxTick[0] = real[0].maxTickNanos();
                    real[0] = Blackouts.newClock();
                    Blackouts.useClock(level.getServer(), real[0]);
                    Blackouts.restore(level, null, 0, 0);
                })
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "refund is in progress"))
                .thenExecute(() -> {
                    h.assertTrue(real[0] != null, "the real clock is not set - the last step has fallen");
                    ticks[3] = real[0].ticksWorked();
                    maxTick[1] = real[0].maxTickNanos();
                    Blackouts.useClock(level.getServer(), Blackouts.newClock());
                    held.forEach(p -> chunks.removeRegionTicket(HOLD, p, 1, p));
                    assertAllLit(h, level, lamps, mid);
                    int budget = (int) Math.round(WorkScheduler.gridCapNanos() / 1e6);
                    // by one, and not by tick: the cascade goes through quarters with a shift, and in the tick part the queue empties ahead of schedule
                    double off = (double) held.size() / units[0], on = (double) held.size() / units[1];
                    Incoming.LOG.info("GRIDBENCH counting clock (up to {} units per tick): clearing {} chunks per unit ({} units, {} tick), returning {} ({} units, {} tick)",
                            counting.maxUnitsPerTick(), String.format(Locale.ROOT, "%.2f", off), units[0], ticks[0], String.format(Locale.ROOT, "%.2f", on), units[1], ticks[1]);
                    Incoming.LOG.info("GRIDBENCH real clock (budget {} ms): clearing {} chunks/tick ({} tick, longest {} ms), returning {} chunks/tick ({} tick, longest {} ms)",
                            budget, String.format(Locale.ROOT, "%.1f", (double) held.size() / ticks[2]), ticks[2], String.format(Locale.ROOT, "%.2f", maxTick[0] / 1e6),
                            String.format(Locale.ROOT, "%.1f", (double) held.size() / ticks[3]), ticks[3], String.format(Locale.ROOT, "%.2f", maxTick[1] / 1e6));
                    h.assertTrue(off >= 1.2 && on >= 1.2, "chunks per unit of work: quenching " + off + ", return " + on + " (you need at least 1.2)");
                })
                .thenSucceed();
    }

    /** All the city lamps are the same again (the first one that didn’t light up goes into the log with its chunk). */
    private static void assertAllLit(GameTestHelper h, ServerLevel level, List<BlockPos> lamps, ChunkPos mid) {
        List<BlockPos> bad = lamps.stream().filter(p -> level.getBlockState(p) != Blocks.SEA_LANTERN.defaultBlockState()).toList();
        if (bad.isEmpty()) return;
        BlockPos b = bad.get(0);
        ChunkPos c = new ChunkPos(b);
        Incoming.LOG.info("GRIDDEBUG did not light up {}: first {} {} chunk {} ({} {}), dark {}, affected by {}", bad.size(), b, level.getBlockState(b), c,
                c.x - mid.x, c.z - mid.z, PowerGrid.get(level).dark(c.x, c.z, level.getGameTime()), PowerGrid.get(level).covered(c.x, c.z));
        h.fail(bad.size() + " the lamps did not light up as before, the first " + b);
    }

    /**
     * Chunk filled with light: 32 768 blocks of light and lantern (like invisible blocks of light of city maps, up to 7400 per chunk) —
     *  dozens of units of work. The queue takes him through passages from where he stopped and brings him to the end (the wrong end of the chunk
     *  would have left the lamps on). Then the chunk is unloaded in the middle of extinguishing (one per tick) and loaded again: in the dark
     *  quarter - not a single burning lamp; all the light returns. At the pace of the game: the upload is waiting for the chunk to be recorded in the background, and the
     *  server GameTest would pass the tower without pauses earlier.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "grid_tower", skyAccess = true)
    public static void lampTowerConvertsAcrossPassesAndUnload(GameTestHelper h) {
        StrikeGameTests.gameSpeed(h);
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos far = new ChunkPos(h.absolutePos(CENTER.west(480)));
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        BlockState light = Blocks.LIGHT.defaultBlockState(), lantern = Blocks.SEA_LANTERN.defaultBlockState();
        WorkClock fast = WorkClock.counting(1_000_000L);
        // 3 ms unit with budget 4 ms - one unit per tick: tower extinguishing takes tens of ticks
        WorkClock slow = WorkClock.counting(3_000_000L);
        long[] lampsAtStart = {0};
        hold(level, far);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "chunk is loading"))
                .thenExecute(() -> {
                    placed.putAll(fill(level, far, 128, light, lantern));
                    Blackouts.useClock(level.getServer(), fast);
                    Blackouts.blackout(level, Vec3.atCenterOf(placed.keySet().iterator().next()), 200, 1000, -1);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(BlackoutWorld.get(level).idle(), "extinguishing is in progress");
                    assertLamps(h, level, placed, true);
                })
                .thenExecute(() -> {
                    h.assertTrue(fast.units() >= placed.size() / BlackoutWorld.UNIT_WORK, "the tower went out in " + fast.units() + " units - there were no passages");
                    Blackouts.restore(level, null, 0, 0);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(BlackoutWorld.get(level).idle(), "refund is in progress");
                    assertLamps(h, level, placed, false);
                })
                .thenExecute(() -> {
                    lampsAtStart[0] = BlackoutWorld.get(level).totals()[BlackoutWorld.Work.LAMPS.ordinal()];
                    Blackouts.useClock(level.getServer(), slow);
                    Blackouts.blackout(level, Vec3.atCenterOf(placed.keySet().iterator().next()), 200, 1000, -1);
                })
                // the first lights went out - the chunk leaves memory in the middle of translation
                .thenWaitUntil(() -> h.assertTrue(placed.keySet().stream().anyMatch(p -> GridLights.isUnlit(level.getBlockState(p))), "extinguishing has not started"))
                .thenExecute(() -> chunks.removeRegionTicket(HOLD, far, 2, far))
                .thenWaitUntil(() -> unloaded(h, level, far))
                .thenExecute(() -> {
                    long lamps = BlackoutWorld.get(level).totals()[BlackoutWorld.Work.LAMPS.ordinal()] - lampsAtStart[0];
                    h.assertTrue(lamps < placed.size(), "the tower went out before unloading (" + lamps + " lamps) - there was no unloading in the middle of the aisle");
                    hold(level, far);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "chunk is loading");
                    h.assertTrue(BlackoutWorld.get(level).idle(), "extinguishing is in progress");
                    assertLamps(h, level, placed, true);
                })
                .thenExecute(() -> Blackouts.restore(level, null, 0, 0))
                .thenWaitUntil(() -> {
                    h.assertTrue(BlackoutWorld.get(level).idle(), "refund is in progress");
                    assertLamps(h, level, placed, false);
                })
                .thenExecute(() -> {
                    Blackouts.useClock(level.getServer(), Blackouts.newClock());
                    chunks.removeRegionTicket(HOLD, far, 2, far);
                })
                .thenSucceed();
    }

    /**
     * Four chunks of 7168 light blocks (denser than the densest chunks of city maps): lamps change in the palette, and the
     *  queue transfers them no more expensive than {@code UNIT_WORK} lamps per unit of work (via {@code setBlock} - 32),
     *  both ways to the end; the light in the middle of the cube goes out and comes back.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "grid_dense", skyAccess = true)
    public static void denseLightChunksConvertInBoundedUnits(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos base = new ChunkPos(h.absolutePos(CENTER.east(480).south(480)));
        List<ChunkPos> held = List.of(base, new ChunkPos(base.x + 1, base.z), new ChunkPos(base.x, base.z + 1), new ChunkPos(base.x + 1, base.z + 1));
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        BlockState light = Blocks.LIGHT.defaultBlockState();
        WorkClock clock = WorkClock.counting(1_000_000L);
        BlockPos[] mid = {null};
        int[] units = new int[2];
        long[] before = new long[BlackoutWorld.Work.values().length];
        held.forEach(p -> hold(level, p));
        h.startSequence()
                .thenWaitUntil(() -> held.forEach(p -> h.assertTrue(chunks.getChunkNow(p.x, p.z) != null, "chunk " + p + " loading")))
                .thenExecute(() -> {
                    // one height for all four chunks - a solid cube, its middle is far from the edge
                    int y = held.stream().mapToInt(p -> caseOrigin(level, p).getY()).max().orElseThrow();
                    for (ChunkPos p : held) placed.putAll(fill(level, p, y, 28, light, light));
                    mid[0] = new BlockPos(base.getMaxBlockX(), y + 14, base.getMaxBlockZ());
                    Blackouts.useClock(level.getServer(), clock);
                })
                .thenWaitUntil(() -> assertBlockLight(h, level, mid[0], 15))
                .thenExecute(() -> {
                    System.arraycopy(BlackoutWorld.get(level).totals(), 0, before, 0, before.length);
                    Blackouts.blackout(level, Vec3.atCenterOf(mid[0]), 200, 1000, -1);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(BlackoutWorld.get(level).idle(), "extinguishing is in progress");
                    assertLamps(h, level, placed, true);
                })
                .thenWaitUntil(() -> assertBlockLight(h, level, mid[0], 0))
                .thenExecute(() -> {
                    units[0] = (int) (BlackoutWorld.get(level).totals()[BlackoutWorld.Work.UNIT.ordinal()] - before[BlackoutWorld.Work.UNIT.ordinal()]);
                    Blackouts.restore(level, null, 0, 0);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(BlackoutWorld.get(level).idle(), "refund is in progress");
                    assertLamps(h, level, placed, false);
                })
                .thenWaitUntil(() -> assertBlockLight(h, level, mid[0], 15))
                .thenExecute(() -> {
                    units[1] = (int) (BlackoutWorld.get(level).totals()[BlackoutWorld.Work.UNIT.ordinal()] - before[BlackoutWorld.Work.UNIT.ordinal()]) - units[0];
                    Blackouts.useClock(level.getServer(), Blackouts.newClock());
                    held.forEach(p -> chunks.removeRegionTicket(HOLD, p, 2, p));
                    // conversion units (without parsing the cascade into rows): one per UNIT_WORK lamps and a reserve at the ends of
                    //  passages and empty chunks of the area (through setBlock through 32 lamps - 900 units)
                    int most = placed.size() * 115 / 100 / BlackoutWorld.UNIT_WORK + 2 * held.size();
                    long[] t = BlackoutWorld.get(level).totals();
                    StringBuilder kinds = new StringBuilder();
                    for (BlackoutWorld.Work w : BlackoutWorld.Work.values()) kinds.append(' ').append(w).append('=').append(t[w.ordinal()] - before[w.ordinal()]);
                    Incoming.LOG.info("GRIDBENCH dense chunks: {} light blocks, blanking {} units, return {} (no more than {}); {}", placed.size(), units[0], units[1], most, kinds);
                    h.assertTrue(units[0] <= most && units[1] <= most, "units of work: extinguishing " + units[0] + ", return " + units[1] + " (no more " + most + ")");
                })
                .thenSucceed();
    }

    /** A column of blocks from the corner of a chunk with a height of {@code height} above its relief: every ninth one is {@code other}, the rest are {@code block}. */
    private static Map<BlockPos, BlockState> fill(ServerLevel level, ChunkPos p, int height, BlockState block, BlockState other) {
        return fill(level, p, caseOrigin(level, p).getY(), height, block, other);
    }

    private static Map<BlockPos, BlockState> fill(ServerLevel level, ChunkPos p, int y0, int height, BlockState block, BlockState other) {
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockPos at = new BlockPos(p.getMinBlockX() + x, y0 + y, p.getMinBlockZ() + z);
                    BlockState s = (x + y + z) % 9 == 0 ? other : block;
                    level.setBlock(at, s, Block.UPDATE_CLIENTS);
                    placed.put(at, s);
                }
            }
        }
        return placed;
    }

    /**
     * Reading a chunk from the disk is not for the world (LOD, maps: other mods call {@code ChunkSerializer.read}, including in their
     *  streams) does not touch the blackout queue: a copy of an already loaded chunk in the server thread and reading in someone else's thread does not
     *  put light from the disk on the world's chunk (otherwise the queue is over and over again would pass his lamps) and do not write in her maps.
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "grid_foreign_read", skyAccess = true)
    public static void foreignChunkReadsLeaveQueueAlone(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos far = new ChunkPos(h.absolutePos(CENTER.south(480)));
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        BlockState lantern = Blocks.SEA_LANTERN.defaultBlockState();
        RegionStorageInfo info = new RegionStorageInfo("gametest", level.dimension(), "chunk");
        hold(level, far);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "chunk is loading"))
                .thenExecute(() -> {
                    BlockPos origin = caseOrigin(level, far);
                    for (int i = 0; i < 40; i++) placed.put(origin.offset(i % 10, i / 10, 0), lantern);
                    placed.keySet().forEach(p -> level.setBlock(p, lantern, Block.UPDATE_ALL));
                    Blackouts.blackout(level, Vec3.atCenterOf(origin), 200, 1000, -1);
                })
                .thenWaitUntil(() -> {
                    assertLamps(h, level, placed, true);
                    h.assertFalse(BlackoutWorld.get(level).busy(far), "queue is busy: " + BlackoutWorld.get(level).busyState());
                })
                .thenExecute(() -> {
                    LevelChunk chunk = level.getChunk(far.x, far.z);
                    // like on the disc: lamps instead of doubles
                    CompoundTag tag = ChunkSerializer.write(level, chunk);
                    NeoForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk, level, tag));
                    h.assertFalse(tag.toString().contains(Incoming.MOD_ID + ":unlit"), "in the doubles tag");
                    long copies = ChunkSaves.foreignReads(ChunkSaves.COPY), offThread = ChunkSaves.foreignReads(ChunkSaves.OFF_THREAD);
                    // copy in server stream
                    LevelChunk copy = ((ImposterProtoChunk) ChunkSerializer.read(level, level.getPoiManager(), info, far, tag)).getWrapped();
                    h.assertTrue(ChunkSaves.foreignReads(ChunkSaves.COPY) == copies + 1, "copy of the world chunk is not recognized");
                    h.assertFalse(BlackoutWorld.get(level).busy(far), "a copy of the world chunk put its lamps in the queue: " + BlackoutWorld.get(level).busyState());
                    // the same event in someone else's thread (like LOD)
                    Thread reader = new Thread(() -> NeoForge.EVENT_BUS.post(new ChunkDataEvent.Load(copy, tag, ChunkType.LEVELCHUNK)), "gametest-foreign-reader");
                    reader.start();
                    try {
                        reader.join(10_000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    h.assertTrue(ChunkSaves.foreignReads(ChunkSaves.OFF_THREAD) == offThread + 1, "read in foreign thread not recognized");
                    h.assertFalse(BlackoutWorld.get(level).busy(far), "reading in someone else's thread queued lamps: " + BlackoutWorld.get(level).busyState());
                    assertLamps(h, level, placed, true);
                    Blackouts.restore(level, null, 0, 0);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(BlackoutWorld.get(level).idle(), "refund is in progress");
                    assertLamps(h, level, placed, false);
                })
                .thenExecute(() -> chunks.removeRegionTicket(HOLD, far, 2, far))
                .thenSucceed();
    }

    /**
     * Chunks without lamps do not slow down the queue: in a pack for a unit of work, and not one at a time (everyone would wait for the assessment of a heavy
     *  chunk with lamps). 144 loaded district chunks with a budget of 4 ms (counting clocks: 3 units at 1 ms per tick) at
     *  one - 48 tick of one queue, in batches - several ticks for the entire cascade. The area is its own place in the distance: batch GameTest
     *  takes place in the same places of the sites, and the city {@link #cityQueueRunsManyChunksPerTick} left lamps in every
     *  chunk around the site - there were no empty chunks when its batch was going on earlier.
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "grid_settle", skyAccess = true)
    public static void emptyChunksSettleInBatches(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        // places away from other checks - up to 6000 blocks along the diagonal and 12000 along the axes
        BlockPos centre = h.absolutePos(CENTER).offset(9000, 0, -9000);
        ChunkPos mid = new ChunkPos(centre);
        List<ChunkPos> held = new ArrayList<>();
        for (int dx = -6; dx < 6; dx++) {
            for (int dz = -6; dz < 6; dz++) {
                ChunkPos p = new ChunkPos(mid.x + dx, mid.z + dz);
                chunks.addRegionTicket(HOLD, p, 1, p);
                level.getChunk(p.x, p.z);
                held.add(p);
            }
        }
        BlockPos lamp = centre.offset(-14, 0, -14).atY(level.getHeight(Heightmap.Types.MOTION_BLOCKING, centre.getX() - 14, centre.getZ() - 14));
        level.setBlock(lamp, Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL);
        WorkClock clock = WorkClock.counting(1_000_000L);
        int budget = (int) Math.round(WorkScheduler.gridCapNanos() / 1e6);
        long[] start = {0};
        long[] took = {0};
        h.startSequence()
                // light returns from previous checks (quiet) must pass before measurement
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "there is a queue from previous checks"))
                .thenExecute(() -> {
                    Blackouts.useClock(level.getServer(), clock);
                    start[0] = level.getGameTime();
                    Blackouts.blackout(level, Vec3.atCenterOf(centre), 150, 1000, -1);
                })
                .thenWaitUntil(() -> {
                    BlackoutWorld world = BlackoutWorld.get(level);
                    h.assertTrue(world.idle() && GridLights.isUnlit(level.getBlockState(lamp)), "the cascade is going on");
                    took[0] = level.getGameTime() - start[0];
                })
                .thenExecute(() -> {
                    Blackouts.useClock(level.getServer(), Blackouts.newClock());
                    held.forEach(p -> chunks.removeRegionTicket(HOLD, p, 1, p));
                    // chunks in memory that are affected by the shutdown - everyone goes through the queue
                    long covered = held.stream().filter(p -> PowerGrid.get(level).covered(p.x, p.z)).count();
                    h.assertTrue(covered >= 100, "the shutdown only affected " + covered + " chunks - there is nothing to check");
                    // one per chunk - covered / units tick per queue
                    int units = clock.maxUnitsPerTick();
                    h.assertTrue(took[0] * units < covered, "cascade by " + covered + " chunkam went " + took[0] + " tick at " + units + " units per tick (budget " + budget + " ms)");
                })
                .thenSucceed();
    }

    /**
     * A lamp placed in a dark quarter, when the cascade has already passed, goes out - due to the event of the setting (by itself
     *  it would not go out: the cascade for its chunk will no longer come).
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "grid_placed", skyAccess = true)
    public static void lampPlacedInDarkDistrictGoesOut(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        // both are in a chunk in the middle of the platform (he’s all on it): this chunk has already passed the cascade
        ChunkPos mid = new ChunkPos(h.absolutePos(CENTER));
        int y = h.absolutePos(CENTER).getY() + 1;
        BlockPos first = new BlockPos(mid.getMinBlockX() + 3, y, mid.getMinBlockZ() + 3), placed = first.offset(6, 0, 6);
        level.setBlock(first, Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL);
        Blackouts.blackout(level, Vec3.atCenterOf(h.absolutePos(CENTER)), 200, 1000, -1);
        h.startSequence()
                .thenWaitUntil(() -> {
                    h.assertTrue(GridLights.isUnlit(level.getBlockState(first)), "lantern " + first + " lit");
                    // the whole cascade has passed: the lamp could only be extinguished by staging
                    h.assertTrue(BlackoutWorld.get(level).idle(), "the cascade is going on");
                })
                .thenExecute(() -> level.setBlock(placed, Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL))
                .thenIdle(10)
                .thenExecute(() -> {
                    h.assertTrue(level.getBlockState(placed).is(Blocks.LANTERN), "the lamp went out without an event: " + level.getBlockState(placed));
                    Blackouts.onBlockPlaced(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, placed), Blocks.STONE.defaultBlockState(), null));
                })
                .thenWaitUntil(() -> h.assertTrue(GridLights.isUnlit(level.getBlockState(placed)), "the supplied lantern is on"))
                .thenSucceed();
    }

    /**
     * Restart in the middle of the cascade: the blackout queue is not saved (unsaved attachment of the world) - the cascade goes
     *  again along the saved network, and the area goes out as if without a restart.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "grid_restart", skyAccess = true)
    public static void cascadeResumesAfterRestart(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        List<BlockPos> lamps = List.of(new BlockPos(18, 12, 18), new BlockPos(45, 12, 45));
        for (BlockPos p : lamps) h.setBlock(p, Blocks.LANTERN);
        // slow cascade (10 blocks per second); blackout memory - like a newly loaded world
        Blackouts.blackout(level, Vec3.atCenterOf(h.absolutePos(CENTER)), 200, 0.5, -1);
        level.setData(ModAttachments.BLACKOUT_WORLD, new BlackoutWorld());
        h.startSequence()
                .thenWaitUntil(() -> {
                    for (BlockPos p : lamps) h.assertTrue(GridLights.isUnlit(h.getBlockState(p)), "lantern " + p + " lit");
                })
                .thenExecute(() -> Blackouts.restore(level, null, 0, 0))
                .thenWaitUntil(() -> {
                    for (BlockPos p : lamps) h.assertBlockState(p, s -> s == Blocks.LANTERN.defaultBlockState(), () -> "lantern " + p + " didn't light up the same");
                })
                .thenSucceed();
    }

    /**
     * A chunk, kept light, in a dark quarter: the cascade does not load it, it goes out when loading - all 19 lamps are exactly
     *  doubles, and the light is counted according to the extinguished ones. The light was returned - the next time it is loaded, it lights up with the same lamps.
     */
    @GameTest(template = "range", timeoutTicks = 3600, batch = "grid_lazy", skyAccess = true)
    public static void unloadedChunkDarkensOnLoad(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos far = new ChunkPos(h.absolutePos(CENTER.east(480)));
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        BlockPos[] glow = new BlockPos[1];
        hold(level, far);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "chunk is loading"))
                .thenExecute(() -> {
                    placed.putAll(lampCases(level, caseOrigin(level, far)));
                    glow[0] = find(placed, Blocks.GLOWSTONE);
                    chunks.removeRegionTicket(HOLD, far, 2, far);
                })
                .thenWaitUntil(() -> unloaded(h, level, far))
                .thenExecute(() -> {
                    assertNoTwinsOnDisk(h, level, far);
                    Blackouts.blackout(level, Vec3.atCenterOf(glow[0]), 200, 1000, -1);
                })
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "the cascade is going on"))
                .thenExecute(() -> {
                    h.assertTrue(chunks.chunkMap.getVisibleChunkIfPresent(far.toLong()) == null, "cascade loaded chunk");
                    hold(level, far);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "chunk is loading");
                    assertLamps(h, level, placed, true);
                    assertBlockLight(h, level, glow[0].above(), 0);
                })
                .thenExecute(() -> Blackouts.restore(level, Vec3.atCenterOf(glow[0]), 8))
                .thenWaitUntil(() -> {
                    assertLamps(h, level, placed, false);
                    assertBlockLight(h, level, glow[0].above(), 14);
                })
                .thenExecute(() -> chunks.removeRegionTicket(HOLD, far, 2, far))
                .thenSucceed();
    }

    /**
     * The chunk at the edge of the loaded world is in memory, but the neighbors are not loaded (and will not load until the player approaches):
     *  its lamps go out and come on without waiting for neighbors, the blackout queue is empty.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "grid_edge", skyAccess = true)
    public static void edgeChunkSwitchesWithoutNeighbours(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos far = new ChunkPos(h.absolutePos(CENTER.south(480)));
        // ticket level 33: the chunk itself is fully loaded, its neighbors are not
        chunks.addRegionTicket(HOLD, far, 0, far);
        level.getChunk(far.x, far.z);
        BlockPos[] lamp = new BlockPos[1];
        h.startSequence()
                .thenExecute(() -> {
                    h.assertFalse(NuclearTickets.neighbourhoodLoaded(level, far), "the neighbors of the chunk are loaded - there is nothing to check");
                    int x = far.getMiddleBlockX(), z = far.getMiddleBlockZ();
                    lamp[0] = new BlockPos(x, level.getChunk(far.x, far.z).getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 3, z);
                    // without neighbor updates: they would read the neighboring chunk
                    level.setBlock(lamp[0], Blocks.GLOWSTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    Blackouts.blackout(level, Vec3.atCenterOf(lamp[0]), 200, 1000, -1);
                })
                .thenWaitUntil(() -> h.assertTrue(GridLights.isUnlit(level.getBlockState(lamp[0])), "the glowstone at the edge of the world is burning"))
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "blackout queue is not empty: " + java.util.Arrays.toString(BlackoutWorld.get(level).backlog())))
                .thenExecute(() -> Blackouts.restore(level, Vec3.atCenterOf(lamp[0]), 8))
                .thenWaitUntil(() -> h.assertTrue(level.getBlockState(lamp[0]).is(Blocks.GLOWSTONE), "the light at the edge of the world has not returned"))
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "blackout queue does not empty after return"))
                .thenExecute(() -> {
                    h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "chunk unloaded");
                    h.assertFalse(NuclearTickets.neighbourhoodLoaded(level, far), "blackout loaded the neighbors of the edge chunk");
                    chunks.removeRegionTicket(HOLD, far, 0, far);
                })
                .thenSucceed();
    }

    /**
     * A redstone lamp on the edge of a busy world: the signal was lost while the area was dark. The light returns
     *  in the palette (there are no neighbors - it is impossible to check the signal), the lamp is as it was; checking with the signal - when the neighbors have booted up:
     *  the lamp goes out, as if from a neighbor update.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "grid_edge_signal", skyAccess = true)
    public static void edgeLampFollowsSignalOnceNeighboursLoad(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos far = new ChunkPos(h.absolutePos(CENTER.west(480)));
        chunks.addRegionTicket(HOLD, far, 0, far);
        level.getChunk(far.x, far.z);
        BlockPos[] lamp = new BlockPos[1];
        h.startSequence()
                .thenExecute(() -> {
                    h.assertFalse(NuclearTickets.neighbourhoodLoaded(level, far), "the neighbors of the chunk are loaded - there is nothing to check");
                    int x = far.getMiddleBlockX(), z = far.getMiddleBlockZ();
                    lamp[0] = new BlockPos(x, level.getChunk(far.x, far.z).getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 3, z);
                    // without neighbor updates: they would read the neighboring chunk
                    level.setBlock(lamp[0].above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                    level.setBlock(lamp[0], Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true), Block.UPDATE_CLIENTS);
                    Blackouts.blackout(level, Vec3.atCenterOf(lamp[0]), 200, 1000, -1);
                })
                .thenWaitUntil(() -> h.assertTrue(GridLights.isUnlit(level.getBlockState(lamp[0])), "the lamp at the edge of the world is burning"))
                .thenExecute(() -> {
                    // the signal disappeared in the dark
                    level.setBlock(lamp[0].above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    Blackouts.restore(level, Vec3.atCenterOf(lamp[0]), 8);
                })
                .thenWaitUntil(() -> h.assertTrue(level.getBlockState(lamp[0]).is(Blocks.REDSTONE_LAMP), "the light at the edge of the world has not returned"))
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "blackout queue is not empty"))
                .thenExecute(() -> {
                    h.assertTrue(level.getBlockState(lamp[0]).getValue(RedstoneLampBlock.LIT), "lamp without neighbors checked the signal");
                    h.assertFalse(NuclearTickets.neighbourhoodLoaded(level, far), "blackout loaded the neighbors of the edge chunk");
                    hold(level, far);
                })
                .thenWaitUntil(() -> h.assertFalse(level.getBlockState(lamp[0]).getValue(RedstoneLampBlock.LIT), "the lamp did not check the signal when the neighbors booted up"))
                .thenExecute(() -> {
                    chunks.removeRegionTicket(HOLD, far, 0, far);
                    chunks.removeRegionTicket(HOLD, far, 2, far);
                })
                .thenSucceed();
    }

    /**
     * The same, but the neighbors of the edge chunk were already loaded and only dropped below full loading (the player moved away): when
     *  they rise back, there is no loading event, and the lamp is still checked against the signal.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "grid_edge_signal_back", skyAccess = true)
    public static void edgeLampFollowsSignalWhenNeighboursReturn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos far = new ChunkPos(h.absolutePos(CENTER.west(480).north(480)));
        chunks.addRegionTicket(HOLD, far, 0, far);
        // neighbors - fully loaded (there was a loading event), then they drop lower, remaining in memory
        hold(level, far);
        BlockPos[] lamp = new BlockPos[1];
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(NuclearTickets.neighbourhoodLoaded(level, far), "neighbors are loading"))
                .thenExecute(() -> {
                    int x = far.getMiddleBlockX(), z = far.getMiddleBlockZ();
                    lamp[0] = new BlockPos(x, level.getChunk(far.x, far.z).getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 3, z);
                    level.setBlock(lamp[0].above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                    level.setBlock(lamp[0], Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true), Block.UPDATE_CLIENTS);
                    chunks.removeRegionTicket(HOLD, far, 2, far);
                })
                .thenWaitUntil(() -> {
                    h.assertFalse(NuclearTickets.neighbourhoodLoaded(level, far), "neighbors are still fully loaded");
                    h.assertTrue(chunks.chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(far.x + 1, far.z)) != null, "the neighbor has completely unloaded");
                })
                .thenExecute(() -> Blackouts.blackout(level, Vec3.atCenterOf(lamp[0]), 200, 1000, -1))
                .thenWaitUntil(() -> h.assertTrue(GridLights.isUnlit(level.getBlockState(lamp[0])), "the lamp at the edge of the world is burning"))
                .thenExecute(() -> {
                    level.setBlock(lamp[0].above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    Blackouts.restore(level, Vec3.atCenterOf(lamp[0]), 8);
                })
                .thenWaitUntil(() -> h.assertTrue(level.getBlockState(lamp[0]).is(Blocks.REDSTONE_LAMP), "the light at the edge of the world has not returned"))
                .thenWaitUntil(() -> h.assertTrue(BlackoutWorld.get(level).idle(), "blackout queue is not empty"))
                .thenExecute(() -> {
                    h.assertTrue(level.getBlockState(lamp[0]).getValue(RedstoneLampBlock.LIT), "lamp without neighbors checked the signal");
                    // neighbors are fully loaded again - no loading event: chunks are the same as they were
                    chunks.addRegionTicket(HOLD, far, 2, far);
                })
                .thenWaitUntil(() -> h.assertFalse(level.getBlockState(lamp[0]).getValue(RedstoneLampBlock.LIT), "the lamp did not check the signal when the neighbors returned"))
                .thenExecute(() -> {
                    chunks.removeRegionTicket(HOLD, far, 0, far);
                    chunks.removeRegionTicket(HOLD, far, 2, far);
                })
                .thenSucceed();
    }

    /**
     * Dark chunk through the disk back and forth. It is saved with real lamps (not a single {@code incoming:unlit}
     *  in the tag, light - count); loaded, while the quarter is dark - again exactly doubles of all 19 lamps, a chest
     *  with contents and the same biomes, the lights are counted by extinguished ones. The light was returned while the chunk is on the disk - it loads
     *  with the same lamps and shines.
     */
    @GameTest(template = "range", timeoutTicks = 4800, batch = "grid_disk", skyAccess = true)
    public static void darkChunkRoundTripsThroughDisk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        var chunks = level.getChunkSource();
        ChunkPos far = new ChunkPos(h.absolutePos(CENTER.north(480)));
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        Map<BlockPos, Holder<Biome>> biomes = new LinkedHashMap<>();
        BlockPos[] glow = new BlockPos[1], chest = new BlockPos[1];
        ItemStack loot = new ItemStack(Items.DIAMOND, 7);
        hold(level, far);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "chunk is loading"))
                .thenExecute(() -> {
                    BlockPos origin = caseOrigin(level, far);
                    placed.putAll(lampCases(level, origin));
                    sealLampCases(level, origin);
                    glow[0] = find(placed, Blocks.GLOWSTONE);
                    chest[0] = origin.offset(0, 0, 9);
                    level.setBlock(chest[0], Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
                    ((ChestBlockEntity) level.getBlockEntity(chest[0])).setItem(4, loot.copy());
                    for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y += 16) {
                        BlockPos p = new BlockPos(far.getMinBlockX() + 5, y, far.getMinBlockZ() + 9);
                        biomes.put(p, level.getBiome(p));
                    }
                    Blackouts.blackout(level, Vec3.atCenterOf(glow[0]), 200, 1000, -1);
                })
                .thenWaitUntil(() -> {
                    assertLamps(h, level, placed, true);
                    assertBlockLight(h, level, glow[0].above(), 0);
                })
                .thenExecute(() -> chunks.removeRegionTicket(HOLD, far, 2, far))
                .thenWaitUntil(() -> unloaded(h, level, far))
                .thenExecute(() -> {
                    CompoundTag saved = assertNoTwinsOnDisk(h, level, far);
                    h.assertFalse(saved.getBoolean(ChunkSerializer.IS_LIGHT_ON_TAG), "the light of the extinguished is preserved as true");
                    h.assertTrue(saved.toString().contains("minecraft:glowstone"), "glowstone is not saved");
                    h.assertTrue(PowerGrid.get(level).dark(far.x, far.z, level.getGameTime()), "the block is already bright");
                    hold(level, far);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "chunk is loading");
                    assertLamps(h, level, placed, true);
                    assertBlockLight(h, level, glow[0].above(), 0);
                })
                .thenExecute(() -> {
                    h.assertTrue(level.getChunkAt(glow[0]).hasData(ModAttachments.GRID_DARK), "the chunk loaded with dark is not marked");
                    h.assertTrue(level.getBlockEntity(chest[0]) instanceof ChestBlockEntity c && ItemStack.matches(c.getItem(4), loot),
                            "chest after disc: " + level.getBlockEntity(chest[0]));
                    for (var e : biomes.entrySet()) {
                        h.assertTrue(level.getBiome(e.getKey()).value() == e.getValue().value(), "biome in " + e.getKey() + ": " + level.getBiome(e.getKey()));
                    }
                    chunks.removeRegionTicket(HOLD, far, 2, far);
                })
                .thenWaitUntil(() -> unloaded(h, level, far))
                .thenExecute(() -> {
                    assertNoTwinsOnDisk(h, level, far);
                    h.assertTrue(Blackouts.restore(level, Vec3.atCenterOf(glow[0]), 8) == 1, "there is nothing to return");
                })
                .thenWaitUntil(() -> h.assertFalse(PowerGrid.get(level).dark(far.x, far.z, level.getGameTime()), "the block is still dark"))
                .thenExecute(() -> {
                    h.assertTrue(chunks.chunkMap.getVisibleChunkIfPresent(far.toLong()) == null, "return light loaded chunk");
                    hold(level, far);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(chunks.getChunkNow(far.x, far.z) != null, "chunk is loading");
                    assertLamps(h, level, placed, false);
                    assertBlockLight(h, level, glow[0].above(), 14);
                })
                .thenExecute(() -> {
                    h.assertFalse(level.getChunkAt(glow[0]).hasData(ModAttachments.GRID_DARK), "light chunk is marked dark");
                    chunks.removeRegionTicket(HOLD, far, 2, far);
                })
                .thenSucceed();
    }

    /**
     * Intercepting the save affects only the tag that vanilla wrote for the same chunk. Your own tag (as in the chunk)
     * —lamps instead of doubles; foreign ones - empty, sections not a list, sections outside the height of the world, section blocks of a different
     *  type or different than in the chunk, tag of an underloaded chunk - remain byte by byte.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "grid_save_tags", skyAccess = true)
    public static void saveLeavesForeignTagsAlone(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        BlockPos lamp = h.absolutePos(CENTER);
        level.setBlock(lamp, Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_ALL);
        LevelChunk chunk = level.getChunkAt(lamp);
        h.assertTrue(ChunkLights.apply(level, chunk, true) >= 1, "the lantern didn't go out");
        try {
            CompoundTag own = ChunkSerializer.write(level, chunk);
            h.assertTrue(own.toString().contains("incoming:unlit_sea_lantern"), "there is no double in the tag - there is nothing to check");
            ChunkSaves.onSave(new ChunkDataEvent.Save(chunk, level, own));
            h.assertFalse(own.toString().contains("incoming:unlit"), "the double remains in the tag: " + own.toString().indexOf("incoming:unlit"));
            h.assertTrue(own.toString().contains("minecraft:sea_lantern"), "the lantern is not in the tag");
            h.assertFalse(own.getBoolean(ChunkSerializer.IS_LIGHT_ON_TAG), "the light of the extinguished is preserved as true");
            h.assertTrue(level.getBlockState(lamp) == GridLights.unlit(Blocks.SEA_LANTERN.defaultBlockState()), "conservation lit a lantern in the world");

            CompoundTag pattern = ChunkSerializer.write(level, chunk);
            int sy = SectionPos.blockToSectionCoord(lamp.getY());
            PalettedContainer<BlockState> stone = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.STONE.defaultBlockState(),
                    PalettedContainer.Strategy.SECTION_STATES);
            List<CompoundTag> foreign = List.of(
                    new CompoundTag(),
                    with(pattern, t -> t.put(ChunkSerializer.SECTIONS_TAG, new CompoundTag())),
                    with(pattern, t -> sections(t).forEach(s -> s.putByte("Y", (byte) (s.getByte("Y") + 64)))),
                    with(pattern, t -> sections(t).forEach(s -> s.putString("block_states", "foreign"))),
                    with(pattern, t -> sections(t).stream().filter(s -> s.getByte("Y") == sy)
                            .forEach(s -> s.put("block_states", BLOCK_STATES.encodeStart(NbtOps.INSTANCE, stone).getOrThrow()))));
            for (CompoundTag t : foreign) {
                CompoundTag before = t.copy();
                ChunkSaves.onSave(new ChunkDataEvent.Save(chunk, level, t));
                h.assertTrue(t.equals(before), "someone else's tag has been changed: " + before.getAllKeys());
            }
            ProtoChunk proto = new ProtoChunk(chunk.getPos(), UpgradeData.EMPTY, level, level.registryAccess().registryOrThrow(Registries.BIOME), null);
            CompoundTag protoTag = pattern.copy();
            ChunkSaves.onSave(new ChunkDataEvent.Save(proto, level, protoTag));
            h.assertTrue(protoTag.equals(pattern), "underloaded chunk tag changed");
        } finally {
            ChunkLights.apply(level, chunk, false);
        }
        h.succeed();
    }

    /**
     * A section where the double needs a new entry in the palette, but there is no space in it: saving writes lamps and does not touch the
     *  section of the world. A copy of {@code PalettedContainer.copy()} of any palette except the global one holds the growth handler for the live section
     *  (a palette of one value is exactly the same), and a replacement in the copy would expand the world section, and the recording would fail - chunk
     *  would go to the disk with duplicates. Sections: all of a double (one value), a full linear palette (16 states)
     *  and a global one (more 256 states) with and without a double - searching for lamps by it counts blocks ({@link Palettes#contains}).
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "grid_save_tags", skyAccess = true)
    public static void saveLeavesFullPaletteSectionsAlone(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        quiet(level);
        BlockState twin = GridLights.unlit(Blocks.SEA_LANTERN.defaultBlockState());
        PalettedContainer<BlockState> single = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, twin, PalettedContainer.Strategy.SECTION_STATES);
        // air, 14 other blocks and double: 16 states - the linear palette is full, there is no lantern in it
        List<Block> others = List.of(Blocks.STONE, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.SAND,
                Blocks.GRAVEL, Blocks.GLASS, Blocks.BRICKS, Blocks.OAK_LOG, Blocks.WHITE_WOOL, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE);
        PalettedContainer<BlockState> linear = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
        for (int i = 0; i < others.size(); i++) linear.set(i, 0, 0, others.get(i).defaultBlockState());
        linear.set(15, 15, 15, twin);
        PalettedContainer<BlockState> global = globalWithoutLamps(), globalTwin = globalWithoutLamps();
        globalTwin.set(15, 15, 15, twin);
        h.assertTrue(global.maybeHas(GridLights::isUnlit), "a palette without duplicates answers “no” itself: it is not global");
        LevelChunk chunk = level.getChunkAt(h.absolutePos(CENTER));
        // empty section above the platform - during the inspection, one of these
        int index = chunk.getSectionIndex(h.absolutePos(CENTER).getY()) + 4;
        LevelChunkSection air = chunk.getSections()[index];
        h.assertTrue(air.hasOnlyAir(), "the section above the platform is not empty");
        int sectionY = chunk.getSectionYFromSectionIndex(index);
        for (PalettedContainer<BlockState> live : List.of(single, linear, global, globalTwin)) {
            chunk.getSections()[index] = new LevelChunkSection(live, air.getBiomes());
            try {
                Tag before = BLOCK_STATES.encodeStart(NbtOps.INSTANCE, live).getOrThrow();
                int size = live.getSerializedSize();
                CompoundTag tag = ChunkSerializer.write(level, chunk);
                ChunkSaves.onSave(new ChunkDataEvent.Save(chunk, level, tag));
                CompoundTag saved = sections(tag).stream().filter(s -> s.getByte("Y") == sectionY).findFirst().orElseThrow().getCompound("block_states");
                PalettedContainer<BlockState> disk = BLOCK_STATES.parse(NbtOps.INSTANCE, saved).getOrThrow();
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState inWorld = live.get(x, y, z), onDisk = disk.get(x, y, z);
                            BlockState expected = inWorld == twin ? Blocks.SEA_LANTERN.defaultBlockState() : inWorld;
                            if (onDisk != expected) h.fail("on disk " + onDisk + " instead of " + expected + " in " + x + " " + y + " " + z);
                        }
                    }
                }
                h.assertTrue(BLOCK_STATES.encodeStart(NbtOps.INSTANCE, live).getOrThrow().equals(before), "saving changed a section of the world");
                // in the global palette without doubles in this place there is another block (it was checked by the cycle above)
                h.assertTrue(live == global || live.get(15, 15, 15) == twin, "conservation lit a lantern in the world");
                // the palette of the world section has not grown (the blocks are the same, but the storage is different)
                h.assertTrue(live.getSerializedSize() == size, "conservation expanded the palette of the world section");
            } finally {
                chunk.getSections()[index] = air;
            }
        }
        h.succeed();
    }

    /**
     * “Is there a double in the section” - exactly with any palette. The global palette (more than 256 states in a section, the usual
     *  thing in a detailed city) on {@code maybeHas} always answers “yes”, and without counting, each loading and saving of such a chunk would go through all the blocks of the section and put it in the blackout queue; small palettes (linear up to 16
     *  states, hash table up to 256) remember departed states.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "grid_palette", skyAccess = true)
    public static void lampSearchIsExactForEveryPalette(GameTestHelper h) {
        BlockState twin = GridLights.unlit(Blocks.SEA_LANTERN.defaultBlockState());
        PalettedContainer<BlockState> global = globalWithoutLamps();
        h.assertTrue(global.maybeHas(GridLights::isUnlit), "the palette answers “no” itself - there is nothing to check");
        h.assertFalse(Palettes.contains(global, GridLights::isUnlit), "a double was found in the global palette without duplicates");
        global.set(7, 7, 7, twin);
        h.assertTrue(Palettes.contains(global, GridLights::isUnlit), "duplicate not found in global palette");

        PalettedContainer<BlockState> stale = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        stale.set(1, 2, 3, twin);
        stale.set(1, 2, 3, Blocks.STONE.defaultBlockState());
        h.assertTrue(stale.maybeHas(GridLights::isUnlit), "the small palette forgot the departed double - there is nothing to check");
        h.assertFalse(Palettes.contains(stale, GridLights::isUnlit), "the double who left the section was found based on the palette");

        // 100 different states - palette-hash-table, also remembers the departed double
        PalettedContainer<BlockState> hashed = withoutLamps(100);
        hashed.set(15, 15, 15, twin);
        hashed.set(15, 15, 15, Blocks.STONE.defaultBlockState());
        h.assertTrue(hashed.maybeHas(GridLights::isUnlit), "palette-hash-table forgot the departed double - there is nothing to check");
        h.assertFalse(Palettes.contains(hashed, GridLights::isUnlit), "the departed double was found using the palette hash table");
        hashed.set(15, 15, 15, twin);
        h.assertTrue(Palettes.contains(hashed, GridLights::isUnlit), "duplicate in palette-hash-table not found");
        h.succeed();
    }

    /** Section of 4096 of different states without lamps and doubles: global palette. */
    private static PalettedContainer<BlockState> globalWithoutLamps() {
        return withoutLamps(4096);
    }

    /** Section from {@code distinct} of different states without lamps and doubles (the rest is air). */
    private static PalettedContainer<BlockState> withoutLamps(int distinct) {
        PalettedContainer<BlockState> c = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        int placed = 0;
        for (BlockState s : Block.BLOCK_STATE_REGISTRY) {
            if (placed == distinct) break;
            if (s.isAir() || GridLights.isLit(s) || GridLights.isUnlit(s)) continue;
            c.set(placed & 15, placed >> 8, placed >> 4 & 15, s);
            placed++;
        }
        if (placed < distinct) throw new IllegalStateException("less in the registry " + distinct + " states");
        return c;
    }

    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private static CompoundTag with(CompoundTag tag, Consumer<CompoundTag> edit) {
        CompoundTag t = tag.copy();
        edit.accept(t);
        return t;
    }

    private static List<CompoundTag> sections(CompoundTag tag) {
        ListTag list = tag.getList(ChunkSerializer.SECTIONS_TAG, Tag.TAG_COMPOUND);
        return java.util.stream.IntStream.range(0, list.size()).mapToObj(list::getCompound).toList();
    }

    /**
     * Keep a chunk and its neighbors and load them immediately (from disk or generation): the GameTest server on CI ticks
     *  tens of times faster than the game, and the test period passes before the background loading.
     */
    private static void hold(ServerLevel level, ChunkPos p) {
        level.getChunkSource().addRegionTicket(HOLD, p, 2, p);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) level.getChunk(p.x + dx, p.z + dz);
        }
    }

    /**
     * Stone around the layout {@link #lampCases} (lamps - x 0..9, z 0 and 3, supports above them - y + 1): light inside - only
     * from her lamps. batch go on the same places of the platforms, and someone else's light at the distant chunk (what the
     *  checks left earlier) gives 1–4 over the extinguished glowstone instead of 0.
     */
    private static void sealLampCases(ServerLevel level, BlockPos origin) {
        for (int dx = -1; dx <= 10; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 4; dz++) {
                    boolean inside = dx >= 0 && dx <= 9 && dy >= 0 && dy <= 1 && dz >= 0 && dz <= 3;
                    if (!inside) level.setBlock(origin.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    /** The angle of the lamps in the chunk: above the highest block of the chunk, so that there is air above the lamps. */
    private static BlockPos caseOrigin(ServerLevel level, ChunkPos p) {
        int top = level.getMinBuildHeight();
        for (int x = p.getMinBlockX(); x <= p.getMaxBlockX(); x++) {
            for (int z = p.getMinBlockZ(); z <= p.getMaxBlockZ(); z++) top = Math.max(top, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
        }
        return new BlockPos(p.getMinBlockX() + 3, top + 3, p.getMinBlockZ() + 3);
    }

    private static BlockPos find(Map<BlockPos, BlockState> placed, Block block) {
        return placed.entrySet().stream().filter(e -> e.getValue().is(block)).findFirst().orElseThrow().getKey();
    }

    /** All lamps are exactly doubles of those supplied ({@code dark}) or exactly placed. */
    private static void assertLamps(GameTestHelper h, ServerLevel level, Map<BlockPos, BlockState> placed, boolean dark) {
        for (var e : placed.entrySet()) {
            BlockState want = dark ? GridLights.unlit(e.getValue()) : e.getValue();
            BlockState now = level.getBlockState(e.getKey());
            h.assertTrue(now == want, "in " + e.getKey() + " were waiting " + want + ", worth it " + now);
        }
    }

    private static void assertBlockLight(GameTestHelper h, ServerLevel level, BlockPos p, int want) {
        int got = level.getBrightness(LightLayer.BLOCK, p);
        h.assertTrue(got == want, "light blocks in " + p + ": " + got + ", waited " + want);
    }

    /** Tag of a chunk from a disk (or from a recording queue): there is one without a single duplicate. */
    private static CompoundTag assertNoTwinsOnDisk(GameTestHelper h, ServerLevel level, ChunkPos p) {
        CompoundTag tag = level.getChunkSource().chunkMap.read(p).join().orElse(null);
        h.assertTrue(tag != null, "chunk " + p + " not saved");
        String text = tag.toString();
        h.assertFalse(text.contains("incoming:unlit"), "double on disk: ..." + text.substring(Math.max(0, text.indexOf("incoming:unlit") - 40),
                Math.min(text.length(), text.indexOf("incoming:unlit") + 60)) + "…");
        return tag;
    }

    /**
     * Wait for the chunk to unload. The GameTest server ticks without pauses, and it never has “extra time,” and vanilla does the unloading of
     *  (queue {@code unloadQueue}) only during the remaining tick time - as any live server does.
     *  Here this time is given to it: one unloading pass per waiting step.
     */
    private static void unloaded(GameTestHelper h, ServerLevel level, ChunkPos p) {
        level.getChunkSource().tick(() -> true, false);
        var holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(p.toLong());
        h.assertTrue(holder == null, "chunk not unloaded: level " + (holder == null ? 0 : holder.getTicketLevel()));
    }
}
