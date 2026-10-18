package ua.zentix.incoming.nuclear;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.model.BurnInjury;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.PromptRadiationModel;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.nuclear.model.Yield;
import ua.zentix.incoming.nuclear.radiation.FlashBurns;
import ua.zentix.incoming.nuclear.radiation.FlashEyes;
import ua.zentix.incoming.nuclear.radiation.RadiationTicker;
import ua.zentix.incoming.nuclear.radiation.Shielding;
import ua.zentix.incoming.nuclear.world.NuclearWorld;
import ua.zentix.incoming.nuclear.world.ThermalShadow;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.util.Terrain;

import java.util.UUID;

/**
 * Ядерная боеголовка: подрыв (DESIGN-nuke §0, §4). Сервер записывает событие и шлёт его всем в измерении
 * одним пакетом. Всё остальное идёт по тикам под бюджетом ({@link NuclearWorld}): световой импульс и проникающая
 * радиация по сущностям (ближние первыми), ударная волна, разрушения и воронка.
 */
public final class NuclearWarhead {
    /**
     * Сценарий клиента ({@code tools/client_scenario.sh}, сервер — в той же JVM): ветер и сид подрыва — по его номеру, а не
     * из {@code level.random}, чтобы кадры пелены и гриба сравнивались от прогона к прогону. В игре — как было.
     */
    private static final boolean SCENARIO = System.getProperty("incoming.scenario") != null;
    /** Докуда от глаза (блоки) рельеф между ним и шаром проверяется по карте высот плотно, и с каким шагом. */
    private static final double NEAR_TERRAIN = 512, TERRAIN_STEP = 8;
    private static final long SCENARIO_SEED = 0x5CE7A510L;

    private NuclearWarhead() {}

    /**
     * @param target   точка прицеливания (земля под эпицентром берётся по карте высот)
     * @param airBurst воздушный подрыв на оптимальной высоте; иначе — наземный
     */
    public static Detonation detonate(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, @Nullable UUID owner) {
        return detonate(level, target, yieldKt, airBurst, owner, IncomingConfig.SERVER.nukeEffectsScale.get().floatValue());
    }

    /** @param scale масштаб радиусов (1 — как в жизни); обычно из настройки effects_scale, GameTest задаёт свой */
    public static Detonation detonate(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, @Nullable UUID owner, float scale) {
        long started = System.nanoTime();
        // предел мощности — и для ударов из старых сохранений и команд других модов
        yieldKt = Math.min(yieldKt, ua.zentix.incoming.strike.Loadout.Nuke.MAX_YIELD);
        NuclearEvents events = NuclearEvents.get(level);
        // готовились к этому подрыву — его место, земля и видимость те же, что у руин заранее
        Detonation g = NuclearWorld.get(level).planned(geometry(level, target, yieldKt, airBurst, scale));
        int id = events.nextId();
        RandomSource random = SCENARIO ? RandomSource.create(SCENARIO_SEED + id) : level.random;
        Detonation d = new Detonation(id, g.burst(), g.groundY(), yieldKt, g.surface(),
                level.getGameTime(), (float) (random.nextDouble() * Math.PI * 2), 5 + random.nextFloat() * 10,
                g.visibility(), random.nextLong(), scale, IncomingConfig.SERVER.nukeFallout.get(),
                IncomingConfig.SERVER.nukeWaveSpeed.get().floatValue(), level.getDayTime(),
                IncomingConfig.SERVER.nukeFires.get() && IncomingConfig.SERVER.nukeMaxFires.get() > 0);
        events.add(d);
        PacketDistributor.sendToPlayersInDimension(level, new S2C.NukeDetonation(d, (float) Blackouts.nukeRadius(d)));

        NuclearWorld.get(level).onDetonation(level, d, owner);
        Blackouts.nuke(level, d);
        Incoming.LOG.info("Nuclear detonation #{}: {} kt, {}, {} {} {}, scale {}, {} ms", d.id(), Math.round(yieldKt), d.surface() ? "ground burst" : "air burst",
                Mth.floor(d.burst().x), Mth.floor(d.burst().y), Mth.floor(d.burst().z), scale, (System.nanoTime() - started) / 1_000_000);
        return d;
    }

    /**
     * Место подрыва без номера, времени и случайных деталей: точка подрыва, земля под ней, наземный ли, видимость.
     * По нему же строятся руины заранее ({@link ua.zentix.incoming.nuclear.world.NuclearPrep}) — они совпадут с подрывом.
     */
    public static Detonation geometry(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, float scale) {
        Terrain.Surface under = ground(level, Mth.floor(target.x), Mth.floor(target.z));
        double ground = under != null ? Math.min(under.y(), target.y) : target.y;
        if (ground <= level.getMinBuildHeight()) ground = target.y;
        double hob = airBurst ? Yield.optimalBurstHeight(yieldKt) * scale : 0;
        boolean surface = hob / scale < FireballModel.maxRadius(yieldKt, true);
        float visibility = (float) (level.isThundering() ? ThermalModel.VISIBILITY_THUNDER : level.isRaining() ? ThermalModel.VISIBILITY_RAIN : ThermalModel.VISIBILITY_CLEAR);
        return new Detonation(-1, new Vec3(target.x, ground + hob, target.z), ground, yieldKt, surface, 0, 0, 0, visibility, 0, scale, false,
                IncomingConfig.SERVER.nukeWaveSpeed.get().floatValue());
    }

    /** Точка на поверхности над целью (удар «по поверхности»: цель — место на карте). */
    public static Vec3 surfaceAt(ServerLevel level, Vec3 at) {
        BlockPos p = BlockPos.containing(at);
        Terrain.Surface under = ground(level, p.getX(), p.getZ());
        return under == null ? at : new Vec3(at.x, under.y(), at.z);
    }

    /** Земля в колонке по готовому чанку; потолок Незера — не земля; чанк не готов — null (точка остаётся целью). */
    private static Terrain.@Nullable Surface ground(ServerLevel level, int x, int z) {
        Terrain.Surface s = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z, Terrain.Allowed.CHUNK);
        return s.source() == Terrain.Source.CHUNK ? s : null;
    }

    /**
     * Дальше этого (блоки) ни свет, ни проникающая радиация сущностей не трогают: ожоги 1-й степени у самых
     * чувствительных или 50 бэр. Ожог сетчатки достаёт дальше — игроков до него {@link PulseJob} добирает сам
     * ({@link FlashEyes#reach}).
     */
    public static double exposureRange(Detonation d) {
        double burnRange = d.blocks(ThermalModel.rangeForFluence(BurnInjury.lowest(d.yieldKt()), d.yieldKt(), d.surface(), d.visibility()));
        double radRange = d.blocks(radiusForDose(d.yieldKt(), 50));
        return Math.max(burnRange, radRange);
    }

    /**
     * Свет и проникающая радиация по одной сущности в пределах {@link #exposureRange} (её отбирает вызывающий).
     * Идёт не в тике подрыва, а под бюджетом ({@link NuclearWorld}).
     *
     * @return задело ли (жива и не в творческом режиме)
     */
    public static boolean expose(ServerLevel level, Detonation d, LivingEntity living, @Nullable Entity owner) {
        if (!living.isAlive()) return false;
        // творческий режим и наблюдатели: ни ожогов, ни дозы (как и у урона)
        if (living instanceof Player p && (p.isCreative() || p.isSpectator())) return false;
        Vec3 eye = living.getEyePosition();
        if (sees(level, d, living)) {
            if (living instanceof ServerPlayer player) FlashEyes.flash(level, d, player);
            FlashBurns.burn(level, d, living, d.fluence(eye), owner);
            // видел вспышку — бежит прочь
            MobPanic.flash(d, living, level.getGameTime());
        }
        double rem = PromptRadiationModel.doseRem(Math.max(1, d.metres(eye.distanceTo(d.burst()))), d.yieldKt());
        // без защиты меньше бэра — лучей защиты не нужно (игроки до дальности ожога сетчатки — за десятки километров)
        if (rem < 1) return true;
        rem *= Shielding.prompt(level, eye, d.burst());
        if (rem < 1) return true;
        float gy = (float) (rem / PromptRadiationModel.REM_PER_GY);
        if (living instanceof ServerPlayer player) {
            if (IncomingConfig.SERVER.nukeRadiation.get()) RadiationTicker.addDose(player, gy);
        } else if (IncomingConfig.SERVER.nukeMobRadiation.get()) {
            // мобы болеют, как игроки (нежить и стойки брони не болеют)
            if (RadiationTicker.affectsMob(living)) RadiationTicker.addDose(living, gy);
        } else if (rem >= 1000) {
            living.hurt(ModDamageTypes.source(level, ModDamageTypes.RADIATION, null, owner), Float.MAX_VALUE);
        } else if (rem >= 400) {
            living.hurt(ModDamageTypes.source(level, ModDamageTypes.RADIATION, null, owner), living.getMaxHealth() * 0.5f);
        }
        return true;
    }

    /**
     * Видит ли сущность огненный шар: рядом — честный луч по блокам (стены, крыша), дальше — по карте высот
     * (холмы, дома): до {@link #NEAR_TERRAIN} — плотно, за ним — редкими точками, чтобы не гнать луч на километры.
     */
    public static boolean sees(Level level, Detonation d, LivingEntity e) {
        Vec3 eye = e.getEyePosition();
        Vec3 dir = d.burst().subtract(eye);
        double len = dir.length();
        Vec3 near = eye.add(dir.scale(Math.min(1, 48 / Math.max(len, 1e-3))));
        // луч по блокам — только по загруженным чанкам: сервер не должен грузить мир ради проверки
        if (Terrain.readyAlong(level, eye, near) && level.clip(new ClipContext(eye, near, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, e)).getType() != HitResult.Type.MISS) return false;
        // редкие точки ThermalShadow у дальнего глаза проходят мимо рельефа рядом (хребет в сотне метров при шаре за
        // 30 км): от луча до NEAR_TERRAIN — каждые TERRAIN_STEP блоков по карте высот, пока чанки готовы
        Vec3 until = Terrain.readyUntil(level, near, eye.add(dir.scale(Math.min(1, NEAR_TERRAIN / Math.max(len, 1e-3)))));
        Vec3 span = until.subtract(near);
        for (double s = TERRAIN_STEP, n = span.length(); s < n; s += TERRAIN_STEP) {
            Vec3 p = near.add(span.scale(s / n));
            if (Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, Mth.floor(p.x), Mth.floor(p.z)) > p.y + 0.5) return false;
        }
        return ThermalShadow.visible(level, d.burst(), near);
    }

    /** Дальность, где доза проникающей радиации падает до {@code rem}, м (бисекция по монотонной модели). */
    private static double radiusForDose(double yieldKt, double rem) {
        double lo = 1, hi = 100_000;
        for (int i = 0; i < 60; i++) {
            double m = Math.sqrt(lo * hi);
            if (PromptRadiationModel.doseRem(m, yieldKt) > rem) lo = m;
            else hi = m;
        }
        return lo;
    }
}
