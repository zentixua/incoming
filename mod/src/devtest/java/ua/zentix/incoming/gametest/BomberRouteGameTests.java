package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.strike.*;
import ua.zentix.incoming.target.Target;

import java.util.List;
import java.util.UUID;

/** B-2's shared route state advances outside the world, then releases a bomb onto the actual target. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BomberRouteGameTests {
    private BomberRouteGameTests() {}

    @GameTest(template = "runway", batch = "hidden_bomber_route", timeoutTicks = 4000, skyAccess = true)
    public static void bomberVisitsWaypointsThenBombHits(GameTestHelper h) { route(h, false, false); }

    @GameTest(template = "runway", batch = "hidden_bomber_close", timeoutTicks = 4000, skyAccess = true)
    public static void lastWaypointBesideTargetStillGetsABombRun(GameTestHelper h) { route(h, true, false); }

    @GameTest(template = "runway", batch = "hidden_bomber_real", timeoutTicks = 5500, skyAccess = true)
    public static void realAltitudeRouteStillReleasesOnTarget(GameTestHelper h) { route(h, true, true); }

    private static void route(GameTestHelper h, boolean close, boolean real) {
        if (real) StrikeGameTests.flightScale(h, 1);
        ServerLevel level = h.getLevel();
        UUID owner = UUID.randomUUID();
        Vec3 aim = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 3, 200)));
        // Only prepare the target for a deterministic arrival; waypoint legs remain outside loaded chunks.
        ChunkPos chunk = new ChunkPos(BlockPos.containing(aim));
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) level.getChunk(chunk.x + dx, chunk.z + dz);
        Waypoints via = new Waypoints(List.of(aim.add(4000, 0, -4000), close ? aim.add(80, 0, -40) : aim.add(0, 0, -3000)));
        h.assertTrue(StrikeService.launch(level, WeaponType.BUNKER, new Target.Ground(aim), aim, 0, owner, false,
                Loadout.Nuke.DEFAULT, via, null).ok(), "B-2 order failed");
        BomberEntity original = (BomberEntity) StrikeWorld.projectiles(level).stream().filter(p -> owner.equals(p.ownerId())).findFirst().orElseThrow();
        UUID id = original.getUUID();
        Vec3 expected = original.aimPoint(); // spec guidance miss belongs to this mission
        int[] reached = {0};
        boolean[] virtual = {false}, materialized = {false};
        BomberEntity[] last = {original};
        BunkerBusterEntity[] bomb = {null};
        StrikeGameTests.afterTest(h, () -> {
            VirtualFlights.get(level).clear(level, p -> owner.equals(p.ownerId()));
            new java.util.ArrayList<>(StrikeWorld.projectiles(level)).stream().filter(p -> owner.equals(p.ownerId())).forEach(Entity::discard);
        });
        h.onEachTick(() -> {
            for (StrikeProjectile p : StrikeWorld.projectiles(level)) {
                if (!owner.equals(p.ownerId())) continue;
                if (p instanceof BomberEntity b && p.getUUID().equals(id)) {
                    last[0] = b;
                    virtual[0] |= b.isVirtual();
                    materialized[0] |= !b.isVirtual();
                    while (reached[0] < via.size() && b.position().subtract(via.points().get(reached[0])).horizontalDistance() <= 2 * WeaponSpec.BUNKER.capture()) reached[0]++;
                    h.assertTrue(!b.hasReleased() || reached[0] == via.size() && b.route().finished(), "bomb released before waypoints/final run");
                } else if (p instanceof BunkerBusterEntity b) bomb[0] = b;
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(reached[0] == via.size(), "B-2 did not visit waypoints: " + reached[0] + ", at " + last[0].position() + ", route " + last[0].route().index());
            h.assertTrue(bomb[0] != null && bomb[0].isDrilling(), "no bomb ground entry; B-2 at " + last[0].position() + ", route " + last[0].route().index() + ", released " + last[0].hasReleased());
            h.assertTrue(bomb[0].entry().subtract(expected).horizontalDistance() < 4, "routed bomb missed by " + bomb[0].entry().subtract(expected).horizontalDistance());
            h.assertTrue(virtual[0] && materialized[0], "route did not exercise virtual/world flight");
        });
    }
}
