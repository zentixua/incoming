package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.nuclear.FlashAftermath;
import ua.zentix.incoming.client.nuclear.FlashMarksClient;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.flash.FlashMarks;
import ua.zentix.incoming.nuclear.flash.FlashSync;
import ua.zentix.incoming.registry.ModBlocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scenario {@code flash} (DESIGN-nuke §3.7): flash close. On a flat plain there is an area made of stone bricks and a wall
 *  made of peeled birch (light wood - smoke and shadows are best visible) in 40 blocks from the epicenter, near the wall there are racks for
 *  armor, a resident and a cow, along the square there are lamps on poles and a substation. 15 kt in the air on a scale 0.03
 *  (near the wall ~3 psi and ~19 cal/cm²: the wall is standing, the wood is charred, the stone is darkening), fires are turned off during a close detonation
 *  — the fire would have covered the wall. Shots: before, first tick, wall with silhouettes from a distance, close up and along. Then -
 * detonation on a real scale at 1,3 km from the viewer on another plain: EMP arcs from the lanterns close in the first tick, then
 * hazes from the grass and foliage from a distance before the wave arrives (on a small scale the wave comes after the tick and covers them with dust).
 * Lines {@code SCENARIO flash …} - how many traces at the server and at the client, EMP arcs and smoke.
 */
final class FlashScenario {
    /** Step: after {@code at} tick from the beginning of your part (the beginning of the world, the finished site, the found place of the smoke). */
    private record Step(int part, int at, Runnable action) {}

    private static final int WORLD = 0, STAGE = 1, FAR = 2;

    /** The scale of close-range detonation; the wall is {@link #WALL_R} blocks from the epicenter. */
    private static final float SCALE = 0.03f;
    private static final int WALL_R = 40;
    /** The area relative to the middle of the base of the wall: x from −64 to +24, z ±20. */
    private static final int WEST = 74, EAST = 14, HALF_Z = 20;

    private final List<Step> steps = new ArrayList<>();
    private int tick;
    /** client tick when the part started (−1 - not yet): the site and the place of the smoke are built in the server thread seconds. */
    private final int[] started = {0, -1, -1};
    /** The middle of the base of the wall (floor), places the server. */
    private volatile BlockPos origin;
    /** Spectator's position for distant detonation (ground). */
    private volatile BlockPos far;

    FlashScenario() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
        plan();
    }

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        tick++;
        if (started[STAGE] < 0 && origin != null) started[STAGE] = tick;
        if (started[FAR] < 0 && far != null) started[FAR] = tick;
        for (Step s : List.copyOf(steps)) if (started[s.part] >= 0 && started[s.part] + s.at == tick) s.action.run();
    }

    private void at(int part, int t, Runnable r) {
        steps.add(new Step(part, t, r));
    }

    private void shot(int part, int t, String name) {
        at(part, t, () -> {
            Minecraft mc = Minecraft.getInstance();
            Incoming.LOG.info("SCENARIO flash shot {}: client chunks with marks {}, {}, fps {}", name, FlashMarksClient.chunks(), FlashAftermath.describe(), mc.getFps());
            Screenshot.grab(mc.gameDirectory, String.format(Locale.ROOT, "%s_%04d.png", name, tick), mc.getMainRenderTarget(), c -> {});
        });
    }

    private static void cmd(String c) {
        Minecraft.getInstance().player.connection.sendCommand(c);
        Incoming.LOG.info("SCENARIO /{}", c);
    }

    private static MinecraftServer server() {
        return Minecraft.getInstance().getSingleplayerServer();
    }

    /** The viewer is on point, the gaze is on point. */
    private static void look(double x, double y, double z, double tx, double ty, double tz) {
        double dx = tx - x, dy = ty - y, dz = tz - z;
        float yaw = (float) Math.toDegrees(-Math.atan2(dx, dz)), pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz)));
        cmd(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", x, y, z, yaw, pitch));
    }

    /** The same from the middle of the base of the wall; there is no platform - nothing. */
    private void view(double x, double y, double z, double tx, double ty, double tz) {
        BlockPos o = origin;
        if (o != null) look(o.getX() + x, o.getY() + y, o.getZ() + z, o.getX() + tx, o.getY() + ty, o.getZ() + tz);
    }

    private void plan() {
        at(WORLD, 40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("gamerule doDaylightCycle false");
            cmd("gamerule doWeatherCycle false");
            cmd("gamerule doMobSpawning false");
            cmd("gamerule doFireTick false");
            cmd("gamemode creative");
            Minecraft.getInstance().options.hideGui = true;
        });
        at(WORLD, 60, () -> {
            MinecraftServer server = server();
            server.execute(() -> {
                ServerLevel level = server.overworld();
                BlockPos site = flat(level, BlockPos.ZERO, 0, 1600, 2);
                Incoming.LOG.info("SCENARIO flash stage site {}", site == null ? "-" : site.toShortString());
                if (site != null) origin = build(level, site);
            });
        });
        for (int t : new int[]{1, 40, 80}) {
            at(STAGE, t, () -> {
                Minecraft.getInstance().player.getAbilities().flying = true;
                view(-8, 5, -15, 0, 3, 0);
            });
        }
        shot(STAGE, 260, "flash_before");
        at(STAGE, 270, () -> {
            BlockPos o = origin;
            MinecraftServer server = server();
            server.execute(() -> {
                IncomingConfig.SERVER.nukeFires.set(false);
                ServerLevel level = server.overworld();
                Detonation d = NuclearWarhead.detonate(level, new Vec3(o.getX() - WALL_R + 0.5, o.getY() + 1, o.getZ() + 0.5), 15, true, null, SCALE);
                Vec3 wall = new Vec3(o.getX(), o.getY() + 2, o.getZ() + 0.5);
                Incoming.LOG.info("SCENARIO flash detonation #{} burst {} wall psi {} q {}", d.id(), d.burst(),
                        String.format(Locale.ROOT, "%.1f", d.psi(wall)), String.format(Locale.ROOT, "%.1f", d.fluence(wall)));
            });
        });
        for (int dt : new int[]{2, 4, 6, 9, 14}) shot(STAGE, 270 + dt, "flash_now");
        at(STAGE, 330, this::stats);
        // wall from a distance, close up (silhouettes), along, all from the side
        at(STAGE, 560, () -> view(-8, 5, -15, 0, 3, 0));
        shot(STAGE, 640, "flash_wall");
        at(STAGE, 650, () -> view(-5.5, 1.9, -5.5, 0, 1.4, -5.5));
        shot(STAGE, 730, "flash_shadow_a");
        at(STAGE, 740, () -> view(-5.5, 1.9, 1.5, 0, 1.4, 1.5));
        shot(STAGE, 820, "flash_shadow_b");
        at(STAGE, 830, () -> view(-3.5, 2.2, -11, 0, 1.2, 3));
        shot(STAGE, 910, "flash_along");
        at(STAGE, 920, () -> view(-24, 10, 10, 0, 2, -2));
        shot(STAGE, 1000, "flash_wide");
        at(STAGE, 1010, this::stats);
        // haze to wave - detonation on a real scale at 1,3 km north of the viewer on another plain
        at(STAGE, 1020, () -> {
            BlockPos o = origin;
            MinecraftServer server = server();
            server.execute(() -> {
                IncomingConfig.SERVER.nukeFires.set(true);
                ServerLevel level = server.overworld();
                BlockPos site = flat(level, o, 900, 2400, 4);
                Incoming.LOG.info("SCENARIO flash smoke site {}", site == null ? "-" : site.toShortString());
                if (site == null) return;
                level.getChunk(site.getX() >> 4, site.getZ() >> 4);
                far = new BlockPos(site.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING, site.getX(), site.getZ()), site.getZ());
            });
        });
        at(FAR, 1, () -> {
            BlockPos f = far;
            cmd(String.format(Locale.ROOT, "tp @s %d %d %d", f.getX(), f.getY() + 30, f.getZ()));
        });
        // lights on poles and a substation in front of the viewer are for EMP: on a real scale the wave lasts for seconds
        at(FAR, 150, () -> {
            BlockPos f = far;
            MinecraftServer server = server();
            server.execute(() -> lamps(server.overworld(), f));
        });
        at(FAR, 160, () -> {
            BlockPos f = far;
            cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", f.getX() - 1, f.getY() + 9, f.getZ() - 1, f.getX() + 1, f.getY() + 9, f.getZ() + 1));
            // to the west, the ball is on the right behind the frame: in the frame there is earth and foliage, lit from the side
            look(f.getX() + 0.5, f.getY() + 11, f.getZ() + 0.5, f.getX() - 60, f.getY() - 2, f.getZ() - 12);
        });
        shot(FAR, 230, "smoke_before");
        // arcs - close, from the shadow side (ball - back left): lights in 3–17 blocks and substation in 12; the game is in 4 tick
//  per second: after the explosion, the client without a GPU draws 3 frames per second, and on 20 tick frame for tick did not keep up with
//  (identical pictures in a row, two-tick arc flash between them)
        at(FAR, 232, () -> {
            BlockPos f = far;
            Minecraft.getInstance().player.getAbilities().flying = true;
            look(f.getX() - 14, f.getY() + 3.5, f.getZ() - 8, f.getX() - 24, f.getY() + 1, f.getZ() + 1);
            cmd("tick rate 4");
        });
        shot(FAR, 238, "emp_before");
        at(FAR, 240, () -> {
            BlockPos f = far;
            MinecraftServer server = server();
            server.execute(() -> {
                Detonation d = NuclearWarhead.detonate(server.overworld(), new Vec3(f.getX() + 0.5, f.getY(), f.getZ() - 1300 + 0.5), 15, true, null, 1);
                Incoming.LOG.info("SCENARIO flash smoke detonation #{} burst {} viewer q {} wave in {} ticks", d.id(), d.burst(),
                        String.format(Locale.ROOT, "%.1f", d.fluence(Vec3.atCenterOf(f))), String.format(Locale.ROOT, "%.0f", d.arrivalTicks(1300)));
            });
        });
        for (int dt : new int[]{1, 2, 3, 4, 5, 6, 8, 10, 12, 14, 16, 19, 22}) shot(FAR, 240 + dt, "emp_arcs");
        at(FAR, 264, () -> {
            BlockPos f = far;
            cmd("tick rate 20");
            look(f.getX() + 0.5, f.getY() + 11, f.getZ() + 0.5, f.getX() - 60, f.getY() - 2, f.getZ() - 12);
        });
        for (int dt : new int[]{28, 40, 55, 70, 85, 100}) shot(FAR, 240 + dt, "smoke");
        at(FAR, 360, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
        // no site was found - a way out
        at(WORLD, 6000, () -> {
            if (far != null) return;
            Incoming.LOG.warn("SCENARIO flash: no stage after 5 minutes");
            Minecraft.getInstance().stop();
        });
    }

    /** Traces on the server in wall chunks and on the client. */
    private void stats() {
        BlockPos o = origin;
        MinecraftServer server = server();
        if (o == null) return;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            int chars = 0, shadows = 0;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    var chunk = level.getChunkSource().getChunkNow((o.getX() >> 4) + dx, (o.getZ() >> 4) + dz);
                    if (chunk == null) continue;
                    for (FlashMarks.Layer l : FlashSync.of(chunk).layers()) {
                        chars += l.chars().length;
                        shadows += l.shadows().length;
                    }
                }
            }
            Incoming.LOG.info("SCENARIO flash server marks near wall: {} charred faces, {} silhouette faces", chars, shadows);
        });
        FlashMarks wall = FlashMarksClient.of(ChunkPos.asLong(o.getX() >> 4, o.getZ() >> 4));
        Incoming.LOG.info("SCENARIO flash client: {} chunks with marks, wall chunk {}", FlashMarksClient.chunks(), wall == null ? "none" : wall.size());
    }

    /**
     * Flat plain (server flow) according to the heights of the generator, without loading chunks: rings around {@code from} from
     * {@code min} to {@code max} blocks, land above the sea, the difference above the site is no more than {@code range}; No
     *  was found - the smoothest. The height of the place is the top of the ground according to the generator.
     */
    private static @Nullable BlockPos flat(ServerLevel level, BlockPos from, int min, int max, int range) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState random = level.getChunkSource().randomState();
        int sea = gen.getSeaLevel();
        BlockPos best = null;
        int bestRange = Integer.MAX_VALUE;
        for (int r = Math.max(min, 1); r <= max; r += 112) {
            int n = Math.max(6, (int) (2 * Math.PI * r / 160));
            for (int k = 0; k < n; k++) {
                double a = 2 * Math.PI * k / n;
                int x = from.getX() + (int) (Math.cos(a) * r), z = from.getZ() + (int) (Math.sin(a) * r);
                if (!plains(level.getBiome(new BlockPos(x - 20, sea, z)))) continue;
                int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                for (int dx = -WEST; dx <= EAST && hi - lo <= bestRange; dx += 11) {
                    for (int dz = -HALF_Z; dz <= HALF_Z; dz += 10) {
                        int h = gen.getBaseHeight(x + dx, z + dz, Heightmap.Types.WORLD_SURFACE_WG, level, random);
                        lo = Math.min(lo, h);
                        hi = Math.max(hi, h);
                    }
                }
                if (lo <= sea + 1 || hi - lo >= bestRange) continue;
                best = new BlockPos(x, gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random) - 1, z);
                bestRange = hi - lo;
                if (bestRange <= range) return best;
            }
        }
        return best;
    }

    private static boolean plains(Holder<Biome> b) {
        return b.is(Biomes.PLAINS) || b.is(Biomes.SUNFLOWER_PLAINS) || b.is(Biomes.MEADOW) || b.is(Biomes.SAVANNA);
    }

    /** Lanterns on poles (server stream) in 14–44 blocks west of the viewer and a substation: EMP arcs in a frame of haze. */
    private static void lamps(ServerLevel level, BlockPos f) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int k = 0; k < 8; k++) {
            int x = f.getX() - 14 - 4 * k, z = f.getZ() + ((k & 1) == 0 ? -5 : 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            level.setBlock(m.set(x, y, z), Blocks.OAK_FENCE.defaultBlockState(), 2);
            level.setBlock(m.set(x, y + 1, z), Blocks.OAK_FENCE.defaultBlockState(), 2);
            level.setBlock(m.set(x, y + 2, z), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false), 2);
        }
        int x = f.getX() - 24, z = f.getZ() - 1;
        level.setBlock(m.set(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z), ModBlocks.SUBSTATION.get().defaultBlockState(), 3);
    }

    /**
     * Site (server stream) at location {@code site} (x, z and the top of the ground): grass, an area of stone bricks in front of a wall of peeled
     *  birch facing west, bodies in front of it, lanterns and a substation between the wall and the epicenter. Returns the midpoint of
     *  of the base of the wall.
     */
    private static BlockPos build(ServerLevel level, BlockPos site) {
        int x = site.getX(), z = site.getZ(), g = site.getY();
        for (int cx = (x - WEST - 16) >> 4; cx <= (x + EAST + 16) >> 4; cx++) {
            for (int cz = (z - HALF_Z - 16) >> 4; cz <= (z + HALF_Z + 16) >> 4; cz++) level.getChunk(cx, cz);
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        int wx = x;
        for (int bx = x - WEST; bx <= x + EAST; bx++) {
            for (int bz = z - HALF_Z; bz <= z + HALF_Z; bz++) {
                boolean plaza = bx >= wx - 14 && bx < wx && Math.abs(bz - z) <= 12;
                for (int y = g - 4; y <= g + 30; y++) {
                    BlockState st = y < g - 1 ? Blocks.STONE.defaultBlockState() : y < g ? Blocks.DIRT.defaultBlockState()
                            : y == g ? (plaza ? Blocks.STONE_BRICKS : Blocks.GRASS_BLOCK).defaultBlockState() : air;
                    level.setBlock(m.set(bx, y, bz), st, 2);
                }
            }
        }
        for (int bz = z - 10; bz <= z + 10; bz++) {
            for (int y = g + 1; y <= g + 7; y++) {
                level.setBlock(m.set(wx, y, bz), Blocks.STRIPPED_BIRCH_LOG.defaultBlockState(), 2);
                level.setBlock(m.set(wx + 1, y, bz), Blocks.STONE_BRICKS.defaultBlockState(), 2);
            }
        }
        // lamps on poles along the square and a substation
        for (int bx = x - 40; bx <= x - 4; bx += 6) {
            for (int s : new int[]{-13, 13}) {
                level.setBlock(m.set(bx, g + 1, z + s), Blocks.OAK_FENCE.defaultBlockState(), 2);
                level.setBlock(m.set(bx, g + 2, z + s), Blocks.OAK_FENCE.defaultBlockState(), 2);
                level.setBlock(m.set(bx, g + 3, z + s), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false), 2);
            }
        }
        level.setBlock(m.set(x - 9, g + 1, z + 16), ModBlocks.SUBSTATION.get().defaultBlockState(), 3);
        // body against the wall: the shadow lies almost full-length, just below the body (the ball is ~25° higher)
        double bx = wx - 0.7;
        var src = level.getServer().createCommandSourceStack().withSuppressedOutput().withPermission(4);
        String at = String.format(Locale.ROOT, "%.2f %d", bx, g + 1);
        String[] summons = {
                String.format(Locale.ROOT, "summon minecraft:armor_stand %s %.1f {Rotation:[90f,0f],ShowArms:1b,NoBasePlate:1b}", at, z - 5.5),
                String.format(Locale.ROOT, "summon minecraft:armor_stand %s %.1f {Rotation:[90f,0f],ShowArms:1b,NoBasePlate:1b,Pose:{RightArm:[-110f,0f,0f],LeftArm:[-110f,0f,0f]}}", at, z - 3.0),
                String.format(Locale.ROOT, "summon minecraft:villager %s %.1f {NoAI:1b,Rotation:[90f,0f]}", at, z + 1.5),
                String.format(Locale.ROOT, "summon minecraft:armor_stand %s %.1f {Rotation:[0f,0f],ShowArms:1b,NoBasePlate:1b}", at, z + 4.0),
                String.format(Locale.ROOT, "summon minecraft:cow %.2f %d %.1f {NoAI:1b,Rotation:[0f,0f]}", bx - 0.25, g + 1, z + 7.5),
        };
        for (String c : summons) level.getServer().getCommands().performPrefixedCommand(src, c);
        return new BlockPos(wx, g, z);
    }
}
