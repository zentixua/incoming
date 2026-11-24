package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.api.ClaimQuery;
import ua.zentix.incoming.api.StrikeRoundEvent;
import ua.zentix.incoming.entity.DebrisEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.launcher.LauncherOrders;
import ua.zentix.incoming.launcher.Mission;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BurnInjury;
import ua.zentix.incoming.nuclear.model.Fragility;
import ua.zentix.incoming.nuclear.radiation.BurnsEffect;
import ua.zentix.incoming.nuclear.radiation.FlashBurns;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.registry.ModEffects;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.StrikeOwner;
import ua.zentix.incoming.strike.StrikeRules;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Owner identity (roadmap B3): live combat credit by the owner's UUID from the player list, in any level; persistent
 * attribution on rounds, projectiles, debris and burns, whatever the owner's online state; claim queries attributed as
 * before. The owner "elsewhere" is a real {@link ServerPlayer} in the Nether, in the player list, not in any level's
 * entity list (never ticked). Each test has its own batch.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OwnerGameTests {
    private static final BlockPos CENTER = new BlockPos(32, 11, 32);

    private OwnerGameTests() {}

    /**
     * A Shahed-136 burst whose owner is online in the Nether kills cows beside it: every dead cow credits the owner (the
     * killer vanilla credits, also when the fuel fire gives the last blow), and the owner's kill statistic counts each.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "owner_blast", skyAccess = true)
    public static void blastCreditsOwnerInAnotherLevel(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer owner = elsewhere(h, UUID.randomUUID(), "owner_blast");
        List<Cow> cows = new ArrayList<>();
        for (int[] o : new int[][]{{3, 0}, {-3, 0}, {0, 3}, {0, -3}}) cows.add(still(h, EntityType.COW, CENTER.offset(o[0], 1, o[1])));
        Map<Entity, LivingEntity> killers = new HashMap<>();
        listen(h, LivingDeathEvent.class, e -> {
            if (cows.contains(e.getEntity())) killers.put(e.getEntity(), e.getEntity().getKillCredit());
        });
        Warheads.detonate(level, WeaponType.DRONE, surface(h, CENTER), null, owner.getUUID());
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).idle(), "the strike still runs");
            h.assertFalse(killers.isEmpty(), "no cow died beside a Shahed-136 burst");
            for (LivingEntity k : killers.values()) h.assertTrue(k == owner, "a dead cow credits " + k + ", not the owner");
            h.assertValueEqual(owner.getStats().getValue(Stats.ENTITY_KILLED.get(EntityType.COW)), killers.size(), "cows killed, owner's statistic");
        });
    }

    /**
     * Debris keeps its strike's owner in its data and, falling on a chicken, names the owner online in the Nether: the
     * debris is the direct cause, the owner the attacker, credited with the kill. Debris without an owner reads back without
     * one.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "owner_debris", skyAccess = true)
    public static void debrisCreditsOwnerAfterReload(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer owner = elsewhere(h, UUID.randomUUID(), "owner_debris");
        Chicken chicken = still(h, EntityType.CHICKEN, CENTER.above());
        chicken.setHealth(1);
        Map<Entity, DamageSource> deaths = deaths(h, List.of(chicken));
        DebrisEntity thrown = DebrisEntity.create(level, chicken.position().add(0, 4, 0), Blocks.COBBLESTONE.defaultBlockState(), false, true,
                new Vec3(0, -0.8, 0)).under(StrikeRules.live(), owner.getUUID());
        DebrisEntity copy = reload(level, thrown);
        h.assertValueEqual(copy.owner(), owner.getUUID(), "debris owner read back");
        h.assertTrue(reload(level, DebrisEntity.create(level, chicken.position(), Blocks.COBBLESTONE.defaultBlockState(), false, true, Vec3.ZERO)
                .under(StrikeRules.live(), null)).owner() == null, "debris without an owner read back with one");
        level.addFreshEntity(copy);
        h.succeedWhen(() -> {
            DamageSource s = deaths.get(chicken);
            h.assertTrue(s != null, "the debris did not kill the chicken");
            h.assertTrue(s.is(ModDamageTypes.DEBRIS) && s.getDirectEntity() == copy && s.getEntity() == owner,
                    "the debris death does not name the debris and the owner: " + describe(s));
            h.assertValueEqual(owner.getStats().getValue(Stats.ENTITY_KILLED.get(EntityType.CHICKEN)), 1, "chicken killed, owner's statistic");
        });
    }

    /**
     * Burn shock has one cause, the flash that brought it: its owner is kept with the body while offline, survives a save,
     * and a later unowned flash does not replace it. When the shock kills, the owner, now online in the Nether, is named by
     * {@code player_burn_shock} and credited with the kill.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "owner_burn_shock", skyAccess = true)
    public static void burnShockCreditsTheFlashOwner(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID id = UUID.randomUUID();
        double yield = 15;
        Detonation d = NuclearGameTests.detonation(h, CENTER, 0, yield, 1f, false);
        double third = BurnInjury.median(3, yield) * Math.exp(BurnInjury.BETA * Fragility.TAIL) * 1.01;
        Cow cow = still(h, EntityType.COW, CENTER.offset(-6, 1, -6));
        FlashBurns.burn(level, d, cow, third, id);
        h.assertTrue(cow.getEffect(ModEffects.BURNS) != null && cow.getEffect(ModEffects.BURNS).getAmplifier() == BurnsEffect.SHOCK,
                "the cow is not in burn shock");
        h.assertValueEqual(cow.getExistingData(ModAttachments.BURN_SHOCK_OWNER).orElse(null), id, "shock cause while its owner is offline");
        FlashBurns.burn(level, d, cow, third, null);
        h.assertValueEqual(cow.getExistingData(ModAttachments.BURN_SHOCK_OWNER).orElse(null), id, "shock cause after a second, unowned flash");
        CompoundTag saved = new CompoundTag();
        h.assertTrue(cow.save(saved), "the cow did not save");
        Entity back = EntityType.create(saved, level).orElseThrow();
        h.assertValueEqual(back.getExistingData(ModAttachments.BURN_SHOCK_OWNER).orElse(null), id, "shock cause after a save");

        ServerPlayer owner = elsewhere(h, id, "owner_burns");
        Map<Entity, DamageSource> deaths = deaths(h, List.of(cow));
        cow.getEffect(ModEffects.BURNS).duration = 2;
        h.succeedWhen(() -> {
            DamageSource s = deaths.get(cow);
            h.assertTrue(s != null, "the shock did not kill the cow");
            h.assertTrue(s.is(ModDamageTypes.PLAYER_BURN_SHOCK) && s.getEntity() == owner, "the shock death does not name the owner: " + describe(s));
            h.assertValueEqual(owner.getStats().getValue(Stats.ENTITY_KILLED.get(EntityType.COW)), 1, "cow killed, owner's statistic");
        });
    }

    /**
     * Revenge, not credit, stays in the level: damage from an owner in another level leaves a zombie without a revenge
     * target but with the owner as its killer to be; the same damage from an owner in its own level makes the owner its
     * revenge target, as vanilla does.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "owner_revenge", skyAccess = true)
    public static void ownerElsewhereIsCreditedNotPursued(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer far = elsewhere(h, UUID.randomUUID(), "owner_far");
        ServerPlayer near = ProgressGameTests.Credited.online(h, "owner_near", 0, CENTER.offset(10, 1, 10));
        Zombie a = still(h, EntityType.ZOMBIE, CENTER.offset(-4, 1, 0)), b = still(h, EntityType.ZOMBIE, CENTER.offset(4, 1, 0));
        StrikeOwner.hurt(a, ModDamageTypes.source(level, ModDamageTypes.STRIKE, null, far), 1);
        StrikeOwner.hurt(b, ModDamageTypes.source(level, ModDamageTypes.STRIKE, null, near), 1);
        h.assertTrue(a.getLastHurtByMob() == null, "a zombie took the owner in the Nether as its revenge target");
        h.assertTrue(a.getKillCredit() == far, "a zombie hurt by the owner in the Nether does not credit them");
        h.assertTrue(b.getLastHurtByMob() == near && b.getKillCredit() == near, "a zombie hurt by an owner in its level: vanilla revenge changed");
        h.succeed();
    }

    /**
     * An owner who is offline: their fixed launcher's round is released and, intercepted, ended under their UUID; the
     * flying Shahed-136 read back from its save keeps the owner and the round; its wreck debris carries the owner. A burst
     * of theirs names no attacker, and when they log in afterwards they have no kill credited.
     */
    @GameTest(template = "runway", timeoutTicks = 1200, batch = "owner_offline", skyAccess = true)
    public static void offlineOwnerKeepsAttributionWithoutCredit(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID offline = UUID.randomUUID();
        List<StrikeRoundEvent> rounds = new ArrayList<>();
        listen(h, StrikeRoundEvent.Released.class, e -> {
            if (offline.equals(e.getOwnerId())) rounds.add(e);
        });
        listen(h, StrikeRoundEvent.Ended.class, e -> {
            if (offline.equals(e.getOwnerId())) rounds.add(e);
        });
        StrikeGameTests.afterTest(h, () -> {
            for (StrikeProjectile p : List.copyOf(StrikeWorld.projectiles(level))) if (offline.equals(p.ownerId())) p.discard();
            VirtualFlights.get(level).clear(level, p -> offline.equals(p.ownerId()));
        });
        BlockPos pad = new BlockPos(16, 4, 8);
        FixedLauncherBlockEntity be = FixedLauncherGameTests.launcher(h, pad, offline);
        FixedLauncherGameTests.load(h, be, new ItemStack(ModItems.SHAHED.get(), 1));
        Vec3 aim = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 3, 1000)));
        be.setMission(new Mission(WeaponType.DRONE, 1, 0, new Target.Point(aim), aim, Waypoints.NONE));
        h.assertTrue(LauncherOrders.fire(level, be) == null, "the launcher refused");

        List<Cow> cows = new ArrayList<>();
        BlockPos field = new BlockPos(16, 4, 200);
        for (int[] o : new int[][]{{3, 0}, {-3, 0}, {0, 3}}) cows.add(still(h, EntityType.COW, field.offset(o[0], 0, o[1])));
        List<DamageSource> struck = new ArrayList<>();
        listen(h, LivingDamageEvent.Post.class, e -> {
            if (cows.contains(e.getEntity())) struck.add(e.getSource());
        });
        Warheads.detonate(level, WeaponType.DRONE, surface(h, field.below()), null, offline);

        boolean[] intercepted = {false};
        List<DebrisEntity> wreck = new ArrayList<>();
        h.onEachTick(() -> {
            if (intercepted[0]) return;
            StrikeProjectile p = StrikeWorld.projectiles(level).stream().filter(s -> offline.equals(s.ownerId()) && s.round() != null).findFirst().orElse(null);
            if (p == null || p.isVirtual() || p.flightPhase().onLauncher()) return;
            CompoundTag saved = new CompoundTag();
            h.assertTrue(p.save(saved), "the Shahed-136 did not save");
            StrikeProjectile copy = (StrikeProjectile) EntityType.create(saved, level).orElseThrow();
            h.assertValueEqual(copy.ownerId(), offline, "owner after a save");
            h.assertValueEqual(copy.round(), p.round(), "round after a save");
            Vec3 at = p.position();
            p.intercepted(level, "owner test", null, null);
            wreck.addAll(level.getEntitiesOfClass(DebrisEntity.class, new AABB(at, at).inflate(8)));
            intercepted[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(intercepted[0], "the Shahed-136 never flew");
            h.assertTrue(rounds.stream().anyMatch(e -> e instanceof StrikeRoundEvent.Released), "no Released under the offline owner");
            h.assertTrue(rounds.stream().anyMatch(e -> e instanceof StrikeRoundEvent.Ended end && end.getOutcome() == StrikeRoundEvent.Outcome.INTERCEPTED),
                    "no Ended(INTERCEPTED) under the offline owner: " + rounds);
            h.assertFalse(wreck.isEmpty(), "no wreck debris");
            for (DebrisEntity piece : wreck) h.assertValueEqual(piece.owner(), offline, "wreck debris owner");
            h.assertTrue(StrikeWorld.get(level).idle(), "the burst still runs");
            h.assertFalse(struck.isEmpty(), "the burst hurt no cow");
            for (DamageSource s : struck) h.assertTrue(s.getEntity() == null, "a burst of an offline owner named an attacker: " + describe(s));
            ServerPlayer back = ProgressGameTests.Credited.online(h, offline, "owner_offline", 0, new BlockPos(16, 4, 20));
            h.assertValueEqual(back.getStats().getValue(Stats.ENTITY_KILLED.get(EntityType.COW)), 0, "kills credited to the offline owner");
        });
    }

    /**
     * Claim queries keep B2's attribution with the owner online elsewhere: a strike's queries name its projectile as the
     * direct source and no indirect (owner) source, the same as before B3; claim mods decide by their policy, not by owner.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "owner_claims", skyAccess = true)
    public static void claimQueriesKeepTheirAttribution(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer owner = elsewhere(h, UUID.randomUUID(), "owner_claims");
        Entity drone = ModEntities.DRONE.get().create(level);
        Vec3 at = surface(h, CENTER);
        drone.moveTo(at);
        drone.setDeltaMovement(0, -1, 0);
        List<ClaimQuery> queries = new ArrayList<>();
        listen(h, ExplosionEvent.Detonate.class, e -> {
            if (e.getExplosion() instanceof ClaimQuery q && q.center().distanceTo(at) < 64) queries.add(q);
        });
        Warheads.detonate(level, WeaponType.DRONE, at, drone, owner.getUUID());
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).idle(), "the strike still runs");
            h.assertFalse(queries.isEmpty(), "the strike asked no claim question");
            for (ClaimQuery q : queries) {
                h.assertTrue(q.getDirectSourceEntity() == drone, "a claim question lost the projectile as its source");
                h.assertTrue(q.getIndirectSourceEntity() == null, "a claim question names the owner as its source");
            }
        });
    }

    // ---------------------------------------------------------------- fixtures

    /** A real player in the Nether, in the server's player list (not in a level: never ticked). */
    static ServerPlayer elsewhere(GameTestHelper h, UUID id, String name) {
        ServerLevel nether = h.getLevel().getServer().getLevel(Level.NETHER);
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), nether, new GameProfile(id, name), ClientInformation.createDefault());
        PermissionGameTests.online(h, p);
        p.moveTo(0.5, 100, 0.5);
        h.assertTrue(p.level() != h.getLevel(), "the owner is not in another level");
        return p;
    }

    /** Top face of the block {@code top}: where a burst on that ground is. */
    private static Vec3 surface(GameTestHelper h, BlockPos top) {
        return Vec3.atCenterOf(h.absolutePos(top)).add(0, 0.5, 0);
    }

    private static <T extends Mob> T still(GameTestHelper h, EntityType<T> type, BlockPos at) {
        T mob = h.spawn(type, at);
        mob.setNoAi(true);
        return mob;
    }

    /** Death sources of {@code watched}, by entity. */
    private static Map<Entity, DamageSource> deaths(GameTestHelper h, List<? extends LivingEntity> watched) {
        Map<Entity, DamageSource> out = new HashMap<>();
        listen(h, LivingDeathEvent.class, e -> {
            if (watched.contains(e.getEntity())) out.put(e.getEntity(), e.getSource());
        });
        return out;
    }

    /** Debris read back from its data (the type is not saved with chunks: debris lands within seconds). */
    private static DebrisEntity reload(ServerLevel level, DebrisEntity d) {
        CompoundTag saved = new CompoundTag();
        d.saveWithoutId(saved);
        DebrisEntity back = new DebrisEntity(ModEntities.DEBRIS.get(), level);
        back.load(saved);
        return back;
    }

    private static String describe(DamageSource s) {
        return s.getMsgId() + " direct=" + s.getDirectEntity() + " attacker=" + s.getEntity();
    }

    private static <T extends Event> void listen(GameTestHelper h, Class<T> type, Consumer<T> listener) {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, type, listener);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(listener));
    }
}
