package ua.zentix.incoming.gametest;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.model.Yield;
import ua.zentix.incoming.nuclear.world.ChunkSendGate;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.nuclear.world.FlashScorch;
import ua.zentix.incoming.nuclear.world.NuclearPrep;
import ua.zentix.incoming.nuclear.world.NuclearWorld;
import ua.zentix.incoming.nuclear.world.RuinPlan;
import ua.zentix.incoming.nuclear.world.RuinPlanner;
import ua.zentix.incoming.nuclear.world.RuinWorkers;
import ua.zentix.incoming.nuclear.world.ScarQueue;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Ruins in the background ({@code RuinWorkers}): the background is the same as in the server thread; solvers do not call the world and properties of
 *  blocks past snapshots; The chunk that the wave has reached does not go to the player until it reaches its ruins.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinBackgroundGameTests {
    private RuinBackgroundGameTests() {}

    /**
     * A dense city (as in the price measurement) and a block Create with a block entity in it: the plan of each internal chunk with background
     *  threads - place to place is the same as in the server flow.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "nuke_background", skyAccess = true)
    public static void backgroundPlanMatchesServerPlan(GameTestHelper h) {
        RuinCostGameTests.city(h);
        ServerLevel level = h.getLevel();
        // block Create (if it is in the assembly): a shaft with a block entity in the wall of the house
        Block shaft = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "shaft"));
        if (shaft != Blocks.AIR) h.setBlock(new BlockPos(24, 6, 18), shaft);
        Detonation d = NuclearGameTests.atPsi(h, new BlockPos(-600, 0, 32), true, new BlockPos(32, 1, 32), 12);
        int compared = 0, cells = 0, charred = 0;
        for (int rx : new int[]{24, 40}) {
            for (int rz : new int[]{24, 40}) {
                ChunkPos c = new ChunkPos(h.absolutePos(new BlockPos(rx, 1, rz)));
                for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                    for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) level.getChunk(c.x + dx, c.z + dz);
                }
                LevelChunk chunk = level.getChunk(c.x, c.z);
                RuinPlan server = RuinPlanner.planFresh(level, d, chunk, false), background = RuinPlanner.planFresh(level, d, chunk, true);
                String diff = server.differs(background);
                h.assertTrue(diff == null, "chunk background " + c + " not the same as in the server thread:" + diff);
                diff = flashDiffers(server, background);
                h.assertTrue(diff == null, "fumes flash background chunk " + c + " not the one in the server stream:" + diff);
                compared++;
                cells += server.changedBlocks();
                charred += server.flash().length;
            }
        }
        h.assertTrue(cells > 0, "the city is not destroyed: there is nothing to compare");
        h.assertTrue(charred > 0, "there are no burning flashes in the city: there is nothing to compare");
        Incoming.LOG.info("Ruins in the background: {} plans coincided, {} places, {} charred edges", compared, cells, charred);
        h.succeed();
    }

    /**
     * Everything that goes in the background threads of the ruins does not call the world, chunk, settings and block state methods, except for reading
     *  properties ({@code getBlock}, {@code getValue}, {@code hasProperty}, {@code getFluidState}, {@code setValue},
     * {@code isAir}): the rest is from the property table filled in the server stream. Traversal - according to the call graph from the roots of
     *  background tasks (plan: {@code Collapse.solve}, {@code Blast.solve}, {@code RuinPlanner.finish}, task lambdas
     * {@code RuinContext}; trace without ruins and flash layer: {@code RuinPlanner.scorch}, {@code RuinPlanner.flash}; parsing
     *  chunk from disk: {@code DiskShots.parse}) through all classes of the mod, with lambda bodies. Reading
     *  from a mutable static field of a mod and writing to a static field outside of {@code <clinit>} is also an error. The call via
     * {@code Blast.PropsView} is not bypassed: in the background it is a published table ({@code Blast.publish}), and its implementation of
     *  for the server thread ({@code Blast.SERVER}) is itself caught by the rule “{@code Blast.props} - only in the server thread”.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_background_calls", skyAccess = true)
    public static void backgroundSolversCallNoWorld(GameTestHelper h) {
        String pkg = "ua/zentix/incoming/nuclear/world/";
        // roots: class, method, description (null - all overloads); DiskShots.parse(ServerLevel, ...) - for checks, in the server thread
        String[][] roots = {{"Collapse", "solve", null}, {"Blast", "solve", null}, {"RuinPlanner", "finish", null}, {"RuinPlanner", "scorch", null},
                {"RuinPlanner", "flash", null}, {"DiskShots", "parse", "(L" + pkg + "DiskShots$Format;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)L" + pkg + "DiskShots$Read;"}};
        Set<String> forbiddenOwners = Set.of("net/minecraft/world/level/Level", "net/minecraft/server/level/ServerLevel",
                "net/minecraft/world/level/chunk/LevelChunk", "net/minecraft/world/level/chunk/ChunkAccess",
                "net/minecraft/world/level/chunk/LevelChunkSection", "net/minecraft/world/level/BlockGetter",
                "net/minecraft/world/level/LevelReader", "net/minecraft/server/level/ServerChunkCache",
                "net/minecraft/world/level/LevelAccessor", "net/minecraft/world/level/CommonLevelAccessor",
                "ua/zentix/incoming/IncomingConfig", "ua/zentix/incoming/IncomingConfig$Server",
                "net/neoforged/neoforge/common/ModConfigSpec$ConfigValue", "net/neoforged/neoforge/common/ModConfigSpec$BooleanValue",
                "net/neoforged/neoforge/common/ModConfigSpec$IntValue", "net/neoforged/neoforge/common/ModConfigSpec$DoubleValue");
        Set<String> stateOk = Set.of("getBlock", "getValue", "hasProperty", "getFluidState", "setValue", "isAir");
        Set<String> serverOnly = Set.of("props", "ensure", "publish", "computeProps", "clearProps", "ensureOwnStates");
        List<String> bad = new ArrayList<>();
        java.util.ArrayDeque<String[]> todo = new java.util.ArrayDeque<>();
        Set<String> seen = new java.util.HashSet<>();
        for (String[] r : roots) todo.add(new String[]{pkg + r[0], r[1], r[2], r[0] + "." + r[1]});
        java.util.Map<String, java.util.Map<String, Integer>> fieldAccess = new java.util.HashMap<>();
        int walked = 0;
        while (!todo.isEmpty()) {
            String[] m = todo.poll();
            String owner = m[0], name = m[1], desc = m[2], path = m[3];
            if (!seen.add(owner + "." + name + (desc == null ? "*" : desc))) continue;
            boolean[] found = {false};
            String[] superName = {null};
            byte[] bytes = classBytes(owner);
            if (bytes == null) {
                bad.add(path + ": class " + owner + " not found");
                continue;
            }
            walked++;
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String n, String sig, String sup, String[] itf) {
                    superName[0] = sup;
                }

                @Override
                public MethodVisitor visitMethod(int access, String method, String d, String sig, String[] ex) {
                    if (!method.equals(name) || desc != null && !desc.equals(d)) return null;
                    found[0] = true;
                    String here = owner.substring(owner.lastIndexOf('/') + 1) + "." + method;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int op, String o, String called, String cd, boolean itf) {
                            boolean state = o.equals("net/minecraft/world/level/block/state/BlockState")
                                    || o.equals("net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase")
                                    || o.equals("net/minecraft/world/level/block/state/StateHolder");
                            boolean block = o.startsWith("net/minecraft/world/level/block/") && !o.contains("/state/") && !o.endsWith("/Blocks")
                                    && !o.contains("/entity/");
                            if (forbiddenOwners.contains(o) || state && !stateOk.contains(called) || block && !called.equals("defaultBlockState")
                                    || o.equals(pkg + "Blast") && serverOnly.contains(called) || o.equals(pkg + "RuinPlanner") && serverOnly.contains(called)) {
                                bad.add(path + " → " + here + " → " + o.substring(o.lastIndexOf('/') + 1) + "." + called);
                            }
                            if (o.startsWith("ua/zentix/incoming/") && !o.equals(pkg + "Blast$PropsView")) {
                                todo.add(new String[]{o, called, cd, path + " → " + here});
                            }
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String n, String d, org.objectweb.asm.Handle bsm, Object... args) {
                            for (Object a : args) {
                                if (a instanceof org.objectweb.asm.Handle hd && hd.getOwner().startsWith("ua/zentix/incoming/")) {
                                    todo.add(new String[]{hd.getOwner(), hd.getName(), hd.getDesc(), path + " → " + here});
                                }
                            }
                        }

                        @Override
                        public void visitFieldInsn(int op, String o, String field, String fd) {
                            if (!o.startsWith("ua/zentix/incoming/") || op != Opcodes.GETSTATIC && op != Opcodes.PUTSTATIC) return;
                            if (op == Opcodes.PUTSTATIC && !method.equals("<clinit>")) {
                                bad.add(path + " → " + here + " writes a static field " + o.substring(o.lastIndexOf('/') + 1) + "." + field);
                                return;
                            }
                            Integer access = fieldAccess.computeIfAbsent(o, RuinBackgroundGameTests::fields).get(field);
                            if (access != null && (access & Opcodes.ACC_FINAL) == 0 && !method.equals("<clinit>")) {
                                bad.add(path + " → " + here + " reads a mutable static field " + o.substring(o.lastIndexOf('/') + 1) + "." + field);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG);
            // inherited method - look for the ancestor (mod classes only)
            if (!found[0] && superName[0] != null && superName[0].startsWith("ua/zentix/incoming/")) todo.add(new String[]{superName[0], name, desc, path});
        }
        h.assertTrue(walked > 20, "bypassing the call graph only found " + walked + " methods: roots not found?");
        h.assertTrue(bad.isEmpty(), "ruins background tasks call the world or properties past the table: " + bad.size() + ": " + String.join("; ", new java.util.LinkedHashSet<>(bad).stream().limit(40).toList()));
        Incoming.LOG.info("Background tasks of ruins: bypassed {} methods, the world is not called", walked);
        h.succeed();
    }

    private static byte[] classBytes(String owner) {
        try (InputStream in = RuinPlan.class.getResourceAsStream("/" + owner + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /** Static class fields and their flags. */
    private static java.util.Map<String, Integer> fields(String owner) {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        byte[] bytes = classBytes(owner);
        if (bytes == null) return out;
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                if ((access & Opcodes.ACC_STATIC) != 0) out.put(name, access);
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return out;
    }

    /**
     * The plan for chunks read from the disk (phase 2: written as the world would write them, and parsed as chunks not in memory),
     * —place to place is the same as the plan for chunks in memory. The same applies to chunks, as the world update 1.17,
     *  leaves them, and to chunks without height maps on disk (maps from editors).
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "nuke_background_disk", skyAccess = true)
    public static void diskPlanMatchesMemoryPlan(GameTestHelper h) {
        RuinCostGameTests.city(h);
        ServerLevel level = h.getLevel();
        Detonation d = NuclearGameTests.atPsi(h, new BlockPos(-600, 0, 32), true, new BlockPos(32, 1, 32), 12);
        int compared = 0, cells = 0, charred = 0;
        for (int rx : new int[]{24, 40}) {
            for (int rz : new int[]{24, 40}) {
                ChunkPos c = new ChunkPos(h.absolutePos(new BlockPos(rx, 1, rz)));
                for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                    for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) level.getChunk(c.x + dx, c.z + dz);
                }
                LevelChunk chunk = level.getChunk(c.x, c.z);
                RuinPlan memory = RuinPlanner.planFresh(level, d, chunk, true), disk = RuinPlanner.planFromDisk(level, d, chunk);
                String diff = memory.differs(disk);
                h.assertTrue(diff == null, "chunk plan " + c + " from disk is not the same as from a chunk in memory:" + diff);
                diff = flashDiffers(memory, disk);
                h.assertTrue(diff == null, "fumes flash plan chunk " + c + " from the disk is not the same as from the chunk in memory:" + diff);
                // the world of 1.17 after the update: the blocks are in place, the bottom of the world is generated upon loading - the same plan
                RuinPlan retrogen = RuinPlanner.planFromDisk(level, d, chunk, RuinBackgroundGameTests::asRetrogen);
                diff = memory.differs(retrogen);
                if (diff == null) diff = flashDiffers(memory, retrogen);
                h.assertTrue(diff == null, "chunk plan " + c + " s additional generation under zero is not the same as for a chunk in memory:" + diff);
                // map from the editor: there are no height maps on disk - they are counted in blocks, like vanilla when loading
                RuinPlan noHeights = RuinPlanner.planFromDisk(level, d, chunk, tag -> {
                    tag.remove("Heightmaps");
                    return tag;
                });
                diff = memory.differs(noHeights);
                if (diff == null) diff = flashDiffers(memory, noHeights);
                h.assertTrue(diff == null, "chunk plan " + c + " without height maps on disk it is not the same as in a chunk in memory:" + diff);
                compared++;
                cells += memory.changedBlocks();
                charred += memory.flash().length;
            }
        }
        h.assertTrue(cells > 0, "the city is not destroyed: there is nothing to compare");
        h.assertTrue(charred > 0, "there are no burning flashes in the city: there is nothing to compare");
        // the flash layer is on, but the ruins of the explosion are not in memory (restart, the ruins are forgotten after idle time): the fire of the layer is on the roofs of
        //  in the chunk and in its neighbors' windows, the mark of the layer is in the chunk and in its data on the disk; fire in the picture - air in both
        //  ways, otherwise the roof with fire would raise the surface of the world by a block only in the picture from the disk
        List<LevelChunk> pad = padChunks(h);
        int[] marked = flashOverCity(h, d, pad);
        int layerCompared = 0, ruinFires = 0;
        try {
            for (int rx : new int[]{24, 40}) {
                for (int rz : new int[]{24, 40}) {
                    ChunkPos c = new ChunkPos(h.absolutePos(new BlockPos(rx, 1, rz)));
                    LevelChunk chunk = level.getChunk(c.x, c.z);
                    h.assertTrue(FlashScorch.marked(chunk, d.id()), "at the chunk " + c + " no flash layer mark: not the case");
                    RuinPlan memory = RuinPlanner.planFresh(level, d, chunk, true), disk = RuinPlanner.planFromDisk(level, d, chunk);
                    String diff = memory.differs(disk);
                    if (diff == null) diff = flashDiffers(memory, disk);
                    h.assertTrue(diff == null, "chunk plan " + c + " with fire of the flash layer (mark, no layer plan) from the disk is not the same as from the chunk in memory:" + diff);
                    layerCompared++;
                    ruinFires += memory.fireCount();
                }
            }
        } finally {
            unflashCity(level, d, pad);
        }
        h.assertTrue(ruinFires > 0, "the ruins do not have a layer of their own fires on top of the fire: there is nothing to compare");
        Incoming.LOG.info("Ruins from the disk: {} plans coincided, {} places, {} charred edges; on top of the flash layer at the mark - {} plans coincided "
                + "({} chunks with mark, {} layer fires; ruins fires {})", compared, cells, charred, layerCompared, marked[0], marked[1], ruinFires);
        h.succeed();
    }

    /** Chunks of the site (in memory: a block on it is being built across the world). */
    private static List<LevelChunk> padChunks(GameTestHelper h) {
        BlockPos a = h.absolutePos(BlockPos.ZERO), b = h.absolutePos(new BlockPos(63, 0, 63));
        List<LevelChunk> out = new ArrayList<>();
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) out.add(h.getLevel().getChunk(cx, cz));
        }
        return out;
    }

    /**
     * A flash layer over the {@code RuinCostGameTests.city} block: boards on the roofs (there is something for fire to stand on), a layer in all
     *  chunks of the site, then the ruins of the explosion are forgotten - the fire and the mark of the layer remain in the chunks. [0] - chunks with a mark,
     * [1] - layer fires.
     */
    private static int[] flashOverCity(GameTestHelper h, Detonation d, List<LevelChunk> pad) {
        ServerLevel level = h.getLevel();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (LevelChunk c : pad) {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int x = c.getPos().getMinBlockX() + lx, z = c.getPos().getMinBlockZ() + lz;
                    int y = c.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, lx, lz);
                    if (c.getBlockState(m.set(x, y, z)).is(Blocks.SMOOTH_STONE)) level.setBlock(m.set(x, y + 1, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
                }
            }
        }
        long[] keys = pad.stream().mapToLong(c -> c.getPos().toLong()).toArray();
        FlashScorch flash = FlashScorch.forTest(d, keys, new ColumnScar.Budget(true));
        WorkClock clock = WorkClock.counting(1_000_000L);
        boolean done = false;
        for (int i = 0; i < 40 && !done; i++) {
            clock.start(10_000_000_000L);
            done = flash.work(level, d.gameTime() + 10_000, clock);
            if (!done) RuinWorkers.awaitForTest(RuinWorkers.Priority.NEAR, 60_000);
        }
        h.assertTrue(done, "the flash layer over the block did not end");
        NuclearWorld.get(level).forgetRuinsForTest(d.id());
        int[] out = new int[2];
        for (LevelChunk c : pad) {
            if (FlashScorch.marked(c, d.id())) out[0]++;
            out[1] += fires(c, null);
        }
        h.assertTrue(out[1] > 0, "the flash layer did not light the roofs: this was not the case");
        return out;
    }

    /** The fire of the layer and the mark are gone (the boards remain: the batch has its own quarter), the ruins of the explosion are forgotten. */
    private static void unflashCity(ServerLevel level, Detonation d, List<LevelChunk> pad) {
        NuclearWorld.get(level).forgetRuinsForTest(d.id());
        for (LevelChunk c : pad) {
            fires(c, level);
            c.removeData(ModAttachments.FLASH_LAYER);
        }
    }

    /** Fire blocks in the chunk; with {@code level} - and extinguishes them (through the world, without updating neighbors). */
    private static int fires(LevelChunk chunk, @org.jetbrains.annotations.Nullable ServerLevel level) {
        var sections = chunk.getSections();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int n = 0;
        for (int i = 0; i < sections.length; i++) {
            if (sections[i].hasOnlyAir()) continue;
            for (int k = 0; k < 4096; k++) {
                if (!(sections[i].getBlockState(k & 15, k >> 8, (k >> 4) & 15).getBlock() instanceof net.minecraft.world.level.block.BaseFireBlock)) continue;
                n++;
                if (level == null) continue;
                m.set(chunk.getPos().getMinBlockX() + (k & 15), chunk.getMinBuildHeight() + (i << 4) + (k >> 8), chunk.getPos().getMinBlockZ() + ((k >> 4) & 15));
                level.setBlock(m, Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        }
        return n;
    }

    /**
     * Chunk, as the world update 1.17 leaves it (vanilla {@code ChunkHeightAndBiomeFix}, then renaming
     *  statuses): status “empty”, additional generation from zero to full generation of 1.17 ({@code heightmaps} → {@code spawn}).
     */
    private static net.minecraft.nbt.CompoundTag asRetrogen(net.minecraft.nbt.CompoundTag tag) {
        net.minecraft.nbt.CompoundTag retrogen = new net.minecraft.nbt.CompoundTag();
        retrogen.putString("target_status", "minecraft:spawn");
        retrogen.putLongArray("missing_bedrock", new long[4]);
        tag.putString("Status", "minecraft:empty");
        tag.put("below_zero_retrogen", retrogen);
        return tag;
    }

    /**
     * Server stop in the middle of a zone behind a wave (exit from the world after the nuke): all the work of the ruins is abandoned - preparation tickets
     * , zones and holding chunks are released, queues are empty, background threads stopped within their time limit
     *  ({@code RuinWorkers.shutdown}: waits no longer than 3 s). Wall time is not measured: the deadline is at the pool itself.
     */
    @GameTest(template = "range", timeoutTicks = 900, batch = "nuke_stop", skyAccess = true)
    public static void stopMidZoneLeavesNoWork(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        double scale = ua.zentix.incoming.IncomingConfig.SERVER.nukeEffectsScale.get();
        ua.zentix.incoming.IncomingConfig.SERVER.nukeEffectsScale.set(0.02);
        NuclearWorld w = NuclearWorld.get(level);
        ua.zentix.incoming.nuclear.NuclearEvents events = ua.zentix.incoming.nuclear.NuclearEvents.get(level);
        long now = level.getGameTime();
        Vec3 target = Vec3.atBottomCenterOf(h.absolutePos(NuclearGameTests.CENTER));
        events.schedule(new ua.zentix.incoming.nuclear.NuclearEvents.ScheduledStrike(events.nextId(), target, 15, true, now, now + 300, target,
                java.util.Optional.empty(), false));
        h.onEachTick(() -> {
            boolean detonated = events.detonations().stream().anyMatch(x -> x.burst().distanceTo(target) < 20);
            boolean midZone = detonated && (w.prepTiles() > 0 || w.plannedChunks() > 0 || w.queuedChunks() > 0);
            if (!midZone) {
                if (level.getGameTime() > now + 850) {
                    ua.zentix.incoming.IncomingConfig.SERVER.nukeEffectsScale.set(scale);
                    NuclearStrikes.clear(level);
                    throw new net.minecraft.gametest.framework.GameTestAssertException("the zone behind the wave never started: detonation " + detonated);
                }
                return;
            }
            boolean stopped = NuclearWorld.onServerStopping(level.getServer());
            ua.zentix.incoming.IncomingConfig.SERVER.nukeEffectsScale.set(scale);
            int held = ruinTickets(level);
            long threads = ruinThreadsLeft();
            String left = "squares " + w.prepTiles() + ", in preparation " + w.plannedChunks() + ", in line " + w.queuedChunks()
                    + ", ticket ruins " + held + ", streams of ruins " + threads;
            NuclearStrikes.clear(level);
            h.assertTrue(stopped, "background threads did not stop within the time limit; " + left);
            h.assertTrue(ua.zentix.incoming.nuclear.world.RuinWorkers.stopped() && threads == 0, "streams of ruins remained; " + left);
            h.assertTrue(w.prepTiles() == 0 && w.plannedChunks() == 0 && w.queuedChunks() == 0 && held == 0, "after stopping there is still work left: " + left);
            h.succeed();
        });
    }

    /**
     * Living streams of ruins after the pool stops. The pool announces a stop when the count of its threads has reached zero, and the thread,
     * , which has already exited the pool loop, still reaches the end of {@code run()}: “ruin threads 1” happens in the same
     *  millisecond as the line “background threads stopped". Therefore, everyone waits, no longer than a second: the one who hangs like this
     *  remains in the count.
     */
    private static long ruinThreadsLeft() {
        long n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (!t.getName().startsWith("Incoming ruins")) continue;
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) n++;
        }
        return n;
    }

    /** ticket the nuke on the world distance map: yours and downloads of areas whose area is nuclear (preparation, zone). */
    private static int ruinTickets(ServerLevel level) {
        try {
            java.lang.reflect.Field f = net.minecraft.server.level.DistanceManager.class.getDeclaredField("tickets");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (it.unimi.dsi.fastutil.longs.Long2ObjectMap<net.minecraft.util.SortedArraySet<net.minecraft.server.level.Ticket<?>>>)
                    f.get(level.getChunkSource().chunkMap.getDistanceManager());
            java.lang.reflect.Field key = net.minecraft.server.level.Ticket.class.getDeclaredField("key");
            key.setAccessible(true);
            int n = 0;
            for (var set : map.values()) {
                for (net.minecraft.server.level.Ticket<?> t : set) {
                    String type = t.getType().toString();
                    if (type.startsWith("incoming_nuclear")) n++;
                    else if (type.equals("incoming_area_load") && key.get(t) instanceof ua.zentix.incoming.strike.AreaLoader.Area a
                            && a.type().toString().startsWith("incoming_nuclear")) n++;
                }
            }
            return n;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The exit from the world reaches the end when the ticket mode has started generating, and it holds the holder already placed on
     *  unloading (otherwise unloading without end). In Nether, in a fresh place: chunk A is loaded and removed
     *  (it is in the unloading queue), ticket loading on B is nearby - generation B takes A back. Then - as when exiting:
     * {@code ServerStoppingEvent} - the mod removes the ticket and waits for generation ({@link ua.zentix.incoming.util.StopDrain#drain});
     *  after that, not a single Nether holder is busy generating, and the vanilla stop cycle is as is
     *  ({@code removeTicketsOnClosing}, {@code ServerChunkCache.tick(() -> true, false)}, server tasks with tick deadline
     * through 1 ms, like {@code waitUntilNextTick}) ends: Nether map empties ({@code hasWork()} false, no ticket).
     * Without waiting, vanilla call {@code tick} on this holder is not returned until someone else’s thread will not finish the generation:
     *  The unloading task A puts itself back in the queue, and the generation layers need a server thread. Wall time is only
     *  limits against freezing (call longer than 10 s - test red), not a measure of speed.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "stop_drain", skyAccess = true)
    public static void stopDrainLetsVanillaUnloadFinish(GameTestHelper h) {
        net.minecraft.server.MinecraftServer server = h.getLevel().getServer();
        ServerLevel nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
        h.assertTrue(nether != null, "no Nether");
        var cache = nether.getChunkSource();
        var map = cache.chunkMap;
        ChunkPos a = new ChunkPos(20_000 + h.getLevel().random.nextInt(4000), 20_000 + h.getLevel().random.nextInt(4000));
        boolean noSave = nether.noSave;
        long nextTick;
        try {
            java.lang.reflect.Field next = net.minecraft.server.MinecraftServer.class.getDeclaredField("nextTickTimeNanos");
            next.setAccessible(true);
            nextTick = next.getLong(server);
            java.lang.reflect.Field pendingF = net.minecraft.server.level.ChunkMap.class.getDeclaredField("pendingUnloads");
            java.lang.reflect.Field updatingF = net.minecraft.server.level.ChunkMap.class.getDeclaredField("updatingChunkMap");
            java.lang.reflect.Field unloadF = net.minecraft.server.level.ChunkMap.class.getDeclaredField("unloadQueue");
            pendingF.setAccessible(true);
            updatingF.setAccessible(true);
            unloadF.setAccessible(true);
            var pending = (it.unimi.dsi.fastutil.longs.Long2ObjectMap<?>) pendingF.get(map);
            var updating = (it.unimi.dsi.fastutil.longs.Long2ObjectMap<?>) updatingF.get(map);
            var unloadQueue = (java.util.Queue<?>) unloadF.get(map);
            nether.noSave = false;
            try {
                // A - full chunk; his ticket UNKNOWN expires, and A goes to the unloading queue - when it is released by
                //  generation of neighbors (processUnloads skips the holder with generationRefCount > 0): chunk tasks and
                //  generation threads, up to 10 s
                nether.getChunk(a.x, a.z);
                long setup = System.nanoTime() + 10_000_000_000L;
                while (!pending.containsKey(a.toLong()) && System.nanoTime() < setup) {
                    cache.tick(() -> false, false);
                    while (cache.pollTask()) {
                        // server thread chunk tasks: neighbor generation steps
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                }
                h.assertTrue(pending.containsKey(a.toLong()) && !unloadQueue.isEmpty(), "A did not join the upload queue");
                // ticket loading on B nearby, like the mod districts: A returns to work, generation B holds it
                ChunkPos b = new ChunkPos(a.x + 2, a.z);
                net.minecraft.server.level.TicketType<ChunkPos> type = net.minecraft.server.level.TicketType.create("incoming_test_stop",
                        java.util.Comparator.comparingLong(ChunkPos::toLong));
                cache.addRegionTicket(type, b, 0, b);
                cache.tick(() -> false, false);
                h.assertTrue(updating.get(a.toLong()) instanceof net.minecraft.server.level.GenerationChunkHolder g && g.getGenerationRefCount() > 0,
                        "generation B does not hold A - the case is not reproduced");
                // ServerStoppingEvent: the mod removes its ticket and waits for the generation started before release
                cache.removeRegionTicket(type, b, 0, b);
                boolean drained = ua.zentix.incoming.util.StopDrain.drain(java.util.List.of(nether),
                        System.nanoTime() + ua.zentix.incoming.util.StopDrain.LIMIT_NANOS);
                int busy = ua.zentix.incoming.util.StopDrain.inFlight(nether);
                h.assertTrue(drained && busy == 0, "generation has not ended beyond the waiting limit: holders are occupied " + busy);
                h.assertTrue(!(updating.get(a.toLong()) instanceof net.minecraft.server.level.GenerationChunkHolder g) || g.getGenerationRefCount() == 0,
                        "A is still generating");
                // vanilla stop loop as is: each call to tick returns, the map is empty
                long deadline = System.nanoTime() + 60_000_000_000L;
                int rounds = 0;
                while (map.hasWork()) {
                    if (System.nanoTime() > deadline) {
                        throw new net.minecraft.gametest.framework.GameTestAssertException("the exit is not over " + rounds + " circles: unloading queue "
                                + unloadQueue.size() + ", ticket " + (map.getDistanceManager().hasTickets() ? "yes" : "no"));
                    }
                    next.setLong(server, net.minecraft.Util.getNanos() + 1_000_000L);
                    cache.removeTicketsOnClosing();
                    long spinEnd = System.nanoTime() + 10_000_000_000L;
                    try {
                        cache.tick(() -> {
                            if (System.nanoTime() > spinEnd) throw new VanillaSpin();
                            return true;
                        }, false);
                    } catch (VanillaSpin e) {
                        throw new net.minecraft.gametest.framework.GameTestAssertException("vanilla unloading circle did not return for 10 s (circle " + rounds
                                + "): holder A is busy generating - " + (updating.get(a.toLong()) instanceof net.minecraft.server.level.GenerationChunkHolder g
                                ? g.getGenerationRefCount() : -1));
                    }
                    while (server.pollTask()) {
                        // server tasks, like waitUntilNextTick: chunk tasks - only until the deadline has passed
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                    rounds++;
                }
                h.assertFalse(map.getDistanceManager().hasTickets(), "tickets left after leaving");
            } finally {
                nether.noSave = noSave;
                next.setLong(server, nextTick);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        h.succeed();
    }

    /**
     * Stopping after a salvo: mod areas (here is a target area of radius 8 in fresh Nether, chunks are not ready) are released
     * ({@code StrikeWorld.releaseAreas}) before waiting for generation, and {@code StopDrain} waits only for what has been started - without this he would have
     *  applied the district ticket and waited longer for the generation of the entire salvo its limit. Wall time is only the limit
     * {@code StopDrain.LIMIT_NANOS}.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "stop_drain_areas", skyAccess = true)
    public static void stopReleasesAreasBeforeDrain(GameTestHelper h) {
        ServerLevel nether = h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        h.assertTrue(nether != null, "no Nether");
        ChunkPos c = new ChunkPos(-30_000 - h.getLevel().random.nextInt(4000), 20_000 + h.getLevel().random.nextInt(4000));
        java.util.UUID flight = java.util.UUID.randomUUID();
        ua.zentix.incoming.strike.FlightTickets.hold(nether, c, 8, flight, true);
        nether.getChunkSource().tick(() -> false, false);
        h.assertTrue(ua.zentix.incoming.util.StopDrain.inFlight(nether) > 0, "the district did not start generation - the case was not reproduced");
        ua.zentix.incoming.strike.StrikeWorld.releaseAreas(nether);
        h.assertTrue(ua.zentix.incoming.strike.StrikeWorld.get(nether).areas().size() == 0, "areas not released");
        boolean drained = ua.zentix.incoming.util.StopDrain.drain(java.util.List.of(nether),
                System.nanoTime() + ua.zentix.incoming.util.StopDrain.LIMIT_NANOS);
        int busy = ua.zentix.incoming.util.StopDrain.inFlight(nether);
        h.assertTrue(drained && busy == 0, "generation has not ended beyond the waiting limit: holders are occupied " + busy);
        h.assertTrue(ua.zentix.incoming.strike.FlightTickets.held(nether, flight) == 0, "the area ticket remains");
        h.succeed();
    }

    /** Quit the vanilla unload circle if it doesn't return ({@link #stopDrainLetsVanillaUnloadFinish}). */
    private static final class VanillaSpin extends RuntimeException {
        private static final long serialVersionUID = 1L;

        VanillaSpin() {
            super(null, null, false, false);
        }
    }

    /**
     * A chunk is on the edge of a loaded world (ticket of radius 0, neighbors are not loaded) with a building in the entire chunk: its background plan
     *  is built like a queue of ruins - a window from the disk, neighbors are not on the disk (not saved) - a solid array, and nothing is waiting for
     *  ({@link #windowWithoutNeighboursOnDisk}). A ready-made plan without places across the world at the edge is placed without neighbors: the neighbors
     *  are not loaded, and the blocks of the chunk - section to section are the same as with loaded neighbors (the same plan a second time
     *  after returning the blocks). Second case: for a chunk of craft Sable the substitution is still waiting for neighbors with a radius of 1
     *  ({@code RuinPlan.needsNeighbours}: Sable reads 6 neighbors for each place), but not for the distant chunk.
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "nuke_edge_apply", skyAccess = true)
    public static void readyPlanSameWithoutNeighbours(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var chunks = level.getChunkSource();
        ChunkPos pad = new ChunkPos(h.absolutePos(NuclearGameTests.CENTER));
        ChunkPos x = new ChunkPos(pad.x + 40, pad.z);
        boolean fires = ua.zentix.incoming.IncomingConfig.SERVER.nukeFires.get();
        RuinPlan[] plan = new RuinPlan[1];
        Detonation[] boom = new Detonation[1];
        int[][] before = new int[1][], afterAlone = new int[1][];
        Runnable done = () -> {
            chunks.removeRegionTicket(NuclearGameTests.HOLD, x, 0, x);
            chunks.removeRegionTicket(NuclearGameTests.HOLD, x, 1, x);
            ua.zentix.incoming.IncomingConfig.SERVER.nukeFires.set(fires);
            NuclearStrikes.clear(level);
        };
        // and after failure by deadline: ticket, fires in setup, queues
        StrikeGameTests.afterTest(h, done);
        // X - immediately (server GameTest ticks faster than background generation), ticket keeps it full, no neighbors
        chunks.addRegionTicket(NuclearGameTests.HOLD, x, 0, x);
        level.getChunk(x.x, x.z);
        h.startSequence()
                .thenExecute(() -> {
                    LevelChunk chunk = chunks.getChunkNow(x.x, x.z);
                    h.assertTrue(chunk != null, "X not loaded");
                    BlockPos base = new BlockPos(x.getMinBlockX(), 200, x.getMinBlockZ());
                    // floors in the entire chunk on pillars, walls at the edge (places of the plan are a block from the border of the chunk; on the very
                    //  border setBlock is not suitable: Sable reads the neighbors of the place and would load the neighboring chunk)
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lx = 1; lx < 15; lx++) {
                            for (int lz = 1; lz < 15; lz++) {
                                boolean edge = lx == 1 || lx == 14 || lz == 1 || lz == 14, pillar = (lx == 4 || lx == 11) && (lz == 4 || lz == 11);
                                Block b = ly % 4 == 3 ? Blocks.STONE : pillar ? Blocks.STONE_BRICKS : edge ? (ly % 4 == 1 ? Blocks.GLASS : Blocks.BRICKS) : null;
                                if (b != null) level.setBlock(base.offset(lx, ly, lz), b.defaultBlockState(), 2 | 16);
                            }
                        }
                    }
                    ua.zentix.incoming.IncomingConfig.SERVER.nukeFires.set(false);
                    Detonation d = NuclearGameTests.atPsi(h, NuclearGameTests.CENTER, true, h.relativePos(base.offset(8, 8, 8)), 6);
                    boom[0] = d;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if ((dx != 0 || dz != 0) && chunks.getChunkNow(x.x + dx, x.z + dz) != null) {
                                throw new net.minecraft.gametest.framework.GameTestAssertException("neighbor X loaded before replacement - case not reproduced");
                            }
                        }
                    }
                    int[] window = new int[2];
                    plan[0] = RuinPlanner.planWithWindow(level, d, chunk, window);
                    h.assertTrue(plan[0].changedBlocks() > 0, "the building is not destroyed: there is nothing to compare");
                    h.assertFalse(plan[0].needsNeighbours(level, chunk), "plan X needs neighbors - this is not the case");
                    h.assertTrue(plan[0].waitsNeighbours(level, chunk) == 0, "the queue would be waiting for neighbors X");
                    before[0] = blocks(chunk);
                    h.assertTrue(plan[0].apply(level, chunk, d, new ua.zentix.incoming.nuclear.world.ColumnScar.Budget(false)), "plan X is outdated");
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if ((dx != 0 || dz != 0) && chunks.getChunkNow(x.x + dx, x.z + dz) != null) {
                                throw new net.minecraft.gametest.framework.GameTestAssertException("substitution loaded the neighbor " + (x.x + dx) + ", " + (x.z + dz));
                            }
                        }
                    }
                    afterAlone[0] = blocks(chunk);
                    // return the blocks as before the ruins and load the neighbors
                    restore(level, chunk, before[0]);
                    h.assertTrue(java.util.Arrays.equals(blocks(chunk), before[0]), "blocks X did not return");
                    // neighbors - immediately, ticket keeps them full
                    chunks.addRegionTicket(NuclearGameTests.HOLD, x, 1, x);
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(x.x + dx, x.z + dz);
                })
                .thenWaitUntil(() -> h.assertTrue(ua.zentix.incoming.nuclear.world.NuclearTickets.neighbourhoodLoaded(level, x, 1), "neighbors X are not complete"))
                .thenExecute(() -> {
                    LevelChunk chunk = chunks.getChunkNow(x.x, x.z);
                    h.assertTrue(plan[0].apply(level, chunk, boom[0], new ua.zentix.incoming.nuclear.world.ColumnScar.Budget(false)), "plan X is out of date for the second time");
                    String diff = firstDifference(chunk, afterAlone[0], blocks(chunk));
                    h.assertTrue(diff == null, "ruins X without neighbors and with neighbors are different: " + diff);
                    Incoming.LOG.info("Ruins at the edge: {} places, without neighbors and with neighbors - the same thing", plan[0].changedBlocks());
                })
                .thenExecute(() -> assembleCraft(h))
                .thenWaitUntil(() -> h.assertFalse(!sable() || ua.zentix.incoming.compat.SubLevels.near(level, craftCentre(h), 8).isEmpty(), "craft going"))
                .thenExecute(() -> craftChunkWaits(h, x, plan[0]))
                .thenSucceed();
    }

    /**
     * The block entity is not from vanilla in the middle of the chunk (stand Create): its {@code onRemove} moves in a chain through neighboring
     *  blocks (tape Create - up to 20 blocks), so the substitution waits for neighbors of radius {@code RuinPlanner.REACH}, and not 1 and not 0,
     *  like the chest in the same place. Both blocks are in a fireball: the plan removes them.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_be_reach", skyAccess = true)
    public static void moddedBlockEntityWaitsReach(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.afterTest(h, () -> NuclearStrikes.clear(level));
        Block depot = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "depot"));
        h.assertTrue(depot != Blocks.AIR, "no create:depot — Create not loaded");
        BlockPos centre = h.absolutePos(NuclearGameTests.CENTER);
        ChunkPos c = new ChunkPos(centre);
        BlockPos spot = new BlockPos(c.getMinBlockX() + 8, centre.getY() + 1, c.getMinBlockZ() + 8);
        LevelChunk chunk = level.getChunkAt(spot);
        Detonation d = NuclearGameTests.detonation(h, NuclearGameTests.CENTER, 0, 15, 0.05f);
        level.setBlock(spot, depot.defaultBlockState(), 3);
        h.assertTrue(level.getBlockState(spot).hasBlockEntity(), "the stand has no block entity");
        RuinPlan withDepot = RuinPlanner.planWithWindow(level, d, chunk, new int[2]);
        h.assertTrue(withDepot.neighbourRadius(level, chunk) == RuinPlanner.REACH,
                "stand Create in the middle of the chunk: replacement awaits neighbors of the radius " + withDepot.neighbourRadius(level, chunk) + ", not " + RuinPlanner.REACH);
        level.setBlock(spot, Blocks.CHEST.defaultBlockState(), 3);
        RuinPlan withChest = RuinPlanner.planWithWindow(level, d, chunk, new int[2]);
        h.assertTrue(withChest.neighbourRadius(level, chunk) == 0, "chest in the middle of the chunk: replacement awaits radius neighbors " + withChest.neighbourRadius(level, chunk));
        h.assertTrue(withChest.apply(level, chunk, d, new ua.zentix.incoming.nuclear.world.ColumnScar.Budget(false)), "the plan is outdated");
        h.assertFalse(level.getBlockState(spot).is(Blocks.CHEST), "the chest has not been removed: this is not the case");
        h.succeed();
    }

    private static final net.minecraft.server.level.TicketType<java.util.UUID> TEST_TILE =
            net.minecraft.server.level.TicketType.create("incoming_test_tile", java.util.Comparator.<java.util.UUID>naturalOrder());

    private static ua.zentix.incoming.strike.AreaLoader.Area tile(ChunkPos c) {
        return new ua.zentix.incoming.strike.AreaLoader.Area(TEST_TILE, c, 0, new java.util.UUID(7L, c.toLong()), false);
    }

    /**
     * The dead end of the zone behind the wave: the chunk P is loaded under a ticket with the neighbors of the chunk Q (it does not take its ticket
     * ), the square of the zone is held by P, and the replacement P is waiting for the neighbor R, whom no one is loading, - and the square is waiting P. Here Q
     *  and P hold ticket “squares” of radius 0, chests at the eastern edge Q and P require neighbors of radius 1. While Q has not released his ticket, P is waiting; after that it waits and continues (200 tick, R does not load), and {@code holdForTile} gives P its
     *  ticket: R loads, the ruins of P rise. At the same tick, the chest P is replaced by the block entity Create: the plan P becomes obsolete,
     *  a new one awaits neighbors of the radius 2 - your ticket P (taken as a square, P is loaded under someone else’s) must expand to r2,
     * , otherwise P would wait forever and hold the square hold.
     */
    @GameTest(template = "range", timeoutTicks = 1600, batch = "nuke_tile_hold", skyAccess = true)
    public static void tileReleaseHoldsWaitingNeighbours(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos pad = new ChunkPos(h.absolutePos(NuclearGameTests.CENTER));
        ChunkPos q = new ChunkPos(pad.x + 40, pad.z), p = new ChunkPos(q.x + 1, q.z), r = new ChunkPos(q.x + 2, q.z);
        var areas = ua.zentix.incoming.strike.StrikeWorld.get(level).areas();
        var scars = NuclearWorld.get(level).scars();
        // ground explosion at the western edge of P without a crater: the crater holds its chunks with its neighbors (NuclearTickets) and
        //  would have loaded R immediately after the explosion - the ruins of P would have stood up without waiting for a dead end, depending on what came first: plan P or
        // uploading R after crater
        boolean crater = ua.zentix.incoming.IncomingConfig.SERVER.nukeCrater.get();
        ua.zentix.incoming.IncomingConfig.SERVER.nukeCrater.set(false);
        StrikeGameTests.afterTest(h, () -> {
            ua.zentix.incoming.IncomingConfig.SERVER.nukeCrater.set(crater);
            areas.release(level, tile(q));
            areas.release(level, tile(p));
            NuclearStrikes.clear(level);
        });
        // everything around is intact on the disk; chests at the eastern edge Q and P (places across the world at the edge are neighbors of radius 1)
        BlockPos[] chests = new BlockPos[2];
        for (int x = q.x - 2; x <= r.x + 2; x++) {
            for (int z = q.z - 2; z <= q.z + 2; z++) level.getChunk(x, z);
        }
        int i = 0;
        for (ChunkPos c : new ChunkPos[]{q, p}) {
            int x = c.getMinBlockX() + 15, z = c.getMinBlockZ() + 8;
            chests[i] = new BlockPos(x, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z), z);
            level.setBlock(chests[i++], Blocks.CHEST.defaultBlockState(), 3);
        }
        int[] stage = {0};
        long[] since = {0};
        String[] waited = {""};
        boolean[] widened = {false};
        var depot = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.ResourceLocation.parse("create:depot"));
        Detonation[] det = {null};
        long[] tickAt = {0};
        h.onEachTick(() -> {
            // after preparation - at the pace of the game: expectations are lower in tick, and loading of chunks and background plans proceeds by the hour
            if (stage[0] > 0) gamePace(tickAt);
            long now = level.getGameTime();
            switch (stage[0]) {
                case 0 -> {
                    // we wait until the generated one is unloaded: Q, P and R will only load the script itself. It will unload completely, and not
                    //  will just leave the visible map: the chunk waiting to be unloaded is put in a queue right away, and P, not yet
                    //  under ticket Q, would take its ticket with its neighbors - R would load, the ruins of P would get up without dead end While
                    // without game tempo: the lagging server does not unload pending uploads (vanilla - only while there is time in the tick)
                    if (!unloaded(level, List.of(q, p, r))) return;
                    areas.hold(level, tile(q));
                    tickAt[0] = System.nanoTime();
                    stage[0] = 1;
                }
                case 1 -> {
                    if (level.getChunkSource().getChunkNow(q.x, q.z) == null) return;
                    det[0] = NuclearWarhead.detonate(level, new Vec3(p.getMinBlockX(), chests[1].getY(), p.getMinBlockZ() + 8.5), 1, false, null, 0.1f);
                    since[0] = now;
                    stage[0] = 2;
                }
                case 2 -> {
                    // Q is waiting for its neighbors with its ticket - it loads P; P gets in line under someone else's ticket
                    if (!scars.queued(p.toLong())) {
                        if (now - since[0] > 600) h.fail("P did not join the queue: Q " + (scars.queued(q.toLong()) ? "in line" : "ready") + " — Q did not have a ticket with neighbors");
                        return;
                    }
                    areas.hold(level, tile(p));
                    stage[0] = 3;
                }
                case 3 -> {
                    if (scars.queued(q.toLong())) return;
                    h.assertFalse(level.getBlockState(chests[0]).is(Blocks.CHEST), "the chest in Q is intact: this is not the case");
                    since[0] = now;
                    stage[0] = 4;
                }
                case 4 -> {
                    // ticket Q released: P is waiting for R, and no one is loading R (a dead end in which there are zone squares)
                    h.assertTrue(scars.queued(p.toLong()), "the ruins of P stood without a neighbor R: there is no dead end, this is not the case");
                    h.assertTrue(level.getChunkSource().getChunkNow(r.x, r.z) == null, "R loaded");
                    if (now - since[0] < 200) return;
                    waited[0] = scars.diagState(p.toLong(), now);
                    h.assertTrue(scars.waitsNeighbours(p.toLong()), "P is not waiting for neighbors: " + waited[0]);
                    h.assertTrue(scars.holdForTile(level, p.toLong()), "P did not take a ticket with neighbors");
                    // chest - place of the plan; a block entity not from vanilla instead: the old states are not the same, the plan is new,
                    //  and a new one is waiting for neighbors r2
                    h.assertTrue(depot.isPresent(), "no create:depot (Create not loaded)");
                    level.setBlock(chests[1], depot.get().defaultBlockState(), 3);
                    areas.release(level, tile(p));
                    since[0] = now;
                    stage[0] = 5;
                }
                case 5 -> {
                    // the square is released: P holds itself until its ruins stand up (otherwise it would unload without the ruins)
                    if (scars.queued(p.toLong())) {
                        if (scars.diagState(p.toLong(), now).contains("ticket r2")) {
                            widened[0] = true;
                            // extended ticket per square - still included in deductions
                            h.assertTrue(scars.tileHoldsLeft() == 63, "ticket P r2 outside the deduction account: free " + scars.tileHoldsLeft());
                        }
                        h.assertTrue(level.getChunkSource().getChunkNow(p.x, p.z) != null, "P unloaded without ruins after releasing the square");
                        if (now - since[0] > 600) h.fail("ruins P did not stand up for 600 tick after his ticket with neighbors");
                        return;
                    }
                    // the work of P ended in ruins, and not unloading: the mark of explosion at P. By releasing your ticket, P is unloaded
                    // in the same server tick as the ruins - then it is read from disk (here you can: check)
                    boolean wasLoaded = level.getChunkSource().getChunkNow(p.x, p.z) != null;
                    LevelChunk pc = level.getChunk(p.x, p.z);
                    h.assertTrue(pc != null && pc.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) == det[0].id(), "the ruins of P did not rise (P in memory " + wasLoaded
                            + ", explosion mark " + (pc == null ? "?" : pc.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0)) + " when detonated " + det[0].id()
                            + "); P was waiting: " + waited[0] + "; block pressure " + String.format(java.util.Locale.ROOT, "%.0f", det[0].psi(Vec3.atCenterOf(chests[1]))) + " psi");
                    h.assertTrue(widened[0], "P did not wait for neighbors r2 with its ticket: the plan with block Create did not require r2, this is not the case");
                    h.assertTrue(scars.tileHoldsLeft() == 64, "ticket P not released after the ruins: free " + scars.tileHoldsLeft());
                    h.succeed();
                }
                default -> {
                }
            }
        });
    }

    private static boolean sable() {
        return net.neoforged.fml.ModList.get().isLoaded("sable");
    }

    private static final BlockPos CRAFT_FROM = NuclearGameTests.CENTER.offset(6, 5, -2), CRAFT_TO = CRAFT_FROM.offset(3, 1, 3);

    private static Vec3 craftCentre(GameTestHelper h) {
        return Vec3.atCenterOf(h.absolutePos(CRAFT_FROM.offset(1, 0, 1)));
    }

    /** craft from boards above the platform (like {@code aircraftBlast}). Without Sable - nothing. */
    private static void assembleCraft(GameTestHelper h) {
        if (!sable()) return;
        ServerLevel level = h.getLevel();
        BlockPos.betweenClosed(CRAFT_FROM, CRAFT_TO).forEach(p -> h.setBlock(p, Blocks.OAK_PLANKS));
        BlockPos a = h.absolutePos(CRAFT_FROM), b = h.absolutePos(CRAFT_TO);
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                String.format(java.util.Locale.ROOT, "sable assemble area %d %d %d %d %d %d", Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                        Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
    }

    /**
     * The craft replacement chunk needs neighbors, the distant one (X) does not: the same plan (its places are not across the world), the only difference is the
     *  device. Without Sable there is nothing to check.
     */
    private static void craftChunkWaits(GameTestHelper h, ChunkPos far, RuinPlan plan) {
        if (!sable()) return;
        ServerLevel level = h.getLevel();
        LevelChunk under = level.getChunkAt(BlockPos.containing(craftCentre(h)));
        h.assertTrue(plan.needsNeighbours(level, under), "for a craft chunk, substitution does not wait for neighbors: Sable would read them synchronously");
        LevelChunk x = level.getChunk(far.x, far.z);
        h.assertFalse(plan.needsNeighbours(level, x), "for a distant chunk without craft, a replacement awaits neighbors");
    }

    /**
     * The flash layer of craft Sable: Sable reads neighbors for each place (up to 2 blocks), and the flash layer itself does not need
     *  neighbors - for a chunk whose neighbors are not in memory, the layer does not rise (ruins will bring it when there are neighbors), blocks of the chunk same
     * , the neighbors are not loaded. Without craft, the layer receives the same chunk without neighbors. X - the edge of the loaded world (ticket
     *  of radius 0) in 12 chunks to +Z from the site (it is empty: the next rows of sites are placed later): within reach of the physics
     *  device above the site ({@code SableTerrain.watched}), but not in its chunks. Someone else's craft is nearby (former batch) -
     * X is already with the craft, and only it is checked. Without Sable - only the first half.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "nuke_flash_edge", skyAccess = true)
    public static void flashLayerWaitsNeighboursAtCraft(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var chunks = level.getChunkSource();
        ChunkPos pad = new ChunkPos(h.absolutePos(NuclearGameTests.CENTER));
        ChunkPos x = new ChunkPos(pad.x, pad.z + 12);
        BlockPos base = new BlockPos(x.getMinBlockX(), 200, x.getMinBlockZ());
        StrikeGameTests.afterTest(h, () -> {
            // field - away: it would shade the areas of the next rows
            if (chunks.getChunkNow(x.x, x.z) != null) field(level, base, false);
            chunks.removeRegionTicket(NuclearGameTests.HOLD, x, 0, x);
            NuclearStrikes.clear(level);
        });
        // X - immediately (server GameTest ticks faster than background generation), ticket keeps it full, no neighbors
        chunks.addRegionTicket(NuclearGameTests.HOLD, x, 0, x);
        level.getChunk(x.x, x.z);
        int[][] before = new int[1][];
        boolean[] foreign = new boolean[1];
        h.startSequence()
                .thenExecute(() -> {
                    LevelChunk chunk = chunks.getChunkNow(x.x, x.z);
                    h.assertTrue(chunk != null, "X not loaded");
                    field(level, base, true);
                    neighboursUnloaded(chunks, x, "to layer");
                    before[0] = blocks(chunk);
                    foreign[0] = ua.zentix.incoming.compat.SableTerrain.watched(level, chunk);
                    FlashScorch f = flashOver(h, base, x);
                    if (foreign[0]) {
                        h.assertTrue(f.appliedChunks() == 0 && f.waitingChunks() == 1, "layer of someone else's craft without neighbors: stood in " + f.appliedChunks()
                                + ", waited " + f.waitingChunks());
                        h.assertTrue(java.util.Arrays.equals(blocks(chunk), before[0]), "a layer on someone else's craft without neighbors changed blocks: "
                                + firstDifference(chunk, before[0], blocks(chunk)));
                    } else {
                        h.assertTrue(f.appliedChunks() == 1, "without craft, the layer at the edge did not stand up (we were waiting for neighbors: " + f.waitingChunks() + ")");
                        if (java.util.Arrays.equals(blocks(chunk), before[0])) {
                            Vec3 at = Vec3.atCenterOf(base.offset(8, 2, 8));
                            h.fail(String.format(java.util.Locale.ROOT, "the layer did not burn anything: the case is not the same (light near the field %.0f cal/cm², %.1f psi, the ball is on the %.1f block above; trace without ruins - %d places)",
                                    f.detonation().fluence(at), f.detonation().psi(at), f.detonation().burst().y - at.y,
                                    RuinPlanner.testScorch(level, f.detonation(), chunk).changedBlocks()));
                        }
                        restore(level, chunk, before[0]);
                    }
                    neighboursUnloaded(chunks, x, "after layer");
                })
                .thenExecute(() -> {
                    if (!foreign[0]) assembleCraft(h);
                })
                .thenWaitUntil(() -> h.assertFalse(sable() && !foreign[0] && ua.zentix.incoming.compat.SubLevels.near(level, craftCentre(h), 8).isEmpty(),
                        "craft going"))
                .thenExecute(() -> {
                    if (!sable() || foreign[0]) return;
                    LevelChunk chunk = chunks.getChunkNow(x.x, x.z);
                    h.assertTrue(ua.zentix.incoming.compat.SableTerrain.watched(level, chunk), "X out of reach craft: not the case");
                    neighboursUnloaded(chunks, x, "at craft to layer");
                    FlashScorch f = flashOver(h, base, x);
                    h.assertTrue(f.appliedChunks() == 0 && f.waitingChunks() == 1, "craft layer without neighbors: placed in " + f.appliedChunks() + ", waited " + f.waitingChunks());
                    h.assertTrue(java.util.Arrays.equals(blocks(chunk), before[0]), "craft's layer without neighbors changed blocks: "
                            + firstDifference(chunk, before[0], blocks(chunk)));
                    neighboursUnloaded(chunks, x, "at craft after layer");
                    Incoming.LOG.info("The flash layer of craft Sable is waiting for its neighbors: without craft it works, but with craft it doesn’t");
                })
                .thenSucceed();
    }

    /**
     * The flash layer of craft Sable, whose neighbors were in memory when the layer went into the background, and left it while it was being built:
     *  the record checks the neighbors again ({@code RuinPlan.waitsNeighbours} before the record) and does not get up - the chunk blocks are the same,
     *  neighbors are not loaded. The background check was passed (there were neighbors), so he was only waiting for the recording. X - like
     * {@link #flashLayerWaitsNeighboursAtCraft}, from your craft or someone else's. Without Sable there is nothing to check.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "nuke_flash_edge_write", skyAccess = true)
    public static void flashLayerRechecksNeighboursBeforeWrite(GameTestHelper h) {
        if (!sable()) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        var chunks = level.getChunkSource();
        ChunkPos pad = new ChunkPos(h.absolutePos(NuclearGameTests.CENTER));
        ChunkPos x = new ChunkPos(pad.x, pad.z + 12);
        BlockPos base = new BlockPos(x.getMinBlockX(), 200, x.getMinBlockZ());
        StrikeGameTests.afterTest(h, () -> {
            if (chunks.getChunkNow(x.x, x.z) != null) field(level, base, false);
            chunks.removeRegionTicket(NuclearGameTests.HOLD, x, 1, x);
            chunks.removeRegionTicket(NuclearGameTests.HOLD, x, 0, x);
            NuclearStrikes.clear(level);
        });
        chunks.addRegionTicket(NuclearGameTests.HOLD, x, 0, x);
        level.getChunk(x.x, x.z);
        int[][] before = new int[1][];
        FlashScorch[] flash = new FlashScorch[1];
        long[] now = new long[1];
        h.startSequence()
                .thenExecute(() -> {
                    LevelChunk chunk = chunks.getChunkNow(x.x, x.z);
                    h.assertTrue(chunk != null, "X not loaded");
                    field(level, base, true);
                    if (!ua.zentix.incoming.compat.SableTerrain.watched(level, chunk)) assembleCraft(h);
                })
                .thenWaitUntil(() -> h.assertTrue(ua.zentix.incoming.compat.SableTerrain.watched(level, chunks.getChunkNow(x.x, x.z)), "craft going"))
                .thenExecute(() -> {
                    // neighbors X - in memory: background check passed, layer is being built
                    chunks.addRegionTicket(NuclearGameTests.HOLD, x, 1, x);
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(x.x + dx, x.z + dz);
                    h.assertTrue(ua.zentix.incoming.nuclear.world.NuclearTickets.neighbourhoodLoaded(level, x, 1), "neighbors X are not in memory until the layer");
                    before[0] = blocks(chunks.getChunkNow(x.x, x.z));
                    Detonation d = flashDetonation(h, base);
                    flash[0] = FlashScorch.forTest(d, new long[]{x.toLong()}, new ColumnScar.Budget(false));
                    now[0] = d.gameTime() + 1_000;
                    WorkClock clock = WorkClock.counting(1_000_000L);
                    clock.start(10_000_000_000L);
                    h.assertFalse(flash[0].work(level, now[0], clock), "the layer did not go into the background");
                    h.assertTrue(flash[0].waitingChunks() == 0, "layer waited for the neighbors to the background, although they are in memory");
                    RuinWorkers.awaitForTest(RuinWorkers.Priority.NEAR, 60_000);
                    // neighbors go out of memory while the layer is ready but not yet written
                    chunks.removeRegionTicket(NuclearGameTests.HOLD, x, 1, x);
                })
                .thenWaitUntil(() -> neighboursUnloaded(chunks, x, "after leaving their ticket"))
                .thenExecute(() -> {
                    LevelChunk chunk = chunks.getChunkNow(x.x, x.z);
                    WorkClock clock = WorkClock.counting(1_000_000L);
                    clock.start(10_000_000_000L);
                    h.assertTrue(flash[0].work(level, now[0], clock), "the layer is not finished");
                    h.assertTrue(flash[0].appliedChunks() == 0 && flash[0].waitingChunks() == 1, "recording a layer for craft without neighbors: stood in "
                            + flash[0].appliedChunks() + ", waited " + flash[0].waitingChunks());
                    h.assertTrue(java.util.Arrays.equals(blocks(chunk), before[0]), "recording a layer for craft without neighbors changed blocks: "
                            + firstDifference(chunk, before[0], blocks(chunk)));
                    neighboursUnloaded(chunks, x, "after recording the layer");
                    Incoming.LOG.info("The flash layer of craft Sable: the neighbors left while it was being built - the recording did not put it on");
                })
                .thenSucceed();
    }

    /** A field of grass in the air above X: stone, turf, grass - two blocks from the edge (Sable reads shift neighbors); or away. */
    private static void field(ServerLevel level, BlockPos base, boolean grow) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int lx = 2; lx <= 13; lx++) {
            for (int lz = 2; lz <= 13; lz++) {
                level.setBlock(base.offset(lx, 0, lz), grow ? Blocks.STONE.defaultBlockState() : air, 2 | 16);
                level.setBlock(base.offset(lx, 1, lz), grow ? Blocks.GRASS_BLOCK.defaultBlockState() : air, 2 | 16);
                level.setBlock(base.offset(lx, 2, lz), grow ? Blocks.SHORT_GRASS.defaultBlockState() : air, 2 | 16);
            }
        }
    }

    /** Not a single neighbor of X is loaded entirely: otherwise the case is not reproduced (or the layer loaded them). */
    private static void neighboursUnloaded(net.minecraft.server.level.ServerChunkCache chunks, ChunkPos x, String when) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && chunks.getChunkNow(x.x + dx, x.z + dz) != null) {
                    throw new net.minecraft.gametest.framework.GameTestAssertException("neighbor X " + (x.x + dx) + ", " + (x.z + dz) + " loaded " + when);
                }
            }
        }
    }

    /** A flash layer of one {@code x} chunk from the explosion low above the field (the light burns the grass) to the end. */
    private static FlashScorch flashOver(GameTestHelper h, BlockPos base, ChunkPos x) {
        Detonation d = flashDetonation(h, base);
        FlashScorch f = FlashScorch.forTest(d, new long[]{x.toLong()}, new ColumnScar.Budget(false));
        WorkClock clock = WorkClock.counting(1_000_000L);
        boolean done = false;
        for (int i = 0; i < 20 && !done; i++) {
            clock.start(10_000_000_000L);
            done = f.work(h.getLevel(), d.gameTime() + 1_000, clock);
            if (!done) RuinWorkers.awaitForTest(RuinWorkers.Priority.NEAR, 60_000);
        }
        h.assertTrue(done, "the flash layer is not finished");
        return f;
    }

    /** Detonation low over the field in {@code base} (the light burns the grass), according to the location in the world. */
    private static Detonation flashDetonation(GameTestHelper h, BlockPos base) {
        // by place in the world, without relativePos: for GameTestHelper relativePos and absolutePos are not inverse to each other
        double scale = 0.0182;
        BlockPos g = base.offset(8, 2, 8);
        return new Detonation(NuclearGameTests.IDS.incrementAndGet(), new Vec3(g.getX() + 0.5, g.getY() + Yield.optimalBurstHeight(15) * scale, g.getZ() + 0.5),
                g.getY(), 15, false, h.getLevel().getGameTime(), 0, 0, 20_000, 7, (float) scale, false, Detonation.OLD_WAVE);
    }

    /** All chunk locations (state numbers), from bottom to top in sections. */
    private static int[] blocks(LevelChunk chunk) {
        var sections = chunk.getSections();
        int[] out = new int[sections.length * 4096];
        for (int i = 0; i < sections.length; i++) {
            for (int k = 0; k < 4096; k++) out[i * 4096 + k] = Block.getId(sections[i].getBlockState(k & 15, k >> 8, (k >> 4) & 15));
        }
        return out;
    }

    /** Return snapshot blocks to the chunk (through the world, without neighbor updates). */
    private static void restore(ServerLevel level, LevelChunk chunk, int[] was) {
        int[] now = blocks(chunk);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int k = 0; k < was.length; k++) {
            if (now[k] == was[k]) continue;
            int i = k >> 12, c = k & 4095;
            m.set(chunk.getPos().getMinBlockX() + (c & 15), chunk.getMinBuildHeight() + (i << 4) + (c >> 8), chunk.getPos().getMinBlockZ() + ((c >> 4) & 15));
            level.setBlock(m, Block.stateById(was[k]), 2 | 16);
        }
    }

    @org.jetbrains.annotations.Nullable
    private static String firstDifference(LevelChunk chunk, int[] a, int[] b) {
        int n = 0;
        String first = null;
        for (int k = 0; k < a.length; k++) {
            if (a[k] == b[k]) continue;
            if (n++ == 0) {
                int i = k >> 12, c = k & 4095;
                first = "section " + i + " place " + (c & 15) + "," + (c >> 8) + "," + ((c >> 4) & 15) + ": " + Block.stateById(a[k]) + " against " + Block.stateById(b[k]);
            }
        }
        return n == 0 ? null : n + " places, first - " + first;
    }

    /**
     * A neighbor of a window that is not on the disk (not fully generated or not saved): the background window does not wait for it to load. {@code RuinContext.requestWindow} reads its neighbors from the disk, and considers the one not found as a solid array,
     *  as the edge of the world. The plan is the same as in the server stream with the same neighbors out of memory (there are no snapshots of them either).
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_window_absent", skyAccess = true)
    public static void windowWithoutNeighboursOnDisk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var chunks = level.getChunkSource();
        ChunkPos pad = new ChunkPos(h.absolutePos(NuclearGameTests.CENTER));
        ChunkPos y = new ChunkPos(pad.x - 40, pad.z + 7);
        StrikeGameTests.afterTest(h, () -> {
            chunks.removeRegionTicket(NuclearGameTests.HOLD, y, 0, y);
            NuclearStrikes.clear(level);
        });
        // Y - immediately (server GameTest ticks faster than background generation), ticket keeps it full, no neighbors
        chunks.addRegionTicket(NuclearGameTests.HOLD, y, 0, y);
        level.getChunk(y.x, y.z);
        h.startSequence()
                .thenExecute(() -> {
                    LevelChunk chunk = chunks.getChunkNow(y.x, y.z);
                    h.assertTrue(chunk != null, "Y not loaded");
                    BlockPos base = new BlockPos(y.getMinBlockX(), 200, y.getMinBlockZ());
                    // not on the chunk boundary: Sable on setBlock reads the neighbors of the place and would load the neighboring chunk
                    for (int ly = 0; ly < 8; ly++) {
                        for (int lx = 1; lx < 15; lx++) {
                            for (int lz = 1; lz < 15; lz++) {
                                boolean edge = lx == 1 || lx == 14 || lz == 1 || lz == 14;
                                if (edge || ly == 7) level.setBlock(base.offset(lx, ly, lz), (ly % 3 == 1 ? Blocks.GLASS : Blocks.BRICKS).defaultBlockState(), 2 | 16);
                            }
                        }
                    }
                    Detonation d = NuclearGameTests.atPsi(h, NuclearGameTests.CENTER, true, h.relativePos(base.offset(8, 4, 8)), 6);
                    int[] window = new int[2];
                    RuinPlan disk = RuinPlanner.planWithWindow(level, d, chunk, window);
                    int inMemory = 0;
                    for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                        for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) {
                            if ((dx != 0 || dz != 0) && chunks.getChunkNow(y.x + dx, y.z + dz) != null) inMemory++;
                        }
                    }
                    int side = 2 * RuinPlanner.REACH + 1;
                    h.assertTrue(window[0] == side * side - 1 - inMemory, "window neighbors read from disk " + window[0] + ", not in memory "
                            + (side * side - 1 - inMemory));
                    h.assertTrue(window[1] > 0, "all neighbors of the window were found on the disk - the case was not reproduced");
                    RuinPlan server = RuinPlanner.planFresh(level, d, chunk, false);
                    h.assertTrue(disk.changedBlocks() > 0, "the building is not destroyed: there is nothing to compare");
                    String diff = disk.differs(server);
                    if (diff == null) diff = flashDiffers(disk, server);
                    h.assertTrue(diff == null, "the plan with a window from disk is not the same as in the server stream:" + diff);
                    Incoming.LOG.info("Window without neighbors on the disk: read {}, no disk space {}, plan - {} places", window[0], window[1], disk.changedBlocks());
                })
                .thenSucceed();
    }

    /** The mixin for sending chunks to the player is up. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_send_gate", skyAccess = true)
    public static void chunkSendGateApplied(GameTestHelper h) {
        h.assertTrue(new PlayerChunkSender(false) instanceof ChunkSendGate.Gated, "PlayerChunkSenderMixin did not get up: chunks will go to the player before the ruins");
        h.succeed();
    }

    /**
     * The chunk that the wave reached while its ruins are being built (background - not earlier than the next tick) is not given to the player: it is removed from the sending queue; when the ruins stand up, it gives away.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "nuke_send_hold", skyAccess = true)
    public static void chunkWithheldUntilRuins(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos glass = NuclearGameTests.CENTER.west(16);
        h.setBlock(glass, Blocks.GLASS);
        long chunk = new ChunkPos(h.absolutePos(glass)).toLong();
        NuclearWorld w = NuclearWorld.get(level);
        Detonation d = NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(NuclearGameTests.CENTER)), 1, false, null, 0.1f);
        boolean[] held = {false};
        h.succeedWhen(() -> {
            LevelChunk c = level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
            boolean scarred = c != null && c.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) >= d.id();
            LongOpenHashSet pending = new LongOpenHashSet(new long[]{chunk});
            var out = ChunkSendGate.withhold(level, pending);
            if (!scarred) {
                if (w.withholds(level, chunk)) {
                    h.assertTrue(out != null && out.contains(chunk) && pending.isEmpty(), "chunk without ruins remained in the sending queue");
                    held[0] = true;
                }
                h.fail("the ruins have not yet risen");
            }
            h.assertTrue(out == null && pending.contains(chunk), "chunk with ruins is not given to the player");
            h.assertTrue(held[0], "chunk never waited for ruins: not verified");
            NuclearStrikes.clear(level);
        });
    }

    /**
     * The retention period for a chunk without ruins is counted from the first request of the player, and not from the wave: a chunk loaded thousands of
     *  ticks after the wave (the zone behind the wave did not reach it, the player was transferred), first waits for ruins, and does not leave immediately
     * (gate 5: 587 chunks went to the player before the ruins).
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_send_ask", skyAccess = true)
    public static void withholdCountsFromFirstAsk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Detonation d = NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(NuclearGameTests.CENTER)), 1, false, null, 0.1f);
        LevelChunk chunk = level.getChunkAt(h.absolutePos(NuclearGameTests.CENTER.west(16)));
        long key = chunk.getPos().toLong();
        ScarQueue q = new ScarQueue();
        q.offer(chunk, d);
        long late = d.gameTime() + 100_000;
        h.assertTrue(q.withholds(key, late), "the chunk, which first asked the player long after the wave, left immediately without ruins");
        h.assertTrue(q.withholds(key, late + 199), "the retention ended prematurely from the first request");
        h.assertFalse(q.withholds(key, late + 200), "the retention did not end within the period from the first request");
        NuclearStrikes.clear(level);
        h.succeed();
    }

    /**
     * The chunk, whose deadline came when no one had seen it, stands in the invisible queue - behind the zone chunks and LOD in the distance. The player
     *  was transported to him and is waiting for his ruins: the first request transfers the chunk to the visible ones (otherwise, after the teleport, the ruins of the player's
     *  chunks go after the invisible ones, and the chunks go to him to the ruins). Budget - for one unit:
     *  The second chunk remains in the queue.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_send_promote", skyAccess = true)
    public static void askedChunkGoesAheadOfUnseen(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Detonation d = NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(NuclearGameTests.CENTER)), 1, false, null, 0.1f);
        ScarQueue q = new ScarQueue();
        List<Long> keys = new ArrayList<>();
        for (int west : new int[]{16, 32}) {
            LevelChunk chunk = level.getChunkAt(h.absolutePos(NuclearGameTests.CENTER.west(west)));
            q.offer(chunk, d);
            keys.add(chunk.getPos().toLong());
        }
        long late = d.gameTime() + 100_000;
        ua.zentix.incoming.nuclear.world.WorkClock clock = ua.zentix.incoming.nuclear.world.WorkClock.counting(1_000_000);
        clock.start(0);
        q.work(level, late, clock);
        List<Long> left = keys.stream().filter(k -> "in the ready queue".equals(q.diagState(k, late))).toList();
        h.assertTrue(left.size() == 1, "there is more than one chunk in the invisible queue: " + keys.stream().map(k -> q.diagState(k, late)).toList());
        q.withholds(left.get(0), late);
        h.assertTrue("in the ready queue, visible".equals(q.diagState(left.get(0), late)), "the requested chunk remained among the invisible: " + q.diagState(left.get(0), late));
        NuclearStrikes.clear(level);
        h.succeed();
    }

    /**
     * A player-visible chunk has a ready plan that became stale during ICBM flight (the player replaced glass):
     * shortly before the wave, the queue checks the plan against the chunk and rebuilds it in the background,
     * first in the ruin pool, so the ruins appear immediately when due. An unseen chunk with the same plan only
     * finds out when due: its plan has only just been submitted to the background pool then. The queue has its own
     * time ({@code work} with an explicit "now": everything in one tick); visibility is stubbed (the GameTest mock
     * player has no view distance).
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_look_ahead", skyAccess = true)
    public static void staleSeenPlanReplannedBeforeWave(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos seenGlass = NuclearGameTests.CENTER.west(8), unseenGlass = NuclearGameTests.CENTER.east(8);
        h.setBlock(seenGlass, Blocks.GLASS);
        h.setBlock(unseenGlass, Blocks.GLASS);
        Detonation d = NuclearGameTests.detonation(h, NuclearGameTests.CENTER, 0, 15, 0.025f);
        LevelChunk seen = level.getChunkAt(h.absolutePos(seenGlass)), unseen = level.getChunkAt(h.absolutePos(unseenGlass));
        long seenKey = seen.getPos().toLong(), unseenKey = unseen.getPos().toLong();
        // the windows of the ruins are immediately in memory: beyond the edge of the site, the chunks are loaded in the background, and the window from the disk would read tick
        for (LevelChunk c : List.of(seen, unseen)) {
            for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) level.getChunk(c.getPos().x + dx, c.getPos().z + dz);
            }
        }
        var plans = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<RuinPlan>();
        plans.put(seenKey, RuinPlanner.plan(level, d, seen));
        plans.put(unseenKey, RuinPlanner.plan(level, d, unseen));
        // the player replaced the glass after the plans
        h.setBlock(seenGlass, Blocks.WHITE_STAINED_GLASS);
        h.setBlock(unseenGlass, Blocks.WHITE_STAINED_GLASS);
        ScarQueue q = new ScarQueue();
        ScarQueue.seenForTest(c -> c == seenKey);
        try {
            q.scanLoaded(d, plans, new long[]{seenKey, unseenKey});
            long seenWave = ScarQueue.due(d, seen.getPos()), last = Math.max(seenWave, ScarQueue.due(d, unseen.getPos()));
            ua.zentix.incoming.nuclear.world.WorkClock clock = ua.zentix.incoming.nuclear.world.WorkClock.counting(1_000_000);
            // all the chunks are in the queue, the wave is still far away
            clock.start(10_000_000_000L);
            q.work(level, seenWave - 60, clock);
            h.assertTrue(q.pendingPlan(d.id(), seenKey) && q.pendingPlan(d.id(), unseenKey), "finished plans are removed before checking in advance");
            // shortly before the wave: visible is checked, its plan is outdated - new in the background
            clock.start(10_000_000_000L);
            q.work(level, seenWave - 20, clock);
            h.assertFalse(q.pendingPlan(d.id(), seenKey), "outdated visible chunk plan not removed before wave");
            h.assertTrue(q.pendingPlan(d.id(), unseenKey), "the plan for a chunk that no one sees is checked in advance");
            h.assertTrue(q.ruinStats()[2] == 1, "outdated plans " + q.ruinStats()[2] + " instead of one");
            h.assertTrue(ua.zentix.incoming.nuclear.world.RuinWorkers.awaitForTest(ua.zentix.incoming.nuclear.world.RuinWorkers.Priority.SEEN, 60_000),
                    "the background plan of the visible chunk is not ready in a minute");
            // wave: visible - ruins immediately, the second only found out that the plan was outdated
            clock.start(10_000_000_000L);
            q.work(level, last - 1, clock);
            h.assertTrue(seen.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) == d.id(), "ruins visible chunk not on time");
            h.assertBlockNotPresent(Blocks.WHITE_STAINED_GLASS, seenGlass);
            h.assertTrue(q.ruinStats()[2] == 2, "chunk plan that no one sees, not checked on time: outdated " + q.ruinStats()[2]);
            h.assertTrue(unseen.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) != d.id(), "the ruins of an invisible chunk stood in place without a plan - the check means nothing");
            h.assertBlockPresent(Blocks.WHITE_STAINED_GLASS, unseenGlass);
        } finally {
            ScarQueue.seenForTest(null);
            q.clear(level);
        }
        h.succeed();
    }

    /**
     * The background plan, which became outdated while it was being built (the player replaced the glass between snapshots and replacement), is not placed and is not
     *  counted: the chunk receives the plan in the server thread on its own, as is, in the same queue. Previously, the replacement did not silently
     * , and the chunk received the mark of ruins and remained intact.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_stale_background", skyAccess = true)
    public static void staleBackgroundPlanRebuiltOnServer(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos glass = NuclearGameTests.CENTER.west(8);
        h.setBlock(glass, Blocks.GLASS);
        Detonation d = NuclearGameTests.detonation(h, NuclearGameTests.CENTER, 0, 15, 0.025f);
        LevelChunk chunk = level.getChunkAt(h.absolutePos(glass));
        ChunkPos pos = chunk.getPos();
        for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
            for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) level.getChunk(pos.x + dx, pos.z + dz);
        }
        ScarQueue q = new ScarQueue();
        try {
            q.offer(chunk, d);
            long wave = ScarQueue.due(d, pos);
            ua.zentix.incoming.nuclear.world.WorkClock clock = ua.zentix.incoming.nuclear.world.WorkClock.counting(1_000_000);
            clock.start(10_000_000_000L);
            q.work(level, wave - 1, clock);
            h.assertTrue("plan in the background".equals(q.diagState(pos.toLong(), wave)), "the plan is not sent to the background: " + q.diagState(pos.toLong(), wave));
            h.assertTrue(ua.zentix.incoming.nuclear.world.RuinWorkers.awaitForTest(ua.zentix.incoming.nuclear.world.RuinWorkers.Priority.NEAR, 60_000),
                    "the background is not ready in a minute");
            // the player replaced the glass while the plan was being built
            h.setBlock(glass, Blocks.WHITE_STAINED_GLASS);
            for (long now = wave; now < wave + 10 && chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) != d.id(); now++) {
                clock.start(10_000_000_000L);
                q.work(level, now, clock);
            }
            h.assertTrue(chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) == d.id(), "no ruins: " + q.diagState(pos.toLong(), wave + 10));
            h.assertBlockNotPresent(Blocks.WHITE_STAINED_GLASS, glass);
            h.assertTrue(q.ruinStats()[2] == 1, "outdated plans " + q.ruinStats()[2] + " instead of one");
        } finally {
            q.clear(level);
        }
        h.succeed();
    }

    /**
     * The pool task, which is waiting in line on the far stage (the plan given by the preparation of the ruins in advance), is transferred to the
     *  chunk stage at the wave and goes before all the distant ones that stand in front of it; started and already important - are not transferred.
     *  Threads are busy with tasks that are waiting for a latch; the queue tasks behind them are waiting for the second one: without the transfer, the released
     *  threads would take them and the distant task would not start while the second latch is held.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "ruin_pool_promote", skyAccess = true)
    public static void queuedFarTaskPromoted(GameTestHelper h) {
        var far = ua.zentix.incoming.nuclear.world.RuinWorkers.Priority.FAR;
        var seen = ua.zentix.incoming.nuclear.world.RuinWorkers.Priority.SEEN;
        int threads = ua.zentix.incoming.nuclear.world.RuinWorkers.threadsForTest();
        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1), queued = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger started = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Callable<Boolean> filler = () -> (started.getAndIncrement() < threads ? running : queued).await(30, java.util.concurrent.TimeUnit.SECONDS);
        List<java.util.concurrent.Future<Boolean>> fillers = new ArrayList<>();
        // the tasks of the previous batch (trace ring, ruins in advance) would occupy the threads
        h.assertTrue(ua.zentix.incoming.nuclear.world.RuinWorkers.awaitForTest(far, 30_000), "the pool of ruins has not been freed from previous tasks");
        try {
            for (int i = 0; i < 3 * threads; i++) fillers.add(ua.zentix.incoming.nuclear.world.RuinWorkers.submitForTest(filler, far));
            java.util.concurrent.Future<String> task = ua.zentix.incoming.nuclear.world.RuinWorkers.submitForTest(() -> "plan", far);
            // all threads are busy: the task is still in queue
            long until = System.nanoTime() + 10_000_000_000L;
            while (started.get() < threads && System.nanoTime() < until) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            h.assertTrue(started.get() == threads, "pool threads not busy: started " + started.get() + " from " + threads);
            h.assertTrue(ua.zentix.incoming.nuclear.world.RuinWorkers.promoteForTest(fillers.get(0), seen) == null, "the started task has been moved");
            java.util.concurrent.Future<String> moved = ua.zentix.incoming.nuclear.world.RuinWorkers.promoteForTest(task, seen);
            h.assertTrue(moved != null, "the task in the queue has not been moved");
            h.assertTrue(ua.zentix.incoming.nuclear.world.RuinWorkers.promoteForTest(moved, seen) == null, "the task has been moved to its own level");
            running.countDown();
            String result = moved.get(10, java.util.concurrent.TimeUnit.SECONDS);
            h.assertTrue("plan".equals(result), "the transferred task gave " + result);
            h.assertFalse(task.isDone(), "the previous task was also completed");
            h.assertTrue(started.get() <= 2 * threads, "the transferred task followed the distant ones: queue tasks started " + (started.get() - threads));
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new net.minecraft.gametest.framework.GameTestAssertException("the transferred task did not complete in 10 s while the queue tasks are waiting: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new net.minecraft.gametest.framework.GameTestAssertException("interrupted");
        } finally {
            running.countDown();
            queued.countDown();
            ua.zentix.incoming.nuclear.world.RuinWorkers.awaitForTest(far, 10_000);
        }
        h.succeed();
    }

    /**
     * Zone behind the wave at the edge of the explored world: a chunk whose window of ruins is intact on the disk receives a plan from the disk, and the square
     *  of the zone where at least one chunk of the windows of its chunks is not intact is not skipped entirely (otherwise the plan waits until the end of the zone and
     *  disappears, there are no ruins on the disk: “unplaced ... chunks"). Here the impact site (9×9 around the target,
     *  is held by the detonation area with launch) and the B strip to the east (6×7 chunks) are generated and unloaded, around - only
     * undergenerated ring. Chunks B with a whole window, which are not in memory, receive a plan from the disk and ruins on the disk,
     *  preparation ends without a single remaining plan, and the world is not generated: the field {@code Status} of chunks around before
     *  and after - the same (the zone does not take the chunk that vanilla would generate).
     */
    @GameTest(template = "range", timeoutTicks = 6000, batch = "nuke_zone_edge", skyAccess = true)
    public static void zoneLoadsEveryChunkWithPlan(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var chunks = level.getChunkSource();
        ChunkPos pad = new ChunkPos(h.absolutePos(NuclearGameTests.CENTER));
        // further than the radius of the ruins from the site: its chunks are in memory, and their ruins would keep preparation
        ChunkPos t = new ChunkPos(pad.x + 80, pad.z);
        LongOpenHashSet whole = new LongOpenHashSet();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) whole.add(ChunkPos.asLong(t.x + dx, t.z + dz));
        }
        for (int dx = 3; dx <= 8; dx++) {
            for (int dz = -3; dz <= 3; dz++) whole.add(ChunkPos.asLong(t.x + dx, t.z + dz));
        }
        for (long c : whole) level.getChunk(ChunkPos.getX(c), ChunkPos.getZ(c));
        Vec3 target = new Vec3(t.getMiddleBlockX() + 0.5,
                level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, t.getMiddleBlockX(), t.getMiddleBlockZ()), t.getMiddleBlockZ() + 0.5);
        // chunks of B with a whole window of ruins behind the explosion area: their plan is only from the disk
        List<ChunkPos> far = new ArrayList<>();
        for (int dx = 5; dx <= 6; dx++) {
            for (int dz = -1; dz <= 1; dz++) far.add(new ChunkPos(t.x + dx, t.z + dz));
        }
        // districts where the zone could load something: field Status before and after
        List<ChunkPos> around = new ArrayList<>();
        for (int x = t.x - 12; x <= t.x + 14; x++) {
            for (int z = t.z - 12; z <= t.z + 12; z++) around.add(new ChunkPos(x, z));
        }
        java.util.Map<Long, String> before = new java.util.concurrent.ConcurrentHashMap<>(), after = new java.util.concurrent.ConcurrentHashMap<>();
        double scale = ua.zentix.incoming.IncomingConfig.SERVER.nukeEffectsScale.get();
        List<String> waited = java.util.Collections.synchronizedList(new ArrayList<>());
        boolean[] prepared = {false};
        Runnable unwatch = NuclearGameTests.watchLog(line -> {
            if (line.contains("unplaced")) waited.add(line);
            if (line.startsWith("Ruins for strike #") && line.contains(" ready:")) prepared[0] = true;
        });
        StrikeGameTests.afterTest(h, () -> {
            unwatch.run();
            ua.zentix.incoming.IncomingConfig.SERVER.nukeEffectsScale.set(scale);
            NuclearStrikes.clear(level);
        });
        NuclearWorld w = NuclearWorld.get(level);
        ua.zentix.incoming.nuclear.NuclearEvents events = ua.zentix.incoming.nuclear.NuclearEvents.get(level);
        int[] stage = {0};
        long[] since = {0}, tickAt = {0};
        Detonation[] det = {null};
        LongOpenHashSet planned = new LongOpenHashSet();
        h.onEachTick(() -> {
            long now = level.getGameTime();
            switch (stage[0]) {
                case 0 -> {
                    // generated was downloaded and written: the titles from the disk are those that remain
                    if (!unloaded(level, around)) return;
                    for (ChunkPos c : around) readStatus(level, c, before);
                    stage[0] = 1;
                }
                case 1 -> {
                    if (before.size() < around.size()) return;
                    for (long c : whole) h.assertTrue("minecraft:full".equals(before.get(c)), "chunk " + new ChunkPos(c) + " not whole on disk: " + before.get(c));
                    ua.zentix.incoming.IncomingConfig.SERVER.nukeEffectsScale.set(0.25);
                    events.schedule(new ua.zentix.incoming.nuclear.NuclearEvents.ScheduledStrike(events.nextId(), target, 1, false, now, now + 1200, target,
                            java.util.Optional.empty(), true));
                    since[0] = now;
                    stage[0] = 2;
                }
                case 2 -> {
                    if (det[0] == null) {
                        det[0] = events.detonations().stream().filter(x -> x.burst().distanceTo(target) < 20).findFirst().orElse(null);
                        if (det[0] == null) {
                            if (now - since[0] > 1500) h.fail("no explosion");
                            // plans from the disk build background flows according to the wall clock: flight - at the pace of the game, until they are ready
                            if (!prepared[0]) gamePace(tickAt);
                            return;
                        }
                        h.assertTrue(prepared[0], "the ruins are not prepared in advance for detonation for 60 s the flight");
                        for (ChunkPos c : far) h.assertTrue(chunks.getChunkNow(c.x, c.z) == null, "chunk " + c + " in memory when detonated: the plan is not from the disk, the case is not the same");
                        since[0] = now;
                    }
                    for (ChunkPos c : far) if (w.scars().pendingPlan(det[0].id(), c.toLong())) planned.add(c.toLong());
                    if (w.plannedChunks() > 0 || w.prepTiles() > 0) {
                        // titles from the disk, loading chunks and plans - according to the wall clock: zone by wave - at the pace of the game
                        //  (server GameTest on CI ticks without pauses, ~2000 tick/s, and the deadline ends before reading from the disk)
                        gamePace(tickAt);
                        if (now - since[0] > 2400) {
                            StringBuilder left = new StringBuilder();
                            for (ChunkPos c : far) {
                                left.append(' ').append(c).append(w.scars().pendingPlan(det[0].id(), c.toLong()) ? " the plan is waiting" : "")
                                        .append(chunks.getChunkNow(c.x, c.z) != null ? " in memory" : "").append(w.scars().ruined(det[0].id(), c.toLong()) ? " ruins" : "");
                            }
                            h.fail("preparation did not end for 2400 tick after the explosion: plans " + w.plannedChunks() + ", squares " + w.prepTiles() + ";" + left);
                        }
                        return;
                    }
                    h.assertTrue(planned.size() == far.size(), "not all B chunks with a whole window have a plan from disk: " + planned.size() + " from " + far.size());
                    for (ChunkPos c : far) {
                        LevelChunk chunk = level.getChunk(c.x, c.z);
                        h.assertTrue(chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) == det[0].id(), "at the chunk " + c + " there are no ruins: the zone did not load him");
                    }
                    for (String line : waited) {
                        // line “unplaced N chunks (in the zone behind the wave M, ...)” - only if the plan remains
                        h.assertFalse(line.startsWith("Ruins for detonation #" + det[0].id() + ": unplaced") && !line.contains("in the zone behind the wave 0,"), "zone plans are gone: " + line);
                    }
                    stage[0] = 3;
                }
                case 3 -> {
                    if (!unloaded(level, around)) return;
                    for (ChunkPos c : around) readStatus(level, c, after);
                    stage[0] = 4;
                }
                case 4 -> {
                    if (after.size() < around.size()) return;
                    for (ChunkPos c : around) {
                        h.assertTrue(before.get(c.toLong()).equals(after.get(c.toLong())), "chunk " + c + " pre-generated: " + before.get(c.toLong()) + " → " + after.get(c.toLong()));
                    }
                    h.succeed();
                }
                default -> {
                }
            }
        });
    }

    /**
     * Stopping on the heap (the live volume of the heap after assemblies is above the limit): ruins are no longer built in advance, but the ready-made plans
     *  do not disappear - zone after wave loads their chunks (otherwise the ready-made plans remain “unplaced”). A chunk without a plan zone
     *  does not load: the lane C to the west
     *  becomes whole only after stopping, it has no plan, and its chunks are never fully loaded (
     *  for two chunks around itself is only fully loaded by ticket queues of ruins at loaded chunks, C - further).
     */
    @GameTest(template = "range", timeoutTicks = 8000, batch = "nuke_zone_heap", skyAccess = true)
    public static void heapStopZoneLoadsOnlyPlanned(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var chunks = level.getChunkSource();
        // its own place far away, where no check goes: batch GameTest go on the same places of the platforms, and chunks
        //  in 120 chunks from the platform loaded completely the previous batch of the full run - the windows of the strip C were intact on the
        //  disk before preparation (places far from other checks - until 6000 blocks diagonally and 12000 blocks along axes)
        ChunkPos t = new ChunkPos(h.absolutePos(NuclearGameTests.CENTER).offset(-9000, 0, -9000));
        LongOpenHashSet whole = new LongOpenHashSet();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) whole.add(ChunkPos.asLong(t.x + dx, t.z + dz));
        }
        for (int dx = 3; dx <= 8; dx++) {
            for (int dz = -3; dz <= 3; dz++) whole.add(ChunkPos.asLong(t.x + dx, t.z + dz));
        }
        for (long c : whole) level.getChunk(ChunkPos.getX(c), ChunkPos.getZ(c));
        Vec3 target = new Vec3(t.getMiddleBlockX() + 0.5,
                level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, t.getMiddleBlockX(), t.getMiddleBlockZ()), t.getMiddleBlockZ() + 0.5);
        // B: the window is intact on disk before preparation - plan from disk
        List<ChunkPos> far = new ArrayList<>();
        for (int dx = 5; dx <= 6; dx++) {
            for (int dz = -1; dz <= 1; dz++) far.add(new ChunkPos(t.x + dx, t.z + dz));
        }
        // C: the window will become whole after stopping (the G stripe is generated). Full loading without a zone - no further than 6 chunks
        //  from the target (detonation site 9×9 and ticket queue of ruins with neighbors of radius 2), so chunks G from 7 and further
        //  are loaded completely only if the zone takes them
        List<ChunkPos> west = new ArrayList<>(), strip = new ArrayList<>(), never = new ArrayList<>();
        for (int dx = -8; dx <= -7; dx++) {
            for (int dz = -1; dz <= 1; dz++) west.add(new ChunkPos(t.x + dx, t.z + dz));
        }
        for (int dx = -10; dx <= -5; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                strip.add(new ChunkPos(t.x + dx, t.z + dz));
                if (dx <= -7) never.add(new ChunkPos(t.x + dx, t.z + dz));
            }
        }
        List<ChunkPos> around = new ArrayList<>(strip);
        for (long c : whole) around.add(new ChunkPos(c));
        java.util.Map<Long, String> before = new java.util.concurrent.ConcurrentHashMap<>();
        double scale = ua.zentix.incoming.IncomingConfig.SERVER.nukeEffectsScale.get();
        List<String> waited = java.util.Collections.synchronizedList(new ArrayList<>());
        boolean[] prepared = {false}, stopped = {false};
        Runnable unwatch = NuclearGameTests.watchLog(line -> {
            if (line.contains("unplaced")) waited.add(line);
            if (line.startsWith("Ruins for strike #") && line.contains(" ready:")) prepared[0] = true;
            if (line.contains("no more ruins are pre-built")) stopped[0] = true;
        });
        StrikeGameTests.afterTest(h, () -> {
            unwatch.run();
            ua.zentix.incoming.IncomingConfig.SERVER.nukeEffectsScale.set(scale);
            NuclearStrikes.clear(level);
        });
        NuclearWorld w = NuclearWorld.get(level);
        ua.zentix.incoming.nuclear.NuclearEvents events = ua.zentix.incoming.nuclear.NuclearEvents.get(level);
        int[] stage = {0};
        long[] since = {0}, tickAt = {0};
        Detonation[] det = {null};
        LongOpenHashSet planned = new LongOpenHashSet();
        h.onEachTick(() -> {
            long now = level.getGameTime();
            switch (stage[0]) {
                case 0 -> {
                    if (!unloaded(level, around)) return;
                    for (ChunkPos c : around) readStatus(level, c, before);
                    stage[0] = 1;
                }
                case 1 -> {
                    if (before.size() < around.size()) return;
                    for (long c : whole) h.assertTrue("minecraft:full".equals(before.get(c)), "chunk " + new ChunkPos(c) + " not whole on disk: " + before.get(c));
                    for (ChunkPos c : west) {
                        boolean partial = false;
                        for (int dx = -2; dx <= 2; dx++) {
                            for (int dz = -2; dz <= 2; dz++) partial |= !"minecraft:full".equals(before.get(ChunkPos.asLong(c.x + dx, c.z + dz)));
                        }
                        h.assertTrue(partial, "chunk window C " + c + " whole on disk before preparation: he will have a plan, the case is not the same");
                    }
                    ua.zentix.incoming.IncomingConfig.SERVER.nukeEffectsScale.set(0.25);
                    events.schedule(new ua.zentix.incoming.nuclear.NuclearEvents.ScheduledStrike(events.nextId(), target, 1, false, now, now + 2400, target,
                            java.util.Optional.empty(), true));
                    since[0] = now;
                    stage[0] = 2;
                }
                case 2 -> {
                    if (now - since[0] > 2300) h.fail("the ruins are not ready in advance for 2300 flight tick");
                    // plans from the disk build background flows according to the wall clock: flight - at the pace of the game, until they are ready
                    if (!prepared[0]) {
                        gamePace(tickAt);
                        return;
                    }
                    h.assertTrue(w.plannedChunks() > 0, "the ruins are ready in advance, but there are no plans");
                    w.stopPrepForHeap();
                    for (ChunkPos c : strip) level.getChunk(c.x, c.z);
                    stage[0] = 3;
                }
                case 3 -> {
                    // lane G is generated and is no longer fully loaded (before detonation it is kept below full load
                    //  area of the detonation): the zone sees the windows C intact, but can only load them
                    if (events.detonations().stream().anyMatch(x -> x.burst().distanceTo(target) < 20)) h.fail("detonation before the G strip dropped below full load");
                    for (ChunkPos c : strip) if (chunks.getChunkNow(c.x, c.z) != null) return;
                    stage[0] = 4;
                }
                case 4 -> {
                    if (det[0] == null) {
                        det[0] = events.detonations().stream().filter(x -> x.burst().distanceTo(target) < 20).findFirst().orElse(null);
                        if (det[0] == null) {
                            if (now - since[0] > 2700) h.fail("no explosion");
                            return;
                        }
                        h.assertTrue(stopped[0], "there is no stop on the heap in the log");
                        for (ChunkPos c : far) h.assertTrue(chunks.getChunkNow(c.x, c.z) == null, "chunk " + c + " in memory when detonated: the plan is not from the disk, the case is not the same");
                        since[0] = now;
                    }
                    for (ChunkPos c : never) h.assertTrue(chunks.getChunkNow(c.x, c.z) == null, "zone loaded chunk " + c + " without a plan after stopping on a heap");
                    for (ChunkPos c : far) if (w.scars().pendingPlan(det[0].id(), c.toLong())) planned.add(c.toLong());
                    if (w.plannedChunks() > 0 || w.prepTiles() > 0) {
                        gamePace(tickAt);
                        if (now - since[0] > 2400) {
                            StringBuilder left = new StringBuilder();
                            for (ChunkPos c : far) {
                                left.append(' ').append(c).append(w.scars().pendingPlan(det[0].id(), c.toLong()) ? " the plan is waiting" : "")
                                        .append(chunks.getChunkNow(c.x, c.z) != null ? " in memory" : "").append(w.scars().ruined(det[0].id(), c.toLong()) ? " ruins" : "");
                            }
                            h.fail("after stopping on the heap, ready plans did not wait for the zone beyond 2400 tick: plans " + w.plannedChunks() + ", squares " + w.prepTiles() + ";" + left);
                        }
                        return;
                    }
                    h.assertTrue(planned.size() == far.size(), "not all B chunks have a plan from disk: " + planned.size() + " from " + far.size());
                    for (ChunkPos c : far) {
                        LevelChunk chunk = level.getChunk(c.x, c.z);
                        h.assertTrue(chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) == det[0].id(), "at the chunk " + c + " with the plan there are no ruins: the zone did not load it");
                    }
                    for (String line : waited) {
                        h.assertFalse(line.startsWith("Ruins for detonation #" + det[0].id() + ": unplaced") && !line.contains("in the zone behind the wave 0,"), "zone plans are gone: " + line);
                    }
                    h.succeed();
                }
                default -> {
                }
            }
        });
    }

    /** tick is no shorter than 50 ms, as in the game: {@code tickAt} - when the last such tick ended. */
    static void gamePace(long[] tickAt) {
        long deadline = tickAt[0] + 50_000_000L, left;
        while ((left = deadline - System.nanoTime()) > 0) java.util.concurrent.locks.LockSupport.parkNanos(left);
        tickAt[0] = System.nanoTime();
    }

    /**
     * Burn flashes of two plans ({@link RuinPlan#flash}: edge labels - place, side, step, block hash) - the same,
     *  in any order of labels; null - the same, otherwise - how many and which ones differ.
     */
    static String flashDiffers(RuinPlan a, RuinPlan b) {
        int[] x = a.flash().clone(), y = b.flash().clone();
        java.util.Arrays.sort(x);
        java.util.Arrays.sort(y);
        if (java.util.Arrays.equals(x, y)) return null;
        java.util.Set<Integer> onlyA = new java.util.TreeSet<>(), onlyB = new java.util.TreeSet<>();
        for (int m : x) onlyA.add(m);
        for (int m : y) onlyB.add(m);
        java.util.Set<Integer> common = new java.util.HashSet<>(onlyA);
        common.retainAll(onlyB);
        onlyA.removeAll(common);
        onlyB.removeAll(common);
        StringBuilder out = new StringBuilder(" flash " + x.length + " | " + y.length + ", only left " + onlyA.size() + ", only right " + onlyB.size());
        int n = 0;
        for (int m : onlyA) {
            if (n++ >= 4) break;
            out.append(String.format(java.util.Locale.ROOT, " [left x %d y %d z %d %s level %d]", ua.zentix.incoming.nuclear.flash.FlashChar.lx(m),
                    ua.zentix.incoming.nuclear.flash.FlashChar.y(m), ua.zentix.incoming.nuclear.flash.FlashChar.lz(m),
                    ua.zentix.incoming.nuclear.flash.FlashChar.face(m), ua.zentix.incoming.nuclear.flash.FlashChar.level(m)));
        }
        n = 0;
        for (int m : onlyB) {
            if (n++ >= 4) break;
            out.append(String.format(java.util.Locale.ROOT, " [right x %d y %d z %d %s level %d]", ua.zentix.incoming.nuclear.flash.FlashChar.lx(m),
                    ua.zentix.incoming.nuclear.flash.FlashChar.y(m), ua.zentix.incoming.nuclear.flash.FlashChar.lz(m),
                    ua.zentix.incoming.nuclear.flash.FlashChar.face(m), ua.zentix.incoming.nuclear.flash.FlashChar.level(m)));
        }
        return out.toString();
    }

    /** Not a single chunk from the list is in memory (or in the upload queue): what they wrote down is already visible for reading from disk. */
    private static boolean unloaded(ServerLevel level, List<ChunkPos> list) {
        var map = level.getChunkSource().chunkMap;
        for (ChunkPos c : list) if (map.getVisibleChunkIfPresent(c.toLong()) != null || map.pendingUnloads.containsKey(c.toLong())) return false;
        return true;
    }

    /** Field {@code Status} of a chunk from disk (in the chunk I/O stream); “no” - there is no chunk on the disk. */
    private static void readStatus(ServerLevel level, ChunkPos c, java.util.Map<Long, String> out) {
        net.minecraft.nbt.visitors.CollectFields f = new net.minecraft.nbt.visitors.CollectFields(
                new net.minecraft.nbt.visitors.FieldSelector(net.minecraft.nbt.StringTag.TYPE, "Status"));
        level.getChunkSource().chunkMap.chunkScanner().scanChunk(c, f).whenComplete((v, e) -> out.put(c.toLong(),
                e != null ? "error " + e : f.getResult() instanceof net.minecraft.nbt.CompoundTag tag ? tag.getString("Status") : "no"));
    }

    /**
     * The square of the zone behind the wave is skipped only if all its chunks are fully loaded now: a chunk in memory lowered
     *  below full loading (the edge of the loaded world) does not cover the square (gate 6: a row of 11 chunks at the edge was left without ruins).
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "nuke_tile_full", skyAccess = true)
    public static void tileWithDemotedChunkIsNotFull(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // a square for +40 chunks is generated in the background: test - at the pace of the game (otherwise the period ends before the generation for CI)
        StrikeGameTests.gameSpeed(h);
        var chunks = level.getChunkSource();
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        ChunkPos centre = new ChunkPos(base.x + 40, base.z);
        ChunkPos ring = new ChunkPos(centre.x + 2, centre.z);
        StrikeGameTests.afterTest(h, () -> {
            chunks.removeRegionTicket(TicketType.FORCED, centre, 2, centre);
            chunks.removeRegionTicket(TicketType.FORCED, centre, 1, centre);
        });
        chunks.addRegionTicket(TicketType.FORCED, centre, 2, centre);
        int[] stage = {0};
        h.onEachTick(() -> {
            if (stage[0] == 0 && NuclearPrep.tileFull(level, centre)) {
                // the edge of the square drops below full load, but remains in memory
                chunks.addRegionTicket(TicketType.FORCED, centre, 1, centre);
                chunks.removeRegionTicket(TicketType.FORCED, centre, 2, centre);
                stage[0] = 1;
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(stage[0] == 1, "the square has not loaded completely yet");
            h.assertTrue(chunks.getChunkNow(ring.x, ring.z) == null, "the edge of the square is still fully loaded");
            var holder = chunks.chunkMap.getVisibleChunkIfPresent(ring.toLong());
            h.assertTrue(holder != null && holder.getChunkIfPresentUnchecked(ChunkStatus.FULL) instanceof LevelChunk,
                    "the edge of the square is unloaded, not lowered: not checked");
            h.assertFalse(NuclearPrep.tileFull(level, centre), "a square with a chunk lower than full loading is considered full");
        });
    }
}
