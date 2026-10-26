package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.MobPanic;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.NuclearWarhead;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Mobs panic after a nuclear explosion: they run away from the epicenter, then calm down. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PanicGameTests {
    private PanicGameTests() {}

    /** Mob wave pressure: weak throw, no damage (damage from 0,7 psi). */
    private static final double PSI = 0.45;

    /**
     * A cow in a pen 1×1 is looking for a place and a way to escape during the entire panic no more often (with a delay for a locked one - 5 times; with one
     *  limit once per second - 33; without it - every 2 tick, hundreds of times).
     */
    private static final int PENNED_SEARCHES = 12;

    /**
     * The flyer's place of flight: a parrot on flat ground, the epicenter to the west - there is a {@link MobPanic#awayPlace} place
     *  every time (and not vanilla at random: sometimes empty, sometimes in a couple of blocks, and two empty searches in a row postponed the escape for
     * 8 s - CI 22.10, the parrot behind 10 did not move), this is a free cell above the support, and it is further from the epicenter by
     *  half the search radius adjusted for the cone. The cow has the same thing, according to her navigation.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "panic_place", skyAccess = true)
    public static void flyerFindsItsOwnFleePlace(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 from = h.absoluteVec(new Vec3(-200, 12, 32.5));
        for (EntityType<? extends Mob> type : List.of(EntityType.PARROT, EntityType.COW)) {
            Mob mob = h.spawn(type, new BlockPos(32, 12, 32));
            h.assertTrue(mob instanceof net.minecraft.world.entity.PathfinderMob, type.toShortString() + " without paths");
            net.minecraft.world.entity.PathfinderMob pm = (net.minecraft.world.entity.PathfinderMob) mob;
            double here = Math.hypot(mob.getX() - from.x, mob.getZ() - from.z);
            for (int i = 0; i < 64; i++) {
                BlockPos p = MobPanic.awayPlace(pm, from);
                h.assertTrue(p != null, type.toShortString() + ": there is no place of escape (search " + i + ")");
                h.assertTrue(level.getBlockState(p).isAir() && level.getBlockState(p.below()).isSolid(),
                        type.toShortString() + ": place " + h.relativePos(p) + " not above the support: " + level.getBlockState(p) + " on " + level.getBlockState(p.below()));
                double gone = Math.hypot(p.getX() + 0.5 - from.x, p.getZ() + 0.5 - from.z) - here;
                h.assertTrue(gone >= 8 * Math.cos(Math.PI / 4) - 1, String.format(Locale.ROOT, "%s: place closer to the epicenter - further to %.1f", type.toShortString(), gone));
            }
            mob.discard();
        }
        h.succeed();
    }

    /**
     * A 0.1 kt burst high above ground west of the platform (visible above the barrier wall), scaled to
     * {@link #PSI} at the mobs. Cows, a husk, a villager and a parrot see the flash (light per entity, as in
     * {@code PulseJob}) and all panic; the wave extends panic. Within 10 s each moves east, away from the
     * epicenter; when the duration ends, all escape targets are cleared. Friendly mobs never flee: a sitting
     * tamed wolf and a horse with its rider. A cow in a 1×1 glass pen panics but searches less often
     * (when trapped, no more than {@link #PENNED_SEARCHES} times during all of panic).
     */
    @GameTest(template = "range", timeoutTicks = 1400, batch = "nuke_panic", skyAccess = true)
    public static void mobsFleeTheBlastAndCalmDown(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        List<Mob> mobs = new ArrayList<>();
        mobs.add(h.spawn(EntityType.COW, new BlockPos(30, 12, 26)));
        mobs.add(h.spawn(EntityType.COW, new BlockPos(30, 12, 32)));
        mobs.add(h.spawn(EntityType.COW, new BlockPos(30, 12, 38)));
        mobs.add(h.spawn(EntityType.HUSK, new BlockPos(26, 12, 32)));
        mobs.add(h.spawn(EntityType.VILLAGER, new BlockPos(34, 12, 30)));
        // flyer: his navigation place is the solid cell itself on which to land
        mobs.add(h.spawn(EntityType.PARROT, new BlockPos(28, 12, 36)));
        // theirs: a tame wolf sits on orders, a horse has an owner
        UUID owner = UUID.randomUUID();
        Wolf wolf = h.spawn(EntityType.WOLF, new BlockPos(22, 12, 20));
        wolf.setTame(true, true);
        wolf.setOwnerUUID(owner);
        wolf.setOrderedToSit(true);
        wolf.setInSittingPose(true);
        Horse horse = h.spawn(EntityType.HORSE, new BlockPos(22, 12, 44));
        horse.setTamed(true);
        horse.setOwnerUUID(owner);
        List<Mob> pets = List.of(wolf, horse);
        // locked: two blocks of glass around the cage (in the open air - the wave scares her)
        BlockPos pen = new BlockPos(42, 12, 44);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = 0; dy < 2; dy++) h.setBlock(pen.offset(dx, dy, dz), Blocks.GLASS);
            }
        }
        Mob penned = h.spawn(EntityType.COW, pen);
        List<Mob> all = new ArrayList<>(mobs);
        all.addAll(pets);
        all.add(penned);
        for (Mob m : all) m.setPersistenceRequired();
        BlockPos g = h.absolutePos(NuclearGameTests.CENTER.west(60));
        Vec3 burst = new Vec3(g.getX() + 0.5, g.getY() + 120, g.getZ() + 0.5);
        Vec3 probe = h.absoluteVec(new Vec3(30.5, 12, 32.5));
        // scale at which mobs PSI: further in meters - less pressure
        float lo = 0.001f, hi = 1;
        for (int i = 0; i < 40; i++) {
            float mid = (float) Math.sqrt(lo * hi);
            if (make(level, burst, g.getY(), mid, 0).psi(probe) > PSI) hi = mid;
            else lo = mid;
        }
        Detonation d = make(level, burst, g.getY(), (float) Math.sqrt(lo * hi), NuclearGameTests.IDS.incrementAndGet());
        for (Mob m : mobs) {
            double psi = d.psi(m.position());
            h.assertTrue(psi > 0.3 && psi < 0.7, String.format(Locale.ROOT, "mob's blood pressure %.2f psi", psi));
        }
        // flash: light on each one, like an impulse
        for (Mob m : all) NuclearWarhead.expose(level, d, m, null);
        for (Mob m : mobs) h.assertTrue(MobPanic.panicking(m), m.getType().toShortString() + " not afraid of the flash");
        h.assertTrue(MobPanic.panicking(penned), "the cow in the pen was not afraid of the flash");
        h.assertTrue(all.stream().allMatch(Mob::isAlive), "the outbreak killed");
        h.onEachTick(() -> {
            for (Mob m : pets) h.assertFalse(MobPanic.fleeing(m), m.getType().toShortString() + " runs with the owner");
        });
        double[] start = new double[mobs.size()];
        for (int i = 0; i < mobs.size(); i++) start[i] = away(mobs.get(i), burst);
        NuclearEvents.get(level).add(d);
        long t0 = level.getGameTime();
        h.startSequence()
                .thenExecuteAfter(200, () -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mobs.size(); i++) sb.append(String.format(Locale.ROOT, " %s +%.1f", mobs.get(i).getType().toShortString(), away(mobs.get(i), burst) - start[i]));
                    Incoming.LOG.info("GameTest panic: in 10 s we left the epicenter: {}", sb);
                    for (int i = 0; i < mobs.size(); i++) {
                        Mob m = mobs.get(i);
                        double gone = away(m, burst) - start[i];
                        h.assertTrue(gone >= 4, String.format(Locale.ROOT, "%s left the epicenter on block %.1f", m.getType().toShortString(), gone));
                        h.assertTrue(MobPanic.panicking(m), m.getType().toShortString() + " has already calmed down after 10 s");
                    }
                    h.assertTrue(MobPanic.panicking(penned), "the cow in the pen has already calmed down after 10 s");
                })
                .thenWaitUntil(() -> {
                    for (Mob m : all) {
                        h.assertFalse(MobPanic.panicking(m), m.getType().toShortString() + " still panicking through " + (level.getGameTime() - t0) + " tick");
                        h.assertFalse(MobPanic.fleeing(m), m.getType().toShortString() + " still running");
                    }
                })
                .thenExecute(() -> {
                    // the wave arrived and prolonged the panic: they calmed down not before its arrival and the wave’s deadline
                    long calm = level.getGameTime() - t0;
                    Incoming.LOG.info("GameTest panic: calmed down via {} tick", calm);
                    h.assertTrue(calm >= MobPanic.WAVE, "calmed down after " + calm + " tick - ahead of the wave deadline");
                    int searches = MobPanic.searches(penned);
                    Incoming.LOG.info("GameTest panic: the cow in the pen looked for the path {} times per {} tick", searches, calm);
                    h.assertTrue(searches >= 2 && searches <= PENNED_SEARCHES, "the cow in the corral was looking for a way " + searches + " times");
                    for (Mob m : all) m.discard();
                    for (BlockPos p : BlockPos.betweenClosed(pen.offset(-1, 0, -1), pen.offset(1, 1, 1))) h.setBlock(p, Blocks.AIR);
                    NuclearStrikes.clear(level);
                })
                .thenSucceed();
    }

    private static Detonation make(ServerLevel level, Vec3 burst, double groundY, float scale, int id) {
        return new Detonation(id, burst, groundY, 0.1, false, level.getGameTime(), 0, 0, 20_000, 7, scale, false,
                Detonation.OLD_WAVE);
    }

    /** How far is the mob horizontally farther from the epicenter? */
    private static double away(Mob m, Vec3 burst) {
        return Math.hypot(m.getX() - burst.x, m.getZ() - burst.z);
    }
}
