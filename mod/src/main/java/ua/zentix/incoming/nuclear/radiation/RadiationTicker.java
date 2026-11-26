package ua.zentix.incoming.nuclear.radiation;

import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.model.FalloutModel;
import ua.zentix.incoming.nuclear.model.SicknessModel;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.registry.ModEffects;
import ua.zentix.incoming.nuclear.NukeClock;
import ua.zentix.incoming.util.WorldClock;

import java.util.List;

/**
 * Radiation (DESIGN-nuke §4). Players once a second: local fallout dose rate with wall and roof shielding
 * ({@link Shielding#fallout}) and worn shielding ({@link WornShielding}), black-rain contamination (washed off by water),
 * accumulated dose and sickness stages
 * from {@link SicknessModel}: death depends on dose and individual randomness ({@link #fate}). Mobs (except undead)
 * have the same dose and sickness without scanning thousands each second: {@code MobFallout} adds fallout doses in
 * batches under the nuclear work budget, while sickness ticks on the mob itself via {@link RadiationSicknessEffect}
 * throughout its course.
 */
public final class RadiationTicker {
    /** 1 R ≈ 0.01 Gy in the body. */
    public static final double GY_PER_R = 0.01;
    /** A real second in hours. */
    private static final double REAL_SECOND_HOURS = 1 / 3600.0;
    private static final ResourceLocation HEALTH_PENALTY = Incoming.id("radiation_sickness");

    private RadiationTicker() {}

    public static RadiationDose dose(LivingEntity e) {
        // no dose, no attachment: getData would attach one to every mob touched by the flash
        return e.getExistingData(ModAttachments.RADIATION).orElse(RadiationDose.NONE);
    }

    /** Whether a mob can get radiation sickness (with {@code mob_radiation} enabled): mobs except undead; not armor stands. */
    public static boolean affectsMob(LivingEntity e) {
        return e instanceof Mob && !e.getType().is(EntityTypeTags.UNDEAD);
    }

    /** Dose from detonation {@code d} now, Gy: prompt burst radiation. A mob with dose from 1 Gy gets sick (effect drives the course). */
    public static void addDose(LivingEntity e, float gy, Detonation d) {
        addDose(e, gy, d, 0);
    }

    /** Dose now with no detonation holding the body's clock, Gy (tests, a body dosed outside any show). */
    public static void addDose(LivingEntity e, float gy) {
        addDose(e, gy, null, 0);
    }

    /**
     * Dose from detonation {@code d}, Gy, received {@code ago} body-clock ticks before now ({@link BodyClock}): a body loaded
     * after the detonation received its dose then, so its sickness stages run from then ({@code LateExposure}). Sickness runs
     * on the body's clock: real time while a detonation that acted on it is in its show, then the world's day (a vanilla day's
     * hour is 50 real seconds, a day-length mod's hour an hour; a night's sleep is a night of illness), only while the body
     * is in the world.
     */
    public static void addDose(LivingEntity e, float gy, @org.jetbrains.annotations.Nullable Detonation d, double ago) {
        if (d != null) BodyClock.cause(e, d);
        RadiationDose r = dose(e);
        float total = r.doseGy() + gy;
        long now = (long) Math.floor(BodyClock.now(e));
        set(e, new RadiationDose(total, r.contamination(), r.isExposed() || total < 1 ? r.exposedAt() : now - (long) Math.max(0, Math.ceil(ago)), r.rate()));
        if (!(e instanceof Player) && total >= 1 && !e.hasEffect(ModEffects.RADIATION_SICKNESS)) {
            e.addEffect(new MobEffectInstance(ModEffects.RADIATION_SICKNESS, MobEffectInstance.INFINITE_DURATION, 0, true, false, false));
        }
    }

    public static void clear(ServerPlayer p) {
        set(p, RadiationDose.NONE);
        AttributeInstance health = p.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) health.removeModifier(HEALTH_PENALTY);
        p.removeEffect(ModEffects.RADIATION_SICKNESS);
    }

    /** Resend full counter reading: login, respawn, dimension change. */
    public static void sync(ServerPlayer p) {
        RadiationDose r = dose(p);
        PacketDistributor.sendToPlayer(p, new S2C.Radiation(r.doseGy(), r.rate(), r.contamination(), stage(p, r).ordinal()));
    }

    private static void set(LivingEntity e, RadiationDose next) {
        RadiationDose prev = dose(e);
        e.setData(ModAttachments.RADIATION, next);
        if (e instanceof ServerPlayer p && (Math.abs(prev.doseGy() - next.doseGy()) > 0.005 || Math.abs(prev.rate() - next.rate()) > Math.max(0.01, prev.rate() * 0.05)
                || Math.abs(prev.contamination() - next.contamination()) > 0.05)) {
            PacketDistributor.sendToPlayer(p, new S2C.Radiation(next.doseGy(), next.rate(), next.contamination(), stage(p, next).ordinal()));
        }
    }

    public static void tick(ServerLevel level) {
        if (!IncomingConfig.SERVER.nukeRadiation.get() || level.getGameTime() % 20 != 0) return;
        List<Detonation> list = NuclearEvents.get(level).detonations();
        step(level, level.players(), list);
    }

    /** One radiation second of {@code players} under detonations {@code list}: the clocks step once for all of them. */
    static void step(ServerLevel level, List<? extends ServerPlayer> players, List<Detonation> list) {
        NuclearEvents events = NuclearEvents.get(level);
        // each detonation's strike clock now and a second ago (StrikeClock): the field at its own time, the dose over its own step
        Clocks c = new Clocks(list.size());
        for (int i = 0; i < c.now.length; i++) {
            Detonation d = list.get(i);
            c.now[i] = events.seconds(d, level);
            c.before[i] = events.stepFrom(d, c.now[i]);
            if (d.hasFallout() && (c.newest < 0 || c.now[i] < c.now[c.newest])) c.newest = i;
        }
        for (ServerPlayer p : players) {
            if (p.isSpectator()) continue;
            tick(level, p, list, c);
        }
    }

    /** Detonation clocks for one radiation second ({@link NukeClock}): seconds now and at the previous second. */
    private static final class Clocks {
        final double[] now, before;
        /** Youngest detonation with fallout; −1 — none. */
        int newest = -1;

        Clocks(int n) {
            now = new double[n];
            before = new double[n];
        }

        /** Dose hours of detonation {@code i}'s step at its decaying rate ({@link FalloutModel#decayHours}). */
        double doseHours(int i) {
            return FalloutModel.decayHours(before[i] / 3600, now[i] / 3600);
        }
    }

    private static void tick(ServerLevel level, ServerPlayer p, List<Detonation> list, Clocks c) {
        double field = 0, roentgens = 0;
        boolean blackRain = false;
        for (int i = 0; i < c.now.length; i++) {
            Detonation d = list.get(i);
            field += d.falloutRateAt(p.getX(), p.getZ(), c.now[i]);
            // over the step's own course: a long step (a sleep) counts nothing before the dust or rain reached the player
            double r = MobFallout.exposure(d, p.getX(), p.getZ(), c.before[i], c.now[i]);
            roentgens += r;
            boolean rain = d.blackRainAt(p.getX(), p.getZ(), c.now[i]);
            if (rain) blackRain = true;
            // a detonation whose fallout or rain reaches the body holds its clock during its show
            if (!p.isCreative() && (r >= MobFallout.BACKGROUND * c.doseHours(i) || rain)) BodyClock.cause(p, d);
        }
        RadiationDose r = dose(p);
        // what the body wears stops a share of new exposure: fallout and new rain dust, not the dust already on it
        double worn = roentgens > 0 || blackRain ? WornShielding.transmitted(p) : 1;
        // the dust on the body is the same fission products as the ground under it: it decays by the same law and clock
        double contamination = c.newest < 0 ? r.contamination()
                : decay(r.contamination(), c.now[c.newest] / 3600, (c.now[c.newest] - c.before[c.newest]) / 3600);
        // the rule also applies live: detonations saved before it was stored with them read as rain on
        if (blackRain && IncomingConfig.SERVER.nukeBlackRain.get() && Detonation.underOpenSky(level, p.getEyePosition())) {
            contamination = rained(contamination, field, worn);
        }
        if (p.isInWater()) contamination *= 0.8;
        if (contamination < 0.01) contamination = 0;
        double shield = field >= MobFallout.BACKGROUND ? Shielding.fallout(level, middle(p)) : 0;
        double rate = field * shield + contamination;
        double contaminationHours = c.newest < 0 ? REAL_SECOND_HOURS : c.doseHours(c.newest);
        // in creative mode the counter shows the field, but no dose accumulates and no sickness develops
        double gy = r.doseGy() + (p.isCreative() ? 0 : (roentgens * shield * worn + contamination * contaminationHours) * GY_PER_R);
        long exposed = r.isExposed() || gy < 1 ? r.exposedAt() : (long) Math.floor(BodyClock.now(p));
        RadiationDose next = new RadiationDose((float) gy, (float) contamination, exposed, (float) rate);
        set(p, next);
        sickness(level, p, next);
    }

    /**
     * Contamination after a second of black rain, R/h: rain contaminates instead of washing off, rising toward twice the field
     * rate; rain never lowers it. Under worn shielding only the share {@code transmitted} of the new rain dust reaches clothes
     * and skin: both the rise and its ceiling scale with it.
     */
    static double rained(double contamination, double field, double transmitted) {
        return Math.max(contamination, Math.min(contamination + field * 0.05 * transmitted, field * 2 * transmitted));
    }

    /**
     * Contamination dose rate after one more step of {@code stepHours}, R/h: Way–Wigner t^−1.2 decay like the fallout it came
     * from ({@link FalloutModel#doseRate}), {@code ageHours} after the newest detonation with fallout (est.: dust from an older
     * one decays a little slower).
     */
    static double decay(double contamination, double ageHours, double stepHours) {
        double before = Math.max(ageHours - stepHours, FalloutModel.MIN_DECAY_HOURS), after = Math.max(ageHours, FalloutModel.MIN_DECAY_HOURS);
        return contamination * Math.pow(before / after, FalloutModel.DECAY_EXP);
    }

    /** Body center: fallout shielding is evaluated there. */
    public static Vec3 middle(LivingEntity e) {
        return e.position().add(0, e.getBbHeight() * 0.5, 0);
    }

    private static SicknessModel.Stage stage(LivingEntity e, RadiationDose r) {
        if (!r.isExposed()) return SicknessModel.Stage.NONE;
        return SicknessModel.stage(r.doseGy(), hoursSinceExposure(e, r), SicknessModel.fated(r.doseGy(), fate(e, r)));
    }

    /**
     * Exposed subject randomness 0..1 ({@link SicknessModel#fated}): unique per subject and exposure, from UUID and the time
     * dose crossed 1 Gy. Does not change as dose grows: only dose determines death.
     */
    static double fate(LivingEntity e, RadiationDose r) {
        long h = HashCommon.mix(e.getUUID().getMostSignificantBits() ^ HashCommon.mix(e.getUUID().getLeastSignificantBits() ^ r.exposedAt()));
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Body-clock hours since dose crossed 1 Gy ({@link BodyClock}, as last stepped: a login reads the state it left with). */
    public static double hoursSinceExposure(LivingEntity e, RadiationDose r) {
        return (BodyClock.of(e).clock() - r.exposedAt()) / WorldClock.HOUR;
    }

    /**
     * One second of mob sickness (from {@link RadiationSicknessEffect}). Nothing before prodrome, then the same stages
     * as a player; false — recovered, course ended, remove effect.
     */
    static boolean tickMob(ServerLevel level, LivingEntity e) {
        RadiationDose r = dose(e);
        if (!r.isExposed()) return false;
        BodyClock.step(e);
        if (hoursSinceExposure(e, r) < SicknessModel.onsetHours(r.doseGy())) return true;
        sickness(level, e, r);
        return stage(e, r) != SicknessModel.Stage.NONE;
    }

    /**
     * Radiation sickness stages: nausea and vomiting, latent period, manifest illness (weakness, hunger, reduced health), death;
     * at a cerebral dose, ataxia throughout.
     */
    private static void sickness(ServerLevel level, LivingEntity p, RadiationDose r) {
        SicknessModel.Stage stage = stage(p, r);
        AttributeInstance health = p.getAttribute(Attributes.MAX_HEALTH);
        double penalty = stage == SicknessModel.Stage.MANIFEST || stage == SicknessModel.Stage.FATAL ? SicknessModel.maxHealthPenalty(r.doseGy()) : 0;
        if (health != null) {
            health.removeModifier(HEALTH_PENALTY);
            if (penalty > 0) health.addTransientModifier(new AttributeModifier(HEALTH_PENALTY, -penalty, AttributeModifier.Operation.ADD_VALUE));
        }
        // cerebral form: ataxia for everyone from prodrome onset to death
        int ataxia = SicknessModel.ataxiaSlowness(r.doseGy());
        if (ataxia >= 0 && stage != SicknessModel.Stage.NONE) p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, ataxia, true, false, false));
        if (!(p instanceof ServerPlayer player)) {
            // mob sickness effect drives its own course (removed when tickMob returns false); nausea means nothing to a mob
            if (stage == SicknessModel.Stage.MANIFEST || stage == SicknessModel.Stage.FATAL) manifest(level, p, r, stage);
            return;
        }
        if (stage == SicknessModel.Stage.NONE) {
            player.removeEffect(ModEffects.RADIATION_SICKNESS);
            return;
        }
        player.addEffect(new MobEffectInstance(ModEffects.RADIATION_SICKNESS, 60, stage.ordinal() - 1, true, false, true));
        switch (stage) {
            case PRODROMAL -> {
                player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 100, 0, true, false, false));
                if (level.random.nextFloat() < 0.04f) vomit(level, player);
            }
            case MANIFEST, FATAL -> {
                int hunger = (int) Math.round(SicknessModel.hungerMultiplier(r.doseGy()) * 2) - 2;
                if (hunger >= 0) player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 60, hunger, true, false, false));
                manifest(level, player, r, stage);
                if (stage == SicknessModel.Stage.FATAL && level.random.nextFloat() < 0.05f) vomit(level, player);
            }
            default -> {}
        }
    }

    /** Manifest illness for everyone: weakness, and at lethal dose, damage every second. */
    private static void manifest(ServerLevel level, LivingEntity e, RadiationDose r, SicknessModel.Stage stage) {
        int weakness = SicknessModel.weaknessAmplifier(r.doseGy());
        if (weakness >= 0) e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, weakness, true, false, false));
        if (stage == SicknessModel.Stage.FATAL) e.hurt(ModDamageTypes.source(level, ModDamageTypes.RADIATION, null, null), Math.max(1, r.doseGy() / 5));
    }

    private static void vomit(ServerLevel level, ServerPlayer p) {
        p.getFoodData().setFoodLevel(Math.max(0, p.getFoodData().getFoodLevel() - 3));
        p.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 200, 0, true, false, false));
        level.playSound(null, p.blockPosition(), SoundEvents.PLAYER_BURP, SoundSource.PLAYERS, 1.0f, 0.6f);
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.ROTTEN_FLESH)),
                p.getX(), p.getEyeY() - 0.2, p.getZ(), 12, 0.1, 0.1, 0.1, 0.12);
    }

    /** No natural health regeneration during manifest illness at dose ≥ 2 Gy. */
    public static void onHeal(LivingHealEvent e) {
        LivingEntity p = e.getEntity();
        if (p.level().isClientSide()) return;
        RadiationDose r = dose(p);
        SicknessModel.Stage stage = stage(p, r);
        if ((stage == SicknessModel.Stage.MANIFEST || stage == SicknessModel.Stage.FATAL) && SicknessModel.noRegeneration(r.doseGy())) e.setCanceled(true);
    }
}
