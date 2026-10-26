package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.entity.flight.TargetAreaHold;
import ua.zentix.incoming.guidance.Route;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.AreaLoader;
import ua.zentix.incoming.strike.FlightTickets;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Terrain;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The area belongs to the mission, not its current entity: its ticket stays unchanged even within one tick. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AreaHandoverGameTests {
    private AreaHandoverGameTests() {}

    @GameTest(template = "range", timeoutTicks = 100, batch = "area_handover_ready", skyAccess = true)
    public static void readyAreaSurvivesHandover(GameTestHelper h) {
        handovers(h, true, true);
    }

    @GameTest(template = "range", timeoutTicks = 100, batch = "area_handover_loading", skyAccess = true)
    public static void loadingAreaSurvivesHandover(GameTestHelper h) {
        handovers(h, false, false);
    }

    @GameTest(template = "range", timeoutTicks = 100, batch = "area_handover_growing", skyAccess = true)
    public static void growingAreaSurvivesHandover(GameTestHelper h) {
        handovers(h, false, true);
    }

    private static void handovers(GameTestHelper h, boolean ready, boolean ticks) {
        ServerLevel level = h.getLevel();
        Vec3 start = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 80, 32)));
        Vec3 aim = start.add(640, 0, 0);
        ChunkPos centre = new ChunkPos(BlockPos.containing(aim));
        if (ready) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) level.getChunk(centre.x + dx, centre.z + dz);
            }
        }
        h.assertTrue(Terrain.ready(level, BlockPos.containing(aim)) == ready, "wrong initial target readiness");
        FakePlayer watcher = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "handover"));
        watcher.moveTo(aim.x, aim.y, aim.z);
        level.players().add(watcher);
        StrikeGameTests.afterTest(h, () -> level.players().remove(watcher));

        StrikeProjectile p = missile(level, start, aim);
        UUID id = p.getUUID();
        List<StrikeProjectile> forms = new ArrayList<>();
        forms.add(p);
        List<ChunkPos> approach = FlightTickets.approach(List.of(aim, start), 64);
        UUID neighbour = UUID.randomUUID();
        StrikeGameTests.afterTest(h, () -> {
            VirtualFlights.get(level).clear(level, q -> q.getUUID().equals(id));
            forms.forEach(StrikeProjectile::discard);
            FlightTickets.releaseApproach(level, approach, neighbour);
        });
        h.assertTrue(level.addFreshEntity(p), "initial entity refused");
        hold(p, aim, ticks);
        h.assertTrue(FlightTickets.held(level, id) == 1 + approach.size(), "target/approach not held");
        // A second projectile shares the approach: cancelling the first must preserve the other reference.
        h.assertTrue(FlightTickets.holdApproach(level, approach, neighbour), "shared approach refused");
        Map<ChunkPos, Set<UUID>> refs = new HashMap<>(approachRefs(level));
        List<Ticket<?>> before = areaTickets(level);
        Map<ChunkHolder, Integer> levels = holderLevels(level);
        h.assertTrue(!before.isEmpty() && !levels.isEmpty(), "no tickets/holders to observe");
        for (int i = 0; i < 4; i++) {
            p.holdChunks(level);
            h.assertTrue(TicketProbe.count(level, "incoming_projectile", id) > 0, "no own chunk before park");
            StrikeProjectile old = p;
            p = VirtualFlights.park(level, old);
            h.assertTrue(p != null && p != old && p.getUUID().equals(id), "park did not create the same mission");
            forms.add(p);
            h.assertTrue(old.isRemoved() && p.isVirtual(), "wrong flight forms after park");
            h.assertValueEqual(TicketProbe.count(level, "incoming_projectile", id), 0, "own chunks after park");
            // Late or repeated removal callbacks from the old form no longer own the copy's area.
            old.onRemovedFromLevel();
            old.discard();
            unchanged(h, before, levels, refs, id, neighbour);
            h.assertTrue(VirtualFlights.get(level).flights().remove(p), "copy absent from virtual flights");
            p.materialize(level);
            h.assertTrue(level.addFreshEntity(p), "materialized entity refused");
            unchanged(h, before, levels, refs, id, neighbour);
            // Holding the same target again after handover must not recreate its area either.
            hold(p, aim, ticks);
            unchanged(h, before, levels, refs, id, neighbour);
        }
        // Retargeting releases the old area and only this mission's reference to the shared approach.
        Vec3 next = aim.add(320, 0, 0);
        h.assertTrue(p.retarget(new Target.Point(next), next), "retarget refused");
        h.assertValueEqual(FlightTickets.held(level, id), 0, "old target after retarget");
        for (ChunkPos c : approach) h.assertTrue(approachRefs(level).get(c).equals(Set.of(neighbour)), "neighbour lost its approach");
        hold(p, next, false);
        h.assertTrue(FlightTickets.held(level, id) > 0, "new target not held");
        if (ready) {
            p.discard();
        } else {
            p = VirtualFlights.park(level, p);
            h.assertTrue(p != null, "final park failed");
            forms.add(p);
            h.assertTrue(VirtualFlights.get(level).clear(level, q -> q.getUUID().equals(id)).equals(List.of(id)), "cancel missed mission");
        }
        forms.forEach(StrikeProjectile::discard);
        h.assertValueEqual(FlightTickets.held(level, id), 0, "cancelled mission holds areas");
        h.assertTrue(TicketProbe.types(level, id).isEmpty(), "cancelled mission holds tickets");
        FlightTickets.releaseApproach(level, approach, neighbour);
        drained(h);
    }

    @GameTest(template = "range", timeoutTicks = 100, batch = "area_handover_failure", skyAccess = true)
    public static void failedCopyReleasesMission(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 start = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 80, 32)));
        Vec3 aim = start.add(640, 0, 0);
        FakePlayer watcher = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "failed_copy"));
        watcher.moveTo(aim.x, aim.y, aim.z);
        level.players().add(watcher);
        StrikeGameTests.afterTest(h, () -> level.players().remove(watcher));
        for (int mode = 0; mode < 3; mode++) {
            int failure = mode;
            StrikeProjectile p = new CruiseMissileEntity(ModEntities.CRUISE_MISSILE.get(), level) {
                @Override
                public boolean save(CompoundTag tag) {
                    if (failure == 0) return false;
                    if (failure == 1) {
                        super.save(tag);
                        tag.putString("id", "incoming:missing_handover_entity");
                        return true;
                    }
                    throw new IllegalStateException("test: save failed");
                }
            };
            p.placeOnLauncher(start, 0, 40, 10000, 0, new Target.Point(aim), aim, null);
            StrikeGameTests.afterTest(h, p::discard);
            h.assertTrue(level.addFreshEntity(p), "initial entity refused");
            hold(p, aim, false);
            p.holdChunks(level);
            h.assertTrue(FlightTickets.held(level, p.getUUID()) > 1, "failed copy had no target/approach");
            h.assertTrue(VirtualFlights.park(level, p) == null && p.isRemoved(), "failed copy survived");
            p.onRemovedFromLevel();
            p.discard();
            h.assertValueEqual(FlightTickets.held(level, p.getUUID()), 0, "failed copy owns areas");
            h.assertTrue(TicketProbe.types(level, p.getUUID()).isEmpty(), "failed copy left tickets");
            h.assertTrue(VirtualFlights.get(level).flights().stream().noneMatch(q -> q.getUUID().equals(p.getUUID())), "failed copy parked");
        }
        drained(h);
    }

    private static StrikeProjectile missile(ServerLevel level, Vec3 start, Vec3 aim) {
        StrikeProjectile p = ModEntities.CRUISE_MISSILE.get().create(level);
        p.placeOnLauncher(start, 0, 40, 10000, 0, new Target.Point(aim), aim, null);
        p.setRoute(Route.direct());
        return p;
    }

    // Use the entity's real TargetAreaHold without ticking guidance: area readiness and phase stay fixed during handovers.
    private static void hold(StrikeProjectile p, Vec3 aim, boolean ticks) {
        TargetAreaHold area = field(p, StrikeProjectile.class, "aimArea", TargetAreaHold.class);
        area.hold((ServerLevel) p.level(), p.getUUID(), p.position(), aim, 2000, 2, ticks, 64, null, 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<ChunkPos, Set<UUID>> approachRefs(ServerLevel level) {
        return field(StrikeWorld.get(level), StrikeWorld.class, "approach", Map.class);
    }

    private static <T> T field(Object object, Class<?> type, String name, Class<T> valueType) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return valueType.cast(field.get(object));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Ticket<?>> areaTickets(ServerLevel level) {
        List<Ticket<?>> out = new ArrayList<>();
        for (var set : level.getChunkSource().chunkMap.getDistanceManager().tickets.values()) {
            for (Ticket<?> t : set) {
                String type = t.getType().toString();
                Object key = field(t, Ticket.class, "key", Object.class);
                if (type.equals("incoming_flight") || (key instanceof AreaLoader.Area area
                        && area.type().toString().equals("incoming_flight"))) out.add(t);
            }
        }
        return out;
    }

    private static Map<ChunkHolder, Integer> holderLevels(ServerLevel level) {
        level.getChunkSource().runDistanceManagerUpdates();
        Map<ChunkHolder, Integer> out = new HashMap<>();
        for (ChunkHolder holder : level.getChunkSource().chunkMap.getChunks()) out.put(holder, holder.getTicketLevel());
        return out;
    }

    private static void unchanged(GameTestHelper h, List<Ticket<?>> before, Map<ChunkHolder, Integer> levels,
                                  Map<ChunkPos, Set<UUID>> refs, UUID id, UUID neighbour) {
        ServerLevel level = h.getLevel();
        List<Ticket<?>> now = areaTickets(level);
        h.assertValueEqual(now.size(), before.size(), "area ticket count");
        // Ticket.equals is insufficient: remove/add with the same key creates an equal but distinct object.
        for (Ticket<?> ticket : before) h.assertTrue(now.stream().anyMatch(t -> t == ticket), "area ticket removed/re-added");
        level.getChunkSource().runDistanceManagerUpdates();
        levels.forEach((holder, value) -> h.assertTrue(holder.getTicketLevel() <= value, "holder level dropped"));
        refs.forEach((centre, owners) -> {
            h.assertTrue(approachRefs(level).get(centre) == owners, "approach reference set replaced");
            h.assertTrue(owners.equals(Set.of(id, neighbour)), "approach must have one reference per UUID");
        });
    }

    private static void drained(GameTestHelper h) {
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(h.getLevel()).areas().size() == 0, "areas still held");
            h.assertTrue(StrikeWorld.get(h.getLevel()).areas().releasing() == 0, "release queue still draining");
            for (String type : List.of("incoming_flight", "incoming_area_load", "incoming_area_release", "incoming_projectile")) {
                h.assertValueEqual(TicketProbe.count(h.getLevel(), type), 0, "remaining " + type);
            }
        });
    }
}
