package ua.zentix.incoming.nuclear.world;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.material.*;
import ua.zentix.incoming.util.RegionFiles;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MaterialCoastGameTests {
    private MaterialCoastGameTests() {}

    /** Authored paletted chunks with one uniform surface section, no biomes or height maps. */
    private static CompoundTag chunk(String block) {
        var tag = new CompoundTag();
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        tag.putString("Status", "minecraft:full");
        var state = new CompoundTag();
        state.putString("Name", block);
        var palette = new ListTag();
        palette.add(state);
        var storage = new CompoundTag();
        storage.put("palette", palette);
        var section = new CompoundTag();
        section.putByte("Y", (byte) 4);
        section.put("block_states", storage);
        var sections = new ListTag();
        sections.add(section);
        tag.put("sections", sections);
        return tag;
    }

    @GameTest(template = "range", batch = "nuke_material_coast", timeoutTicks = 6000)
    public static void seaBeyondPreparationUsesItsOwnStoredSurface(GameTestHelper h) {
        var level = h.getLevel();
        var centre = h.absolutePos(BlockPos.ZERO).offset(35000, 0, 35000);
        int x = centre.getX(), z = centre.getZ(), shore = x + 1500;
        var d = new Detonation(1, new Vec3(x + .5, 65, z + .5), 65, 15, true, 0, 0, 0, 20000, 1, 1f, false, .35f);
        long[] order = MaterialSurvey.sparse(d);
        var grid = new DustGrid(DustGrid.Bounds.of(d));
        var water = chunk("minecraft:water");
        var land = chunk("minecraft:grass_block");
        CompletableFuture<?>[] writes = new CompletableFuture<?>[order.length];
        for (int i = 0; i < order.length; i++) {
            var p = new ChunkPos(order[i]);
            var tag = (p.getMiddleBlockX() >= shore ? water : land).copy();
            tag.putInt("xPos", p.x);
            tag.putInt("zPos", p.z);
            writes[i] = RegionFiles.write(level, p, tag);
        }
        var saved = CompletableFuture.allOf(writes);
        var bytes = new AtomicLong();
        var live = new LoadedSurfaces();
        live.put(ChunkPos.asLong(x >> 4, z >> 4), 4 << (4 * DustMaterial.SOIL.ordinal()));
        MaterialSurvey[] src = {null};
        long[] peak = {0}, start = {0};
        int[] atBurst = {-1};
        StrikeGameTests.gameSpeed(h);
        h.onEachTick(() -> {
            if (!saved.isDone()) return;
            saved.join();
            long now = level.getGameTime();
            if (src[0] == null) {
                start[0] = now;
                src[0] = new MaterialSurvey(level, d, bytes);
                src[0].begin(level, now + 240, now, live);
            }
            var s = src[0];
            s.advance(now);
            s.publish();
            int available = NuclearPrep.MATERIAL_WINDOW - (int) (bytes.get() / NuclearPrep.READ_BYTES);
            s.work(level, System.nanoTime() + 450_000, available);
            peak[0] = Math.max(peak[0], bytes.get());
            if (now >= start[0] + 240 && atBurst[0] < 0) atBurst[0] = s.storedAnswers;
            if (s.birth.surveying()) return;
            s.close();
            var map = MaterialMap.decode(s.birth.encoded().bytes());
            int sea = 0, wrong = 0;
            for (long key : order) {
                var p = new ChunkPos(key);
                if (p.getMiddleBlockX() >= shore) {
                    sea++;
                    if (map.material(p.getMiddleBlockX(), p.getMiddleBlockZ()) != DustMaterial.WATER) wrong++;
                }
            }
            Incoming.LOG.info("MATERIALCOAST cells {} sparse {} own {} submitted {} stored {} atBurst {} sea {} wrong {} peakBytes {}",
                    grid.count(), order.length, s.birth.ownCells(), s.sparseReads, s.storedAnswers, atBurst[0], sea, wrong, peak[0]);
            if (s.birth.ownCells() < .95 * order.length || s.birth.ownCells() < .85 * grid.count() || wrong > 0)
                h.fail("Coast coverage " + s.birth.ownCells() + "/" + order.length + ", wrong sea " + wrong + "/" + sea);
            h.assertTrue(sea > 1000 && wrong == 0 && atBurst[0] >= 0 && atBurst[0] < s.storedAnswers, "sea class or post-burst coverage");
            h.assertTrue(peak[0] <= NuclearPrep.MATERIAL_WINDOW * NuclearPrep.READ_BYTES && bytes.get() == 0, "unbounded sparse requests");
            var far = new ChunkPos(order[order.length - 1]);
            h.assertTrue(level.getChunkSource().getChunkNow(far.x, far.z) == null, "survey loaded a disk chunk");
            h.succeed();
        });
    }
}
