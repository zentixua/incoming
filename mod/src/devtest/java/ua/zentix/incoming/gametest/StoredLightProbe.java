package ua.zentix.incoming.gametest;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.neoforged.neoforge.gametest.GameTestHolder;
import ua.zentix.incoming.Incoming;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.InflaterInputStream;

/** Explicitly selected local evidence probe; never opens the owner's world or a network connection. */
@GameTestHolder(Incoming.MOD_ID)
public final class StoredLightProbe {
    private static final Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private StoredLightProbe() {}

    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        String input = System.getenv("INCOMING_LIGHT_DATA");
        if (input == null) return List.of();
        return List.of(new TestFunction("light_disk", "light_disk.stored", "incoming:range", Rotation.NONE,
                1200, 0, true, false, 1, 1, true, h -> measure(h, Path.of(input))));
    }

    private static void measure(GameTestHelper h, Path root) {
        try {
            List<String> chunks = new ArrayList<>();
            chunks.add("copy\tx\tz\tregion_timestamp\tdata_version\tisLightOn\tLastUpdate\tInhabitedTime\tattachments");
            BlockLightReference live = read(h, root.resolve("live"), chunks);
            BlockLightReference golden = read(h, root.resolve("golden"), chunks);
            Files.write(root.resolve("chunks.tsv"), chunks);
            LevelLightEngine expected = live.engine(false), saved = live.engine(true);
            var goldenGauge = golden.compare(new ChunkPos(-64, 28), 2);
            List<BlockPos> removed = new ArrayList<>();
            List<String> emitters = new ArrayList<>();
            emitters.add("x\ty\tz\tgolden\tlive\tgolden_emission\tlive_emission\tstored\treference\tclassification");
            Set<BlockPos> stale = new HashSet<>();
            List<String> cells = new ArrayList<>();
            cells.add("x\ty\tz\tstored\treference\tlive\tgolden");
            int over = 0, under = 0, total = 0, gauge = 0;
            for (var tile : live.tiles.values()) {
                var old = golden.tiles.get(tile.pos);
                boolean inside = tile.pos.x >= -66 && tile.pos.x <= -62 && tile.pos.z >= 26 && tile.pos.z <= 30;
                for (int i = 0; i < tile.blocks.length; i++) {
                    BlockPos p = tile.position(i);
                    int a = tile.stored[i], b = value(expected, p);
                    if (a != value(saved, p)) gauge++;
                    int before = old.blocks[i].getLightEmission(golden, p), now = tile.blocks[i].getLightEmission(live, p);
                    if (before > 0 && now < before) removed.add(p);
                    if (before > 0 || now > 0) emitters.add(row(p, old.blocks[i], tile.blocks[i], before, now, a, b,
                            before == 0 && now > 0 ? "player-placed" : before > now ? "removed-or-dimmed" : "retained"));
                    if (!inside) continue;
                    total++;
                    if (a > b) { over++; stale.add(p); }
                    if (a < b) under++;
                    if (a != b) cells.add(row(p, a, b, tile.blocks[i], old.blocks[i]));
                }
            }
            h.assertTrue(gauge == 0, "Saved light gauge differs from NBT in " + gauge + " cells");
            Files.write(root.resolve("emitters.tsv"), emitters);
            Files.write(root.resolve("mismatches.tsv"), cells);
            List<BlockPos> peaks = new ArrayList<>();
            List<String> groups = new ArrayList<>();
            groups.add("group\tcells\tchunks\tpeak_x\tpeak_y\tpeak_z\tstored\treference\tgolden\tlive\tgolden_emission\tlive_emission");
            Set<BlockPos> left = new HashSet<>(stale);
            int group = 0;
            while (!left.isEmpty()) {
                group++;
                BlockPos start = left.iterator().next(); left.remove(start);
                ArrayDeque<BlockPos> queue = new ArrayDeque<>(); queue.add(start);
                List<BlockPos> component = new ArrayList<>(), localPeaks = new ArrayList<>();
                Set<ChunkPos> crossed = new HashSet<>();
                while (!queue.isEmpty()) {
                    BlockPos p = queue.remove(); component.add(p); crossed.add(new ChunkPos(p));
                    boolean peak = true;
                    for (Direction d : Direction.values()) {
                        BlockPos next = p.relative(d);
                        if (value(saved, next) > value(saved, p)) peak = false;
                        if (left.remove(next)) queue.add(next);
                    }
                    if (peak) { peaks.add(p); localPeaks.add(p); }
                }
                for (BlockPos p : localPeaks) groups.add(group + "\t" + component.size() + "\t" + crossed + "\t"
                        + row(p, value(saved, p), value(expected, p), golden.getBlockState(p), live.getBlockState(p),
                        golden.getBlockState(p).getLightEmission(golden, p), live.getBlockState(p).getLightEmission(live, p)));
                if (localPeaks.isEmpty()) groups.add(group + "\t" + component.size() + "\t" + crossed + "\tno-local-peak-in-measured-window");
            }
            Files.write(root.resolve("groups.tsv"), groups);
            for (BlockPos p : removed) saved.checkBlock(p);
            BlockLightReference.drain(saved);
            int afterRemoved = excess(live, saved, expected);
            LevelLightEngine peakChecks = live.engine(true);
            for (BlockPos p : peaks) peakChecks.checkBlock(p);
            BlockLightReference.drain(peakChecks);
            int afterPeaks = excess(live, peakChecks, expected);
            LevelLightEngine borders = live.engine(true);
            int borderChecks = 0;
            for (var tile : live.tiles.values()) for (int i = 0; i < tile.blocks.length; i++) {
                int x = i & 15, z = i / 16 & 15;
                if (tile.stored[i] > 0 && (x == 0 || x == 15 || z == 0 || z == 15)) {
                    borders.checkBlock(tile.position(i)); borderChecks++;
                }
            }
            BlockLightReference.drain(borders);
            int afterBorders = excess(live, borders, expected);
            LevelLightEngine goldenExpected = golden.engine(false), restoreSources = live.engine(true);
            int deficientSources = 0, gridSources = 0, overlap = 0, newDeficit = 0, remainingDeficit = 0;
            for (var tile : live.tiles.values()) for (int i = 0; i < tile.blocks.length; i++) {
                BlockPos p = tile.position(i);
                if (tile.blocks[i].getLightEmission(live, p) > tile.stored[i]) {
                    deficientSources++;
                    if (ua.zentix.incoming.grid.GridLights.isLit(tile.blocks[i])) gridSources++;
                    restoreSources.checkBlock(p);
                }
            }
            BlockLightReference.drain(restoreSources);
            for (var tile : live.tiles.values()) {
                if (tile.pos.x < -66 || tile.pos.x > -62 || tile.pos.z < 26 || tile.pos.z > 30) continue;
                var old = golden.tiles.get(tile.pos);
                for (int i = 0; i < tile.blocks.length; i++) {
                    BlockPos p = tile.position(i);
                    if (tile.stored[i] >= value(expected, p)) continue;
                    if (old.stored[i] < value(goldenExpected, p)) overlap++; else newDeficit++;
                    if (value(restoreSources, p) < value(expected, p)) remainingDeficit++;
                }
            }
            List<String> remaining = new ArrayList<>();
            remaining.add("x\ty\tz\tstored\tafter_source_checks\treference\tlive\tgolden");
            for (var tile : live.tiles.values()) {
                if (tile.pos.x < -66 || tile.pos.x > -62 || tile.pos.z < 26 || tile.pos.z > 30) continue;
                for (int i = 0; i < tile.blocks.length; i++) {
                    BlockPos p = tile.position(i);
                    if (tile.stored[i] < value(expected, p) && value(restoreSources, p) < value(expected, p))
                        remaining.add(row(p, tile.stored[i], value(restoreSources, p), value(expected, p), tile.blocks[i], golden.getBlockState(p)));
                }
            }
            Files.write(root.resolve("remaining-missing.tsv"), remaining);
            for (var tile : live.tiles.values()) for (int i = 0; i < tile.blocks.length; i++) {
                BlockPos p = tile.position(i);
                if (tile.blocks[i].getLightEmission(live, p) > 0) borders.checkBlock(p);
            }
            BlockLightReference.drain(borders);
            int missingAfterCombined = 0;
            for (var tile : live.tiles.values()) {
                if (tile.pos.x < -66 || tile.pos.x > -62 || tile.pos.z < 26 || tile.pos.z > 30) continue;
                for (int i = 0; i < tile.blocks.length; i++) if (value(borders, tile.position(i)) < value(expected, tile.position(i))) missingAfterCombined++;
            }
            Files.writeString(root.resolve("missing-light.txt"), "live_missing=" + under + " golden_missing=" + goldenGauge.under()
                    + " overlap=" + overlap + " new_missing=" + newDeficit + " deficient_sources_7x7=" + deficientSources
                    + " grid_lamp_sources=" + gridSources + " missing_after_source_checks=" + remainingDeficit + " missing_after_borders_and_emitters=" + missingAfterCombined + "\n");
            String summary = "cells=" + total + " overlit=" + over + " underlit=" + under + " groups=" + group
                    + " peaks=" + peaks.size() + " gauge_mismatches=" + gauge + " removed_emitter_checks=" + removed.size()
                    + " overlit_after_removed_checks=" + afterRemoved + " overlit_after_peak_checks=" + afterPeaks
                    + " border_checks=" + borderChecks + " overlit_after_border_checks=" + afterBorders
                    + " golden_overlit=" + goldenGauge.over() + " golden_underlit=" + goldenGauge.under();
            Files.writeString(root.resolve("summary.txt"), summary + "\n");
            Incoming.LOG.info("LIGHT_DISK {}", summary);
            h.succeed();
        } catch (IOException e) { throw new IllegalStateException("Stored-light probe failed", e); }
    }

    private static int excess(BlockLightReference blocks, LevelLightEngine a, LevelLightEngine b) {
        int n = 0;
        for (var tile : blocks.tiles.values()) {
            if (tile.pos.x < -66 || tile.pos.x > -62 || tile.pos.z < 26 || tile.pos.z > 30) continue;
            for (int i = 0; i < tile.blocks.length; i++) if (value(a, tile.position(i)) > value(b, tile.position(i))) n++;
        }
        return n;
    }

    private static int value(LevelLightEngine engine, BlockPos p) { return engine.getLayerListener(LightLayer.BLOCK).getLightValue(p); }
    private static String row(BlockPos p, Object... fields) {
        StringBuilder out = new StringBuilder(p.getX() + "\t" + p.getY() + "\t" + p.getZ());
        for (Object field : fields) out.append('\t').append(field);
        return out.toString();
    }

    private static BlockLightReference read(GameTestHelper h, Path folder, List<String> metadata) throws IOException {
        BlockLightReference view = new BlockLightReference(-64, 384);
        for (int z = 25; z <= 31; z++) for (int x = -67; x <= -61; x++) {
            ChunkPos pos = new ChunkPos(x, z);
            Path file = folder.resolve("r." + (x >> 5) + "." + (z >> 5) + ".mca");
            CompoundTag raw;
            long timestamp;
            try (RandomAccessFile region = new RandomAccessFile(file.toFile(), "r")) {
                int slot = (x & 31) + (z & 31) * 32;
                region.seek(slot * 4L); int location = region.readInt();
                if (location == 0) throw new IOException("Missing chunk " + pos + " in " + file);
                region.seek(4096 + slot * 4L); timestamp = Integer.toUnsignedLong(region.readInt());
                region.seek((location >>> 8) * 4096L); int length = region.readInt(), compression = region.readUnsignedByte();
                if (compression != 2) throw new IOException("Expected vanilla zlib region stream, got " + compression);
                byte[] bytes = new byte[length - 1]; region.readFully(bytes);
                try (DataInputStream input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(bytes)))) {
                    raw = NbtIo.read(input, NbtAccounter.unlimitedHeap());
                }
            }
            int version = ChunkStorage.getVersion(raw);
            CompoundTag tag = h.getLevel().getChunkSource().chunkMap.upgradeChunkTag(h.getLevel().dimension(), () -> null,
                    raw.copy(), Optional.empty());
            metadata.add(folder.getFileName() + "\t" + x + "\t" + z + "\t" + timestamp + "\t" + version + "\t"
                    + tag.getBoolean("isLightOn") + "\t" + tag.getLong("LastUpdate") + "\t" + tag.getLong("InhabitedTime")
                    + "\t" + tag.getCompound("neoforge:attachments"));
            var tile = view.add(pos);
            var sections = tag.getList("sections", Tag.TAG_COMPOUND);
            for (int s = 0; s < sections.size(); s++) {
                CompoundTag section = sections.getCompound(s);
                int sy = section.getByte("Y");
                if (sy < -4 || sy >= 20) continue;
                PalettedContainer<BlockState> states = null;
                if (section.contains("block_states")) {
                    var palette = section.getCompound("block_states").getList("palette", Tag.TAG_COMPOUND);
                    for (int k = 0; k < palette.size(); k++) {
                        String name = palette.getCompound(k).getString("Name");
                        if (!BuiltInRegistries.BLOCK.containsKey(net.minecraft.resources.ResourceLocation.parse(name)))
                            throw new IOException("Unregistered block " + name + " in " + pos);
                    }
                    states = STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")).getOrThrow();
                }
                byte[] light = section.getByteArray("BlockLight");
                for (int i = 0; i < 4096; i++) {
                    int index = (sy + 4) * 4096 + i;
                    if (states != null) tile.blocks[index] = states.get(i & 15, i >> 8, i >> 4 & 15);
                    if (light.length != 0) tile.stored[index] = (byte) ((light[i >> 1] >> ((i & 1) * 4)) & 15);
                }
            }
        }
        return view;
    }
}
