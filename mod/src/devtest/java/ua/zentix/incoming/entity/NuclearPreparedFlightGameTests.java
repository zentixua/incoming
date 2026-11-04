package ua.zentix.incoming.entity;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.world.NuclearWorld;
import ua.zentix.incoming.nuclear.world.RuinPreparation;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.StrikeService;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

/** Flight integration against controlled ready/unready backend fixtures; solver receipts have separate tests. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NuclearPreparedFlightGameTests {
    private NuclearPreparedFlightGameTests() {}

    @GameTest(template = "range", batch = "nuke_prepared_flight", skyAccess = true)
    public static void scheduledIdentityPreservesConsoleOwnerAndConsumesOnlyItsOwnPreparation(GameTestHelper h) throws ReflectiveOperationException {
        ServerLevel level = h.getLevel();
        NuclearStrikes.clear(level);
        UUID handle = UUID.randomUUID(), owner = UUID.randomUUID();
        Vec3 target = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 1, 32)));
        Loadout.Nuke nuke = new Loadout.Nuke(1, false);
        try {
            RuinPreparation.prepare(level, handle, owner, target, 1, false, false);
            var unready = StrikeService.launchPrepared(level, WeaponType.NUKE, new Target.Point(target), target, 0, owner, null,
                    false, nuke, Waypoints.NONE, null, handle, 1800);
            h.assertFalse(unready.ok(), "unready request launched");
            h.assertTrue(NuclearEvents.get(level).scheduled().isEmpty(), "unready launch left scheduled flight");
            Object prep = makeReady(level, handle, target);
            var accepted = StrikeService.launchPrepared(level, WeaponType.NUKE, new Target.Point(target), target, 0, owner, null,
                    false, nuke, Waypoints.NONE, null, handle, 1800);
            h.assertTrue(accepted.ok(), "ready console order rejected");
            var scheduled = NuclearEvents.get(level).scheduled().getFirst();
            h.assertTrue(scheduled.owner().orElseThrow().equals(owner), "console request lost backend owner identity");
            h.assertTrue(scheduled.target().equals(target), "scheduler recomputed accepted exact target");
            h.assertTrue(scheduled.detonateTime() - scheduled.launchTime() == 1800, "preparation changed configured flight");
            h.assertTrue(field(prep, "strike").getInt(prep) == scheduled.id(), "preparation bound to a different scheduled flight");
            var duplicate = StrikeService.launchPrepared(level, WeaponType.NUKE, new Target.Point(target), target, 0, owner, null,
                    false, nuke, Waypoints.NONE, null, handle, 1800);
            h.assertFalse(duplicate.ok(), "bound request launched twice");
            h.assertTrue(NuclearEvents.get(level).scheduled().size() == 1, "failed duplicate left another scheduled event");
            var event = NuclearWarhead.detonatePrepared(level, target, 1, false, owner, scheduled.id());
            h.assertTrue(field(prep, "detonation").getInt(prep) == event.id(), "scheduled identity did not transfer to actual event ID");
        } finally { NuclearStrikes.clear(level); }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_prepared_carrier", skyAccess = true)
    public static void carrierIdentitySurvivesSaveAndVirtualCopyAndReleasesOnFinalLoss(GameTestHelper h) throws ReflectiveOperationException {
        ServerLevel level = h.getLevel();
        NuclearStrikes.clear(level);
        boolean carriers = IncomingConfig.SERVER.carrierNukes.get();
        IncomingConfig.SERVER.carrierNukes.set(true);
        UUID handle = UUID.randomUUID(), owner = UUID.randomUUID();
        Vec3 target = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 1, 32)));
        Loadout.Nuke nuke = new Loadout.Nuke(1, false, true);
        try {
            RuinPreparation.prepareCarrier(level, handle, owner, target, 1, false, false, WeaponType.MISSILE);
            makeReady(level, handle, target);
            var accepted = StrikeService.launchPrepared(level, WeaponType.MISSILE, new Target.Point(target), target, 0, owner, null,
                    false, nuke, Waypoints.NONE, null, handle, 1800);
            h.assertTrue(accepted.ok(), "ready carrier rejected");
            var carrier = VirtualFlights.get(level).flights().stream().filter(p -> owner.equals(p.ownerId()) && !p.isRemoved()).findFirst().orElseThrow();
            int identity = carrier.nuclearPreparation();
            h.assertTrue(identity >= 0 && NuclearEvents.get(level).scheduled().isEmpty(), "carrier invented a timed impact");
            CompoundTag tag = new CompoundTag(); carrier.saveWithoutId(tag);
            var copy = ModEntities.CRUISE_MISSILE.get().create(level);
            h.assertTrue(copy != null, "carrier copy missing");
            copy.load(tag);
            h.assertTrue(copy.nuclearPreparation() == identity, "saved carrier lost identity");
            carrier.handOverTargetArea(copy);
            carrier.discard();
            h.assertTrue(RuinPreparation.status(level, handle).stage() == RuinPreparation.Stage.READY,
                    "old virtual form cancelled transferred identity");
            h.assertTrue(copy.retarget(new Target.Point(target.add(100, 0, 0)), target.add(100, 0, 0)), "retarget refused");
            h.assertTrue(copy.nuclearPreparation() < 0 && RuinPreparation.status(level, handle).stage() == RuinPreparation.Stage.ABSENT,
                    "retarget retained incompatible prepared terrain");
            copy.discard();
        } finally {
            VirtualFlights.get(level).clear(level, p -> owner.equals(p.ownerId()));
            RuinPreparation.cancel(level, handle);
            NuclearStrikes.clear(level);
            IncomingConfig.SERVER.carrierNukes.set(carriers);
        }
        h.succeed();
    }

    @GameTest(template = "range", batch = "nuke_prepared_drop", skyAccess = true)
    public static void bomberDropTransfersPreparationWithoutCancellingItsBomb(GameTestHelper h) throws ReflectiveOperationException {
        ServerLevel level = h.getLevel();
        NuclearStrikes.clear(level);
        boolean carriers = IncomingConfig.SERVER.carrierNukes.get();
        IncomingConfig.SERVER.carrierNukes.set(true);
        UUID handle = UUID.randomUUID(), owner = UUID.randomUUID();
        Vec3 target = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 1, 32)));
        Loadout.Nuke nuke = new Loadout.Nuke(1, false, true);
        try {
            RuinPreparation.prepareCarrier(level, handle, owner, target, 1, false, false, WeaponType.BUNKER);
            makeReady(level, handle, target);
            h.assertTrue(StrikeService.launchPrepared(level, WeaponType.BUNKER, new Target.Point(target), target, 0, owner, null,
                    false, nuke, Waypoints.NONE, null, handle, 1800).ok(), "ready bomber rejected");
            var bomber = (BomberEntity) VirtualFlights.get(level).flights().stream()
                    .filter(p -> owner.equals(p.ownerId()) && p instanceof BomberEntity).findFirst().orElseThrow();
            int identity = bomber.nuclearPreparation();
            var release = BomberEntity.class.getDeclaredMethod("release", ServerLevel.class, Vec3.class);
            release.setAccessible(true);
            release.invoke(bomber, level, target);
            var bomb = VirtualFlights.get(level).flights().stream()
                    .filter(p -> owner.equals(p.ownerId()) && p instanceof BunkerBusterEntity).findFirst().orElseThrow();
            h.assertTrue(bomb.nuclearPreparation() == identity && bomber.nuclearPreparation() < 0, "drop duplicated or lost ownership");
            bomber.discard();
            h.assertTrue(RuinPreparation.status(level, handle).stage() == RuinPreparation.Stage.READY,
                    "departing bomber cancelled its dropped bomb preparation");
            bomb.discard();
            h.assertTrue(RuinPreparation.status(level, handle).stage() == RuinPreparation.Stage.ABSENT,
                    "lost bomb retained preparation");
        } finally {
            VirtualFlights.get(level).clear(level, p -> owner.equals(p.ownerId()));
            RuinPreparation.cancel(level, handle);
            NuclearStrikes.clear(level);
            IncomingConfig.SERVER.carrierNukes.set(carriers);
        }
        h.succeed();
    }

    @SuppressWarnings("unchecked")
    private static Object makeReady(ServerLevel level, UUID handle, Vec3 target) throws ReflectiveOperationException {
        var method = NuclearWorld.class.getDeclaredMethod("prep");
        method.setAccessible(true);
        Object backend = method.invoke(NuclearWorld.get(level));
        Object request = ((List<Object>) field(backend, "preps").get(backend)).stream()
                .filter(p -> {
                    try { return handle.equals(field(p, "handle").get(p)) && !field(p, "draining").getBoolean(p); }
                    catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                }).findFirst().orElseThrow();
        var weapon = (WeaponType) field(request, "carrierWeapon").get(request);
        Vec3 terminal = target.add((Vec3) field(request, "miss").get(request));
        if (weapon == WeaponType.BUNKER) terminal = NuclearWarhead.surfaceAt(level, terminal).add(0, -.5, 0);
        var geometry = NuclearWarhead.geometry(level, terminal, 1, false, (float) IncomingConfig.SERVER.effectsScale());
        field(request, "geometry").set(request, geometry);
        var contextType = Class.forName("ua.zentix.incoming.nuclear.world.RuinContext");
        var constructor = contextType.getDeclaredConstructor(ServerLevel.class, ua.zentix.incoming.nuclear.Detonation.class);
        constructor.setAccessible(true);
        field(request, "ruins").set(request, constructor.newInstance(level, geometry));
        field(request, "resolvedTarget").set(request, target);
        return request;
    }

    private static Field field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
