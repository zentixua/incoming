package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.radiation.FlashEyes;
import ua.zentix.incoming.nuclear.radiation.RetinaScars;

import java.util.UUID;

/** Flash retinal burn ({@link FlashEyes}). */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EyeGameTests {
    private EyeGameTests() {}

    private static final BlockPos CENTER = NuclearGameTests.CENTER;

    /**
     * 15 kt: the one looking at the ball has a spot in the middle of his gaze the size of the image of the ball; the ball to the right and higher is the spot
     *  to the right and higher; the ball behind the back or further 60° from the view - no. In 40 km (1 block = 1 km) - between the burn ranges of
     *  during the day (~31 km) and at night (~53 km): no during the day, but for those accustomed to darkness, yes.
     */
    @GameTest(template = "range", batch = "nuke_eyes", skyAccess = true)
    public static void retinaBurnWhereFireballWas(GameTestHelper h) {
        FakePlayer p = player(h, 0, 0);
        Vec3 eye = p.getEyePosition();
        Detonation near = detonation(h, eye.add(0, 0, 40), 0.1f);
        RetinaScars.Scar s = FlashEyes.scar(near, p, 0);
        h.assertTrue(s != null, "looked at the ball at 400 m - no retinal burn");
        h.assertTrue(Math.abs(s.x()) < 1e-4 && Math.abs(s.y()) < 1e-4, "the spot is not in the middle of the view: " + s);
        h.assertTrue(Math.abs(s.r() - near.fireballRadius() / 40) < 1e-4, "the spot is not the size of the image of the ball: " + s);
        // to the right of looking south (+Z) - west (−X)
        RetinaScars.Scar side = FlashEyes.scar(detonation(h, eye.add(-20, 10, 40), 0.1f), p, 0);
        h.assertTrue(side != null && Math.abs(side.x() - 0.5) < 1e-3 && Math.abs(side.y() - 0.25) < 1e-3, "the ball is to the right and higher, and the spot: " + side);
        h.assertTrue(FlashEyes.scar(detonation(h, eye.add(-80, 0, 40), 0.1f), p, 0) == null, "the ball in 63° burned the retina from the look");
        h.assertTrue(FlashEyes.scar(detonation(h, eye.add(0, 0, -40), 0.1f), p, 0) == null, "the ball behind my back burned my retina");
        Detonation far = detonation(h, eye.add(0, 0, 40), 0.001f);
        h.assertTrue(FlashEyes.scar(far, p, 0) == null, "during the day at 40 km burned the retina");
        h.assertTrue(FlashEyes.scar(far, p, 1) != null, "at night in 40 km did not burn the retina");
        h.succeed();
    }

    /**
     * Through the light one entity at a time, like an impulse: looking up at the ball at 4 km (there are no skin burns or doses there)
     *  receives a spot, a second flash - a second, no more {@link RetinaScars#MAX} is remembered; in creative mode - no.
     *  Exploding a player does not burn the retina of someone whom the rules do not allow to be injured (without PvP, own team), as well as the skin.
     */
    @GameTest(template = "range", batch = "nuke_eyes_flash", skyAccess = true)
    public static void flashAddsScarsToPlayer(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer p = player(h, 0, -45);
        Detonation d = detonation(h, p.getEyePosition().add(p.getViewVector(1).scale(40)), 0.01f);
        h.assertTrue(NuclearWarhead.sees(level, d, p), "the ball is not visible above the platform - the check means nothing");
        NuclearWarhead.expose(level, d, p, null);
        h.assertValueEqual(FlashEyes.scars(p).scars().size(), 1, "spots after flash");
        NuclearWarhead.expose(level, d, p, null);
        h.assertValueEqual(FlashEyes.scars(p).scars().size(), 2, "spots after the second flash");
        for (int i = 0; i < 2 * RetinaScars.MAX; i++) NuclearWarhead.expose(level, d, p, null);
        h.assertValueEqual(FlashEyes.scars(p).scars().size(), RetinaScars.MAX, "spots after many flashes");
        h.assertTrue(p.isAlive() && p.getRemainingFireTicks() <= 0, "in 4 km from 15 the skin was burned - the check is not about the eyes");
        FakePlayer creative = player(h, 0, -45);
        creative.setGameMode(GameType.CREATIVE);
        NuclearWarhead.expose(level, d, creative, null);
        h.assertTrue(FlashEyes.scars(creative).isEmpty(), "in creative mode burned my retina");
        FakePlayer owner = player(h, 0, 0), friend = player(h, 0, -45, false), enemy = player(h, 0, -45, true);
        NuclearWarhead.expose(level, d, friend, owner);
        h.assertTrue(FlashEyes.scars(friend).isEmpty(), "blowing up a player burned the retina of someone whom the rules do not allow to be injured");
        NuclearWarhead.expose(level, d, enemy, owner);
        h.assertValueEqual(FlashEyes.scars(enemy).scars().size(), 1, "stains from a player's explosion on someone who can be injured");
        h.succeed();
    }

    /**
     * The ridge is 100 blocks from the player, the ball is 2 km behind it: rare shadow points on the height map (through 130 blocks) the ridge
     *  would be stepped over, a close check of the eye sees it. The ridge is behind the platform to +X, with its own blocks (chunks load the test),
     *  is removed after checking.
     */
    @GameTest(template = "range", batch = "nuke_eyes_terrain", skyAccess = true)
    public static void ridgeNearFarPlayerHidesFireball(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer p = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "incoming_ridge"));
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(4, 13, 32)));
        p.moveTo(at.x, at.y, at.z, -90, 0);
        // up on 1 block for every 2 sideways: over the wall of barriers around the site
        Detonation d = detonation(h, p.getEyePosition().add(2000, 1000, 0), 1);
        BlockPos from = h.absolutePos(new BlockPos(100, 13, 24)), to = h.absolutePos(new BlockPos(107, 80, 40));
        // ready chunks from the player to the ridge: a tight check goes to the first unready one
        BlockPos start = h.absolutePos(new BlockPos(0, 13, 24));
        for (int cx = Math.min(start.getX(), to.getX()) >> 4; cx <= Math.max(start.getX(), to.getX()) >> 4; cx++) {
            for (int cz = Math.min(from.getZ(), to.getZ()) >> 4; cz <= Math.max(from.getZ(), to.getZ()) >> 4; cz++) level.getChunk(cx, cz);
        }
        h.assertTrue(NuclearWarhead.sees(level, d, p), "without a ridge the ball is not visible - the check means nothing");
        try {
            for (BlockPos b : BlockPos.betweenClosed(from, to)) level.setBlock(b, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            h.assertFalse(NuclearWarhead.sees(level, d, p), "the ball is visible through the ridge in 100 blocks");
        } finally {
            for (BlockPos b : BlockPos.betweenClosed(from, to)) level.setBlock(b, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        h.succeed();
    }

    /** The player is above the middle of the court, looking - turn and tilt (0, 0 - south, +Z). */
    private static FakePlayer player(GameTestHelper h, float yaw, float pitch) {
        return place(h, new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "incoming_eyes")), yaw, pitch);
    }

    /** A player whom the rules of the game (PvP, commands) allow or prevent other players from being injured. */
    private static FakePlayer player(GameTestHelper h, float yaw, float pitch, boolean harmable) {
        return place(h, new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "incoming_eyes")) {
            @Override
            public boolean canHarmPlayer(Player other) {
                return harmable;
            }
        }, yaw, pitch);
    }

    private static FakePlayer place(GameTestHelper h, FakePlayer p, float yaw, float pitch) {
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER.above()));
        p.moveTo(at.x, at.y, at.z, yaw, pitch);
        p.setYHeadRot(yaw);
        return p;
    }

    /** 15 kt in air in {@code burst}; {@code scale} - blocks per meter. */
    private static Detonation detonation(GameTestHelper h, Vec3 burst, float scale) {
        return new Detonation(NuclearGameTests.IDS.incrementAndGet(), burst, h.absolutePos(CENTER).getY(), 15, false, h.getLevel().getGameTime(),
                0, 0, 20_000, 7, scale, false, Detonation.OLD_WAVE);
    }
}
