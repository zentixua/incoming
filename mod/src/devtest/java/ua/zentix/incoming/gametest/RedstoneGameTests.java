package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.defense.Radar;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.grid.Node;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.grid.SubstationBlock;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.util.SignalEdge;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Redstone from sensing blocks (A4 contract, PR #747): the SAM gives weak power 15 from every face while its radar's latest
 * sweep saw a hostile projectile; the substation while it works ({@code powered}). Every check watches each tick that
 * the dust beside the block carries exactly the block's signal: a change without a neighbour update would leave the
 * dust behind for at least one tick. SAM checks have their own batches (a SAM sees every projectile in the world).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RedstoneGameTests {
    private RedstoneGameTests() {}

    // ---------------------------------------------------------------- SAM

    /**
     * Over the runway fly first the owner's and a teammate's Shahed-136, then a stranger's. Three SAMs of the owner, none
     * firing: A empty, with dust, a redstone lamp and a piston beside it; B with four missiles in stock and none on the
     * rail (reload longer than the check), a comparator reading it; C empty under a stone roof. Friendly drones never turn
     * any signal on, though the radar sees them. The hostile drone turns A and B on within one sweep of its launch; on that
     * tick the dust carries 15 and the lamp is lit; the piston extends at the next block-event pass. After the drone hits
     * its target the signal drops within one sweep, the dust on that tick, the lamp after vanilla's 4-tick delay. B's
     * comparator reads the magazine throughout, not the alarm. C's roof blinds its radar: no signal.
     */
    @GameTest(template = "runway", timeoutTicks = 900, batch = "redstone_sam", skyAccess = true)
    public static void samSignalFollowsHostileTracks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        SamGameTests.air(h, 1, 600);
        Scoreboard board = level.getScoreboard();
        PlayerTeam team = board.addPlayerTeam("redstone_sam_team");
        StrikeGameTests.afterTest(h, () -> board.removePlayerTeam(team));
        SamGameTests.Listener crew = SamGameTests.listener(h, "rs_crew", SamGameTests.SAM.east(2).south(3));
        SamGameTests.Listener mate = SamGameTests.listener(h, "rs_mate", SamGameTests.SAM.west(2).south(3));
        board.addPlayerToTeam(crew.getScoreboardName(), team);
        board.addPlayerToTeam(mate.getScoreboardName(), team);
        BlockPos a = SamGameTests.SAM, b = a.east(8), c = a.west(8);
        // the comparator first, as a player would: a comparator reads its input only on a neighbour update, and loading
        // the SAM's magazine (setChanged) is the one that gives it the stock
        h.setBlock(b.east().below(), Blocks.STONE);
        // a diode reads the block on its FACING side
        h.setBlock(b.east(), Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.WEST));
        SamBlockEntity samA = SamGameTests.sam(h, a, crew.getUUID(), 0);
        SamBlockEntity samB = SamGameTests.sam(h, b, crew.getUUID(), 4);
        SamBlockEntity samC = SamGameTests.sam(h, c, crew.getUUID(), 0);
        h.setBlock(c.above(2), Blocks.STONE);
        BlockPos dustA = a.west(), lamp = a.north(), piston = a.east(), comparator = b.east(), dustB = b.west(), dustC = c.west();
        for (BlockPos p : new BlockPos[]{dustA, dustB, dustC}) h.setBlock(p.below(), Blocks.STONE);
        h.setBlock(dustA, Blocks.REDSTONE_WIRE);
        h.setBlock(dustB, Blocks.REDSTONE_WIRE);
        h.setBlock(dustC, Blocks.REDSTONE_WIRE);
        h.setBlock(lamp, Blocks.REDSTONE_LAMP);
        // East of A, off every drone's line of sight: an extended head stands above the antenna (1.5 blocks over the SAM's
        // floor) and, like any block there, hides low targets on its side (to the south it hid the diving Shahed).
        h.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP));
        Vec3 antenna = Vec3.atCenterOf(h.absolutePos(a)).add(0, 1, 0);

        DroneEntity[] hostile = new DroneEntity[1], friends = new DroneEntity[2];
        long[] launched = {-1}, on = {-1}, onB = {-1}, off = {-1}, removed = {-1}, extended = {-1}, retracted = {-1}, unlit = {-1};
        Set<UUID> friendlySeen = new HashSet<>();
        // a comparator sets its output 2 ticks after an update: check it from the tick it first reads the magazine
        boolean[] settled = {false};
        h.onEachTick(() -> {
            long t = level.getGameTime();
            assertDust(h, dustA, samA.alarm());
            assertDust(h, dustB, samB.alarm());
            assertDust(h, dustC, samC.alarm());
            h.assertFalse(samC.alarm(), "a SAM under a stone roof gives a signal: its radar must be blind");
            int stock = samB.comparator();
            h.assertTrue(stock > 0 && stock < 15, "magazine comparator level " + stock + " cannot tell it from the alarm");
            int read = h.getBlockEntity(comparator) instanceof ComparatorBlockEntity cmp ? cmp.getOutputSignal() : -1;
            settled[0] |= read == stock;
            if (settled[0]) h.assertTrue(read == stock, "comparator reads " + read + ", the magazine is " + stock + " (alarm " + samB.alarm() + ")");
            if (hostile[0] == null) {
                h.assertFalse(samA.alarm() || samB.alarm(), "a friendly Shahed turned the alarm on");
                for (Radar.Track tr : Radar.scan(level, antenna, InterceptorSpec.SAM.radarRange(), InterceptorSpec.SAM.engageRange(), crew.getUUID())) {
                    friendlySeen.add(tr.id());
                }
                return;
            }
            boolean lit = h.getBlockState(lamp).getValue(RedstoneLampBlock.LIT);
            boolean out = extended(h, piston), in = retracted(h, piston);
            if (on[0] < 0 && samA.alarm()) {
                on[0] = t;
                h.assertTrue(lit, "the lamp did not light on the tick the alarm came on");
            }
            // B sweeps on its own phase (staggered by position)
            if (onB[0] < 0 && samB.alarm()) onB[0] = t;
            if (on[0] >= 0 && extended[0] < 0 && out) extended[0] = t;
            if (removed[0] < 0 && hostile[0].isRemoved()) removed[0] = t;
            if (on[0] >= 0 && off[0] < 0 && !samA.alarm()) {
                off[0] = t;
                // off exactly when the radar no longer sees it: gone, or out of sight in its dive
                h.assertTrue(removed[0] >= 0 || Radar.scan(level, antenna, InterceptorSpec.SAM.radarRange(), InterceptorSpec.SAM.engageRange(), crew.getUUID())
                        .stream().noneMatch(Radar.Track::hostile), "the alarm dropped while the radar still saw the hostile Shahed");
            }
            if (off[0] >= 0 && extended[0] >= 0 && retracted[0] < 0 && in) retracted[0] = t;
            if (off[0] >= 0 && unlit[0] < 0 && !lit) unlit[0] = t;
        });
        h.startSequence()
                .thenExecute(() -> {
                    friends[0] = SamGameTests.drone(h, 10, crew.getUUID());
                    friends[1] = SamGameTests.drone(h, 22, mate.getUUID());
                })
                .thenWaitUntil(() -> h.assertTrue(friends[0].isRemoved() && friends[1].isRemoved(), "friendly Shaheds are still flying"))
                .thenExecute(() -> {
                    h.assertTrue(settled[0], "the comparator never read the magazine");
                    h.assertTrue(friendlySeen.size() >= 2, "the owner's radar saw " + friendlySeen.size() + " friendly Shaheds: the check is empty");
                    hostile[0] = SamGameTests.drone(h, 16, null);
                    launched[0] = level.getGameTime();
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(unlit[0] >= 0 && retracted[0] >= 0, "the alarm has not come and gone yet");
                    h.assertTrue(on[0] - launched[0] <= SamBlockEntity.SWEEP, "alarm " + (on[0] - launched[0]) + " ticks after the launch");
                    h.assertTrue(onB[0] >= 0 && onB[0] - launched[0] <= SamBlockEntity.SWEEP,
                            "SAM B, loaded but with no missile on its rail, gave no alarm within a sweep: " + (onB[0] - launched[0]));
                    h.assertTrue(extended[0] >= 0 && extended[0] - on[0] <= 2, "piston extended " + (extended[0] - on[0]) + " ticks after the alarm");
                    h.assertTrue(removed[0] < 0 || off[0] - removed[0] <= SamBlockEntity.SWEEP, "alarm dropped " + (off[0] - removed[0]) + " ticks after the target was gone");
                    h.assertTrue(unlit[0] - off[0] <= 4, "lamp went out " + (unlit[0] - off[0]) + " ticks after the alarm dropped");
                    // the base is a moving piston for the 2 ticks of its retraction
                    h.assertTrue(retracted[0] - off[0] <= 4, "piston retracted " + (retracted[0] - off[0]) + " ticks after the alarm dropped");
                    h.assertTrue(BlastLog.near(h, new BlockPos(16, 3, 200), 8), "the hostile Shahed did not reach its target: was it shot down?");
                })
                .thenSucceed();
    }

    /**
     * A stranger's Shahed-136 out of the world, between the SAM's engage range and its radar range (so it is seen and never
     * engaged), flies away and leaves the radar: the signal is on while it is within range and drops within one sweep of
     * its leaving, while it still flies. A second one flies in from beyond the range: no signal until it crosses into it,
     * then on within one sweep. Toward +Z, high enough to clear the platform wall.
     */
    @GameTest(template = "runway", timeoutTicks = 600, batch = "redstone_sam_range", skyAccess = true)
    public static void samSignalFollowsRadarRange(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        SamGameTests.air(h, 1, 600);
        SamBlockEntity sam = SamGameTests.sam(h, SamGameTests.SAM, UUID.randomUUID(), 0);
        BlockPos dust = SamGameTests.SAM.west();
        h.setBlock(dust.below(), Blocks.STONE);
        h.setBlock(dust, Blocks.REDSTONE_WIRE);
        Vec3 antenna = Vec3.atCenterOf(h.absolutePos(SamGameTests.SAM)).add(0, 1, 0);
        double range = InterceptorSpec.SAM.radarRange();
        h.assertTrue(range > InterceptorSpec.SAM.engageRange() + 100, "the drones would be engageable");
        // one sweep of drone travel, and some
        double slack = SamBlockEntity.SWEEP * 4;
        DroneEntity[] d = new DroneEntity[1];
        boolean[] leaving = {true};
        long[] on = {-1}, off = {-1};
        h.onEachTick(() -> {
            assertDust(h, dust, sam.alarm());
            if (d[0] == null) return;
            double dist = d[0].position().distanceTo(antenna);
            if (leaving[0]) {
                if (on[0] < 0 && sam.alarm()) on[0] = level.getGameTime();
                if (on[0] >= 0 && off[0] < 0 && !sam.alarm()) {
                    off[0] = level.getGameTime();
                    h.assertTrue(dist > range && dist < range + slack, "alarm dropped at " + dist + " blocks, radar range " + range);
                    h.assertTrue(alive(level, d[0]), "the Shahed was gone, not out of range");
                }
                if (off[0] < 0 && sam.alarm()) h.assertTrue(dist < range + slack, "alarm still on at " + dist + " blocks");
            } else if (sam.alarm()) {
                h.assertTrue(dist <= range, "alarm on with the Shahed at " + dist + " blocks, beyond radar range " + range);
                if (on[0] < 0) on[0] = level.getGameTime();
            } else {
                h.assertTrue(dist > range - slack, "no alarm with the Shahed already " + dist + " blocks away");
            }
        });
        h.startSequence()
                .thenExecute(() -> d[0] = SamGameTests.virtualDrone(h, antenna.add(0, 150, range - 30), antenna.add(0, 150, 9000), null))
                .thenWaitUntil(() -> h.assertTrue(off[0] >= 0, "the leaving Shahed has not left the radar"))
                .thenExecute(() -> {
                    h.assertTrue(on[0] >= 0, "no alarm for a Shahed inside radar range");
                    VirtualFlights.get(level).clear(level, p -> p == d[0]);
                    leaving[0] = false;
                    on[0] = -1;
                    d[0] = SamGameTests.virtualDrone(h, antenna.add(0, 150, range + 40), antenna.add(0, 0, 400), null);
                })
                .thenWaitUntil(() -> h.assertTrue(on[0] >= 0, "the approaching Shahed has not entered the radar"))
                .thenSucceed();
    }

    /**
     * Restart while tracking: an ownerless SAM in a distant chunk sees a stranger's Shahed (an ownerless SAM counts every
     * projectile hostile) and powers its dust; the chunk unloads with the alarm saved in its block state on disk. The
     * Shahed is removed, the chunk loads again: the dust stays powered by the saved alarm until the next sweep, which drops
     * the signal and the dust together.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "redstone_sam_disk", skyAccess = true)
    public static void samSignalSurvivesChunkReload(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        SamGameTests.air(h, 1, 600);
        ChunkPos far = new ChunkPos(h.absolutePos(GridGameTests.CENTER.east(480)));
        BlockPos[] at = new BlockPos[2];
        DroneEntity[] d = new DroneEntity[1];
        boolean[] reloading = {false}, firstSeen = {false};
        GridGameTests.holdWide(level, far);
        StrikeGameTests.afterTest(h, () -> level.getChunkSource().removeRegionTicket(GridGameTests.HOLD, far, 2, far));
        h.onEachTick(() -> {
            if (at[0] == null || level.getChunkSource().getChunkNow(far.x, far.z) == null) return;
            if (!(level.getBlockEntity(at[0]) instanceof SamBlockEntity sam)) return;
            // a notice waits while its circuit reaches an unready chunk (SignalEdge)
            if (SignalEdge.ready(level, at[0])) assertDust(level, h, at[1], sam.alarm());
            if (reloading[0] && !firstSeen[0]) {
                firstSeen[0] = true;
                boolean swept = (level.getGameTime() + Math.floorMod(at[0].hashCode(), SamBlockEntity.SWEEP)) % SamBlockEntity.SWEEP == 0;
                h.assertTrue(sam.alarm() || swept, "the reloaded SAM lost its saved alarm before any sweep");
            }
        });
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(level.getChunkSource().getChunkNow(far.x, far.z) != null, "chunk is loading"))
                .thenExecute(() -> {
                    BlockPos floor = top(level, far);
                    at[0] = floor.above();
                    at[1] = floor.above().east();
                    level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(floor.east(), Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(at[0], ModBlocks.SAM.get().defaultBlockState(), 3);
                    level.setBlock(at[1], Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
                    Vec3 antenna = Vec3.atCenterOf(at[0]).add(0, 1, 0);
                    d[0] = SamGameTests.virtualDrone(h, antenna.add(0, 300, 200), antenna.add(0, 300, 9000), UUID.randomUUID());
                })
                .thenWaitUntil(() -> h.assertTrue(level.getBlockEntity(at[0]) instanceof SamBlockEntity s && s.alarm(), "the ownerless SAM gives no alarm"))
                .thenExecute(() -> level.getChunkSource().removeRegionTicket(GridGameTests.HOLD, far, 2, far))
                .thenWaitUntil(() -> GridGameTests.unloaded(h, level, far))
                .thenExecute(() -> {
                    VirtualFlights.get(level).clear(level, p -> p == d[0]);
                    String saved = savedSam(level, far, at[0]);
                    h.assertTrue("true".equals(saved), "the saved chunk holds the SAM with alarm=" + saved);
                    reloading[0] = true;
                    GridGameTests.holdWide(level, far);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(firstSeen[0], "the chunk has not loaded");
                    h.assertTrue(level.getBlockEntity(at[0]) instanceof SamBlockEntity s && !s.alarm(), "the alarm did not drop with nothing in the sky");
                })
                .thenExecute(() -> {
                    level.removeBlockEntity(at[0]);
                    level.setBlock(at[0], Blocks.AIR.defaultBlockState(), 3);
                })
                .thenSucceed();
    }

    // ---------------------------------------------------------------- substation

    /**
     * A substation with dust and a piston beside it. Placed, it works and powers both. An explosion knocks it out: the dust
     * drops on that tick, the piston retracts. Restored by command: back on that tick. Knocked out again with repairs a
     * few clock ticks away: back on by time, within the once-a-second sync. A blackout of its area by command does not
     * knock it out: it stays on in a dark district. A nuclear outage over it: off on that tick; a command restore turns it on.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "redstone_substation", skyAccess = true)
    public static void substationSignalFollowsGrid(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        GridGameTests.quiet(level);
        IncomingConfig.Server cfg = IncomingConfig.SERVER;
        double hours0 = cfg.gridRestoreHours.get();
        StrikeGameTests.afterTest(h, () -> {
            cfg.gridRestoreHours.set(hours0);
            Blackouts.restore(level, null, 0, 0);
        });
        BlockPos center = GridGameTests.CENTER, dust = center.west(), piston = center.north();
        h.setBlock(dust.below(), Blocks.STONE);
        h.setBlock(center, ModBlocks.SUBSTATION.get());
        h.setBlock(dust, Blocks.REDSTONE_WIRE);
        h.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP));
        Node node = PowerGrid.get(level).nodeAt(h.absolutePos(center));
        h.assertTrue(node != null, "the substation did not become a grid node");
        Vec3 c = Vec3.atCenterOf(h.absolutePos(center));
        // repairs by time: due on the grid clock, applied by the once-a-second sync
        boolean[] timed = {false};
        long[] due = {-1};
        h.onEachTick(() -> {
            assertDust(h, dust, powered(h, center));
            if (!timed[0] || powered(h, center)) return;
            if (due[0] < 0 && PowerGrid.get(level).downOutage(node.id(), Blackouts.now(level)).isEmpty()) due[0] = level.getGameTime();
            if (due[0] >= 0) h.assertTrue(level.getGameTime() - due[0] <= 20, "repairs were due " + (level.getGameTime() - due[0]) + " ticks ago, still out");
        });
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(powered(h, center) && extended(h, piston), "a working substation does not power the piston"))
                .thenExecute(() -> {
                    Vec3 e = Vec3.atCenterOf(h.absolutePos(center.east(3)));
                    level.explode(null, e.x, e.y, e.z, 2f, Level.ExplosionInteraction.NONE);
                    h.assertFalse(powered(h, center), "the explosion did not knock the substation out");
                    assertDust(h, dust, false);
                })
                .thenWaitUntil(() -> h.assertTrue(retracted(h, piston), "the piston stays out beside a knocked-out substation"))
                .thenExecute(() -> {
                    h.assertTrue(Blackouts.restore(level, c, 8) >= 1, "nothing to restore");
                    h.assertTrue(powered(h, center), "a command restore did not bring the substation back");
                    assertDust(h, dust, true);
                    cfg.gridRestoreHours.set(0.002);
                    Blackouts.knockOut(level, node);
                    timed[0] = true;
                    h.assertFalse(powered(h, center), "the knock-out did not take the substation down");
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(PowerGrid.get(level).downOutage(node.id(), Blackouts.now(level)).isEmpty(), "repairs are not due yet");
                    h.assertTrue(powered(h, center), "repairs are due, but the substation is still out");
                })
                .thenExecute(() -> {
                    timed[0] = false;
                    Blackouts.blackout(level, c, 64, 1000, -1);
                })
                .thenIdle(25)
                .thenExecute(() -> {
                    BlockPos abs = h.absolutePos(center);
                    h.assertTrue(PowerGrid.get(level).dark(abs.getX() >> 4, abs.getZ() >> 4, Blackouts.now(level)), "the blackout did not reach the substation's district");
                    h.assertTrue(powered(h, center), "another outage darkening its district knocked the substation out");
                    BlockPos g = h.absolutePos(center.east(8));
                    Detonation det = new Detonation(NuclearGameTests.IDS.incrementAndGet(), new Vec3(g.getX() + 0.5, g.getY() + 300, g.getZ() + 0.5), g.getY(),
                            15, false, level.getGameTime(), 0, 0, 20_000, 7, 0.1f, false, Detonation.OLD_WAVE);
                    Blackouts.nuke(level, det, ua.zentix.incoming.IncomingConfig.SERVER.gridEnabled.get());
                    h.assertFalse(powered(h, center), "a nuclear outage over the substation left it working");
                    assertDust(h, dust, false);
                })
                .thenExecute(() -> {
                    h.assertTrue(Blackouts.restore(level, null, 0) >= 1, "nothing to restore");
                    h.assertTrue(powered(h, center), "a command restore did not bring the substation back");
                })
                .thenSucceed();
    }

    /**
     * A substation in a distant chunk is knocked out while the chunk is unloaded: the knock-out loads nothing, the chunk
     * keeps its saved working state and powered dust; loaded again, the sync takes it down and the dust with it, never
     * apart. A command restore then brings both back on that tick.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "redstone_substation_disk", skyAccess = true)
    public static void substationSignalAfterChangeWhileUnloaded(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        GridGameTests.quiet(level);
        StrikeGameTests.afterTest(h, () -> Blackouts.restore(level, null, 0, 0));
        ChunkPos far = new ChunkPos(h.absolutePos(GridGameTests.CENTER.east(480)));
        BlockPos[] at = new BlockPos[2];
        Node[] node = new Node[1];
        boolean[] reloaded = {false};
        GridGameTests.holdWide(level, far);
        StrikeGameTests.afterTest(h, () -> level.getChunkSource().removeRegionTicket(GridGameTests.HOLD, far, 2, far));
        h.onEachTick(() -> {
            if (at[0] == null || level.getChunkSource().getChunkNow(far.x, far.z) == null) return;
            BlockState s = level.getBlockState(at[0]);
            if (s.getBlock() instanceof SubstationBlock && SignalEdge.ready(level, at[0])) assertDust(level, h, at[1], s.getValue(SubstationBlock.POWERED));
        });
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(level.getChunkSource().getChunkNow(far.x, far.z) != null, "chunk is loading"))
                .thenExecute(() -> {
                    BlockPos floor = top(level, far);
                    at[0] = floor.above();
                    at[1] = floor.above().east();
                    level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(floor.east(), Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(at[0], ModBlocks.SUBSTATION.get().defaultBlockState(), 3);
                    level.setBlock(at[1], Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
                    node[0] = PowerGrid.get(level).nodeAt(at[0]);
                    h.assertTrue(node[0] != null, "the substation did not become a grid node");
                    h.assertTrue(level.getBlockState(at[1]).getValue(RedStoneWireBlock.POWER) == 15, "a working substation does not power the dust");
                    level.getChunkSource().removeRegionTicket(GridGameTests.HOLD, far, 2, far);
                })
                .thenWaitUntil(() -> GridGameTests.unloaded(h, level, far))
                .thenExecute(() -> {
                    Blackouts.knockOut(level, node[0]);
                    h.assertTrue(level.getChunkSource().chunkMap.getVisibleChunkIfPresent(far.toLong()) == null, "the knock-out loaded the chunk");
                    GridGameTests.holdWide(level, far);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(level.getChunkSource().getChunkNow(far.x, far.z) != null, "chunk is loading");
                    h.assertFalse(level.getBlockState(at[0]).getValue(SubstationBlock.POWERED), "the reloaded substation still works");
                })
                .thenExecute(() -> {
                    h.assertTrue(Blackouts.restore(level, null, 0) >= 1, "nothing to restore");
                    h.assertTrue(level.getBlockState(at[0]).getValue(SubstationBlock.POWERED), "a command restore did not bring it back");
                    level.setBlock(at[0], Blocks.AIR.defaultBlockState(), 3);
                })
                .thenSucceed();
    }

    // ---------------------------------------------------------------- helpers

    private static boolean powered(GameTestHelper h, BlockPos substation) {
        return h.getBlockState(substation).getValue(SubstationBlock.POWERED);
    }

    /** Piston base stands extended (while it moves, the base is a {@code moving_piston}: neither extended nor retracted). */
    private static boolean extended(GameTestHelper h, BlockPos piston) {
        BlockState s = h.getBlockState(piston);
        return s.is(Blocks.PISTON) && s.getValue(PistonBaseBlock.EXTENDED);
    }

    private static boolean retracted(GameTestHelper h, BlockPos piston) {
        BlockState s = h.getBlockState(piston);
        return s.is(Blocks.PISTON) && !s.getValue(PistonBaseBlock.EXTENDED);
    }

    /** Dust at {@code dust} (test coordinates) carries exactly the block's signal on this tick. */
    private static void assertDust(GameTestHelper h, BlockPos dust, boolean signal) {
        assertDust(h.getLevel(), h, h.absolutePos(dust), signal);
    }

    /** Same, world coordinates. */
    private static void assertDust(ServerLevel level, GameTestHelper h, BlockPos dust, boolean signal) {
        BlockState s = level.getBlockState(dust);
        h.assertTrue(s.is(Blocks.REDSTONE_WIRE), "no dust at " + dust + ": " + s);
        int power = s.getValue(RedStoneWireBlock.POWER);
        h.assertTrue(power == (signal ? 15 : 0), "dust carries " + power + " beside a block whose signal is " + (signal ? "on" : "off"));
    }

    /** Floor for a fixture in a loaded chunk: three blocks above its highest column, in the chunk's interior. */
    private static BlockPos top(ServerLevel level, ChunkPos p) {
        int top = level.getMinBuildHeight();
        for (int x = p.getMinBlockX(); x <= p.getMaxBlockX(); x++) {
            for (int z = p.getMinBlockZ(); z <= p.getMaxBlockZ(); z++) top = Math.max(top, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
        }
        return new BlockPos(p.getMinBlockX() + 6, top + 3, p.getMinBlockZ() + 6);
    }

    /**
     * The SAM's {@code alarm} as saved in its chunk section's palette on disk (the fixture has one SAM per section); null
     * if absent.
     */
    private static String savedSam(ServerLevel level, ChunkPos chunk, BlockPos pos) {
        Optional<CompoundTag> tag = level.getChunkSource().chunkMap.read(chunk).join();
        if (tag.isEmpty()) return null;
        ListTag sections = tag.get().getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            if (section.getByte("Y") != SectionPos.blockToSectionCoord(pos.getY())) continue;
            ListTag palette = section.getCompound("block_states").getList("palette", Tag.TAG_COMPOUND);
            for (int k = 0; k < palette.size(); k++) {
                CompoundTag entry = palette.getCompound(k);
                if (entry.getString("Name").equals("incoming:sam")) return entry.getCompound("Properties").getString("alarm");
            }
        }
        return null;
    }

    private static boolean alive(ServerLevel level, DroneEntity d) {
        return VirtualFlights.get(level).flights().stream().anyMatch(p -> p.getUUID().equals(d.getUUID()));
    }
}
