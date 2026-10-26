package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.flash.FlashChar;
import ua.zentix.incoming.nuclear.flash.FlashMarks;
import ua.zentix.incoming.nuclear.flash.FlashShadows;
import ua.zentix.incoming.nuclear.flash.FlashSync;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.nuclear.world.NuclearWorld;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.Locale;

/**
 * Traces of the flash (DESIGN-nuke §3.5): burning on the edges of the ball - with the ruins of the chunk, the silhouette of a body on the wall behind it, the mark of the chunk
 *  survives the save, leaves with the block and goes to the player to whom the chunk is given. The walls are made of logs (without leaves this is not a
 *  tree: a wave does not knock them down) and obsidian (does not break). Each check has its own batch (the explosion affects neighbors).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FlashGameTests {
    private static final BlockPos CENTER = NuclearGameTests.CENTER;
    /** The wall is in a chunk whose neighbors are also on the site (x and z from 16 to 47): they too are ready on time. */
    private static final int WALL_DX = 12;

    private FlashGameTests() {}

    /** Log wall 5×4 facing the explosion (to the west): logs without leaves are not a tree, the wave does not knock them down. */
    private static void wall(GameTestHelper h) {
        for (int dz = -2; dz <= 2; dz++) {
            for (int y = 0; y < 4; y++) h.setBlock(CENTER.east(WALL_DX).south(dz).above(y), Blocks.OAK_LOG);
        }
    }

    /** 15 kt in the air above the middle, 8 psi on the wall: the light there is tens of cal/cm². */
    private static Detonation burst(GameTestHelper h) {
        return NuclearGameTests.atPsi(h, CENTER, true, CENTER.east(WALL_DX).above(1), 8);
    }

    private static int marked(FlashMarks m, int detonation, BlockPos abs, Direction face, boolean shadows) {
        for (FlashMarks.Layer l : m.layers()) {
            if (l.detonation() != detonation) continue;
            int key = FlashChar.key(abs.getX() & 15, abs.getY(), abs.getZ() & 15, face);
            for (int c : shadows ? l.shadows() : l.chars()) if ((c & FlashChar.KEY_MASK) == key) return FlashChar.level(c);
        }
        return 0;
    }

    /**
     * Burnt with ruins: the edges of the boards to the ball were darkened, the backs were not; the edge covered closely by the stone and the edge in the shadow of the high wall are clean. The mark survives the recording of the chunk (as if on a disk), the removed block leaves the traces, and the player
     *  to whom the chunk is given receives the traces in a package, which is parsed back into the same thing.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_flash_chars", skyAccess = true)
    public static void charredFacesTowardFireball(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        wall(h);
        // closed face: obsidian close to west
        BlockPos covered = CENTER.east(WALL_DX).south(6);
        h.setBlock(covered, Blocks.OAK_LOG);
        h.setBlock(covered.west(), Blocks.OBSIDIAN);
        // edge in shadow: log behind high obsidian wall
        BlockPos shaded = CENTER.east(WALL_DX).north(6);
        h.setBlock(shaded, Blocks.OAK_LOG);
        for (int dz = -2; dz <= 2; dz++) for (int y = 0; y < 20; y++) h.setBlock(shaded.west(2).south(dz).above(y), Blocks.OBSIDIAN);
        Detonation d = burst(h);
        double q = d.fluence(Vec3.atCenterOf(h.absolutePos(CENTER.east(WALL_DX).above(1))));
        h.assertTrue(q > 15, String.format(Locale.ROOT, "light on the wall %.1f cal/cm² - weak for testing", q));
        NuclearGameTests.scarAll(h, d, new ColumnScar.Budget(true));
        int lit = 0, faces = 0;
        for (int dz = -2; dz <= 2; dz++) {
            for (int y = 0; y < 4; y++) {
                BlockPos at = CENTER.east(WALL_DX).south(dz).above(y);
                h.assertBlockPresent(Blocks.OAK_LOG, at);
                BlockPos abs = h.absolutePos(at);
                FlashMarks m = FlashSync.of(level.getChunkAt(abs));
                faces++;
                if (marked(m, d.id(), abs, Direction.WEST, false) > 0) lit++;
                if (marked(m, d.id(), abs, Direction.EAST, false) > 0) h.fail("the back of the wall is charred " + at.toShortString());
            }
        }
        h.assertTrue(lit >= faces * 3 / 4, "charred edges to the ball " + lit + " from " + faces + String.format(Locale.ROOT, " (%.1f cal/cm²)", q));
        BlockPos coveredAbs = h.absolutePos(covered), shadedAbs = h.absolutePos(shaded);
        if (marked(FlashSync.of(level.getChunkAt(coveredAbs)), d.id(), coveredAbs, Direction.WEST, false) > 0) h.fail("charred edge under the stone");
        if (marked(FlashSync.of(level.getChunkAt(shadedAbs)), d.id(), shadedAbs, Direction.WEST, false) > 0) h.fail("charred edge in the shadow of the wall");

        // chunk entry: mark - in chunk data and read back the same
        BlockPos probe = CENTER.east(WALL_DX).above(1);
        LevelChunk chunk = level.getChunkAt(h.absolutePos(probe));
        FlashMarks before = FlashSync.of(chunk);
        CompoundTag saved = ChunkSerializer.write(level, chunk);
        Tag attachment = saved.getCompound(net.neoforged.neoforge.attachment.AttachmentHolder.ATTACHMENTS_NBT_KEY).get(Incoming.MOD_ID + ":flash_marks");
        h.assertTrue(attachment != null, "there are no traces in the chunk record: " + saved.getCompound(net.neoforged.neoforge.attachment.AttachmentHolder.ATTACHMENTS_NBT_KEY).getAllKeys());
        FlashMarks read = FlashMarks.CODEC.parse(NbtOps.INSTANCE, attachment).getOrThrow();
        h.assertTrue(same(before, read), "the traces after the recording are not the same");

        // package to the player to whom the chunk is given - the same traces
        S2C.NukeFlashMarks packet = FlashSync.packet(chunk);
        h.assertTrue(packet != null, "no trace of the package");
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        S2C.NukeFlashMarks.CODEC.encode(buf, packet);
        S2C.NukeFlashMarks back = S2C.NukeFlashMarks.CODEC.decode(buf);
        h.assertTrue(back.chunk() == chunk.getPos().toLong() && same(back.marks(), before), "the package of traces was sorted out in the wrong way");

        // the removed block leaves the traces
        BlockPos gone = h.absolutePos(probe);
        h.assertTrue(marked(before, d.id(), gone, Direction.WEST, false) > 0, "the board being removed has no trace");
        level.setBlock(gone, Blocks.AIR.defaultBlockState(), 2);
        S2C.NukeFlashMarks after = FlashSync.packet(chunk);
        FlashMarks left = after == null ? FlashMarks.EMPTY : after.marks();
        h.assertTrue(marked(left, d.id(), gone, Direction.WEST, false) == 0, "a trace of the removed block remains");
        h.assertTrue(marked(FlashSync.of(chunk), d.id(), gone, Direction.WEST, false) == 0, "the trace of the removed block remains in the mark");

        // notifying neighbors without changing the unit (lever on the wall, wire) does not touch the trace
        BlockPos kept = null;
        for (int dz = -2; dz <= 2 && kept == null; dz++) {
            for (int y = 0; y < 4 && kept == null; y++) {
                BlockPos at = h.absolutePos(CENTER.east(WALL_DX).south(dz).above(y));
                if (!at.equals(gone) && level.getChunkAt(at) == chunk && marked(FlashSync.of(chunk), d.id(), at, Direction.WEST, false) > 0) kept = at;
            }
        }
        h.assertTrue(kept != null, "there is no second log with a trace in the wall chunk");
        level.updateNeighborsAt(kept, Blocks.OAK_LOG);
        h.assertTrue(marked(FlashSync.of(chunk), d.id(), kept, Direction.WEST, false) > 0, "the trace went away from notifying neighbors without changing the block");
        // broke and immediately put up the same log (as a player: with the notification of neighbors) - the new log is clean
        level.destroyBlock(kept, false);
        level.setBlock(kept, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        h.assertTrue(marked(FlashSync.of(chunk), d.id(), kept, Direction.WEST, false) == 0, "the trail returned to the newly placed log");
        S2C.NukeFlashMarks resent = FlashSync.packet(chunk);
        h.assertTrue(resent == null || marked(resent.marks(), d.id(), kept, Direction.WEST, false) == 0, "the trace of the newly placed log has gone to the player");
        h.succeed();
    }

    /**
     * Silhouette: an armor rack (a human frame) one and a half blocks in front of the log wall towards the ball - on the wall behind it there are edges
     *  with shadow, there are cells in full shadow and they lie on the continuation of the ray from the ball through the body; the edges of the wall to the side - without shadow.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_flash_shadow", skyAccess = true)
    public static void silhouetteBehindBody(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (int dz = -4; dz <= 4; dz++) {
            for (int y = 0; y < 5; y++) h.setBlock(CENTER.east(WALL_DX).south(dz).above(y), Blocks.OAK_LOG);
        }
        Detonation d = burst(h);
        BlockPos standAt = CENTER.east(WALL_DX - 2);
        Vec3 feet = Vec3.atBottomCenterOf(h.absolutePos(standAt)).add(0.5, 0, 0);
        ArmorStand stand = EntityType.ARMOR_STAND.create(level);
        h.assertTrue(stand != null, "no stand");
        stand.moveTo(feet.x, feet.y, feet.z, 90, 0);
        stand.setYBodyRot(90);
        level.addFreshEntity(stand);
        FlashShadows job = FlashShadows.of(level, d);
        h.assertTrue(job != null, "no bodies were found in front of the wall");
        stand.discard();
        WorkClock clock = WorkClock.counting(1_000_000L);
        int ticks = 0;
        do {
            clock.start(30_000_000L);
        } while (!job.work(level, clock) && ++ticks < 100);
        // where the beam from the ball through the middle of the body hits the wall
        Vec3 c = feet.add(0, stand.getBbHeight() / 2, 0), dir = c.subtract(d.burst()).normalize();
        double wallX = h.absolutePos(CENTER.east(WALL_DX)).getX();
        Vec3 hit = c.add(dir.scale((wallX - c.x) / dir.x));
        int shadowFaces = 0, full = 0;
        BlockPos hitBlock = BlockPos.containing(wallX + 0.5, hit.y, hit.z);
        for (int dz = -4; dz <= 4; dz++) {
            for (int y = 0; y < 5; y++) {
                BlockPos abs = h.absolutePos(CENTER.east(WALL_DX).south(dz).above(y));
                FlashMarks m = FlashSync.of(level.getChunkAt(abs));
                for (FlashMarks.Layer l : m.layers()) {
                    if (l.detonation() != d.id()) continue;
                    for (int k = 0; k < l.shadows().length; k++) {
                        int s = l.shadows()[k];
                        if ((s & FlashChar.KEY_MASK) != FlashChar.key(abs.getX() & 15, abs.getY(), abs.getZ() & 15, Direction.WEST)) continue;
                        shadowFaces++;
                        if (Math.abs(abs.getZ() - hitBlock.getZ()) > 2 || Math.abs(abs.getY() - hitBlock.getY()) > 3) {
                            h.fail("shadow far from the body: " + abs.toShortString() + ", the beam rests on " + hitBlock.toShortString());
                        }
                        for (int u = 0; u < FlashMarks.CELLS; u++) for (int v = 0; v < FlashMarks.CELLS; v++) if (l.shade(k, u, v) == 3) full++;
                    }
                }
            }
        }
        h.assertTrue(shadowFaces > 0, "there is no silhouette on the wall (the ray from the ball through the body is in " + hitBlock.toShortString() + ")");
        h.assertTrue(full >= 20, "cells in full shade " + full + " - empty silhouette");
        h.succeed();
    }

    /**
     * A scheduled strike (ICBM), the ruins of which were prepared in advance: smoke and silhouette - in one layer with the number and time of the explosion
     * . The ruins are built in advance at the place of the explosion without number and time ({@code NuclearWarhead.geometry}), and the burnt-out
     *  appears as a layer of explosion, which puts the ruins: with a layer of place (−1, time 0), the silhouette would not lie on the burnt-out of its
     *  explosion, the repeat would show the burnt-out before the flash, and the second one would be like that the blow would have replaced the burning of the first. Wall and stand - as in
     * {@link #silhouetteBehindBody}, scale - 8 psi on the wall near the blast above the middle.
     */
    @GameTest(template = "range", timeoutTicks = 3000, batch = "nuke_flash_prep", skyAccess = true)
    public static void preparedStrikeMarksOneLayer(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        NuclearGameTests.detonation(h, CENTER, 0, 15, 0.05f); // only backfilling of the layer under the site: blasting - according to schedule
        for (int dz = -4; dz <= 4; dz++) {
            for (int y = 0; y < 5; y++) h.setBlock(CENTER.east(WALL_DX).south(dz).above(y), Blocks.OAK_LOG);
        }
        ArmorStand stand = EntityType.ARMOR_STAND.create(level);
        h.assertTrue(stand != null, "no stand");
        Vec3 feet = Vec3.atBottomCenterOf(h.absolutePos(CENTER.east(WALL_DX - 2))).add(0.5, 0, 0);
        stand.moveTo(feet.x, feet.y, feet.z, 90, 0);
        stand.setYBodyRot(90);
        level.addFreshEntity(stand);
        Vec3 target = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        Vec3 wallAt = Vec3.atCenterOf(h.absolutePos(CENTER.east(WALL_DX).above(1)));
        double lo = 0.002, hi = 1;
        for (int i = 0; i < 40; i++) {
            double mid = Math.sqrt(lo * hi);
            if (NuclearWarhead.geometry(level, target, 15, true, (float) mid).psi(wallAt) < 8) lo = mid;
            else hi = mid;
        }
        double scale = IncomingConfig.SERVER.nukeEffectsScale.get();
        IncomingConfig.SERVER.nukeEffectsScale.set(hi);
        NuclearWorld w = NuclearWorld.get(level);
        NuclearWorld.useClock(level.getServer(), WorkClock.counting(1_000_000L));
        java.util.List<String> handOffs = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Runnable unwatch = NuclearGameTests.watchLog(line -> {
            if (line.contains("pre-built ruins")) handOffs.add(line);
        });
        Runnable restore = () -> {
            unwatch.run();
            IncomingConfig.SERVER.nukeEffectsScale.set(scale);
            NuclearWorld.useClock(level.getServer(), new WorkClock());
            NuclearStrikes.clear(level);
            stand.discard();
        };
        NuclearEvents events = NuclearEvents.get(level);
        long now = level.getGameTime();
        events.schedule(new NuclearEvents.ScheduledStrike(events.nextId(), target, 15, true, now, now + 300, target, java.util.Optional.empty(), false));
        BlockPos wallPos = h.absolutePos(CENTER.east(WALL_DX));
        boolean[] checked = {false};
        long[] tickAt = {System.nanoTime()};
        h.onEachTick(() -> {
            Detonation d = events.detonations().stream().filter(e -> e.burst().distanceTo(target) < 400).findFirst().orElse(null);
            String failure = null;
            if (d == null) {
                if (level.getGameTime() > now + 1150) failure = "there was no explosion";
            } else if (!checked[0]) {
                RuinBackgroundGameTests.gamePace(tickAt);
                LevelChunk wall = level.getChunkSource().getChunkNow(wallPos.getX() >> 4, wallPos.getZ() >> 4);
                FlashMarks m = wall == null ? FlashMarks.EMPTY : FlashSync.of(wall);
                boolean scarred = wall != null && wall.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) >= d.id();
                boolean shadows = m.layers().stream().anyMatch(l -> l.detonation() == d.id() && l.shadows().length > 0);
                if (!scarred || !shadows) {
                    if (level.getGameTime() < d.gameTime() + 2400) return;
                    failure = "for 2400 tick after the explosion: wall ruins " + scarred + ", silhouettes " + shadows;
                } else {
                    checked[0] = true;
                    StringBuilder layers = new StringBuilder();
                    for (FlashMarks.Layer l : m.layers()) {
                        layers.append(" #").append(l.detonation()).append(" t ").append(l.time()).append(": ").append(l.chars().length).append(" charred, ")
                                .append(l.shadows().length).append(" silhouettes;");
                    }
                    FlashMarks.Layer layer = m.layers().stream().filter(l -> l.detonation() == d.id()).findFirst().orElseThrow();
                    if (handOffs.stream().noneMatch(line -> line.startsWith("Detonation #" + d.id() + ":"))) failure = "the ruins were not prepared in advance: the explosion did not take preparation";
                    else if (m.layers().stream().anyMatch(l -> l.detonation() < 0) || layer.time() != d.gameTime() || layer.chars().length == 0) {
                        failure = "smoke and silhouettes of the wall - more than one layer of explosion #" + d.id() + " (time " + d.gameTime() + "):" + layers;
                    }
                }
            } else if (w.plannedChunks() > 0 || w.prepTiles() > 0) {
                // preparation releases squares according to readings and background plans - according to the wall clock
                RuinBackgroundGameTests.gamePace(tickAt);
                if (level.getGameTime() < d.gameTime() + 2400) return;
                failure = "preparation did not release the squares: left " + w.plannedChunks() + ", squares " + w.prepTiles();
            } else {
                restore.run();
                h.succeed();
                return;
            }
            if (failure != null) {
                restore.run();
                throw new net.minecraft.gametest.framework.GameTestAssertException(failure);
            }
        });
    }

    /** The same traces (layers, marks, masks). */
    private static boolean same(FlashMarks a, FlashMarks b) {
        if (a.layers().size() != b.layers().size()) return false;
        for (int i = 0; i < a.layers().size(); i++) {
            FlashMarks.Layer x = a.layers().get(i), y = b.layers().get(i);
            if (x.detonation() != y.detonation() || x.time() != y.time() || !java.util.Arrays.equals(x.chars(), y.chars())
                    || !java.util.Arrays.equals(x.shadows(), y.shadows()) || !java.util.Arrays.equals(x.masks(), y.masks())) return false;
        }
        return true;
    }
}
