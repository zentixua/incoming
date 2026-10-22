package ua.zentix.incoming.client.fx;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.FarBlasts;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.fx.particle.FxBudget;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.sound.BlastEchoes;
import ua.zentix.incoming.client.sound.BlastSounds;
import ua.zentix.incoming.client.sound.OwnSources;
import ua.zentix.incoming.net.S2C;
import net.minecraft.world.level.block.state.BlockState;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.util.Particles;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BunkerCover;
import ua.zentix.incoming.warhead.BunkerImpact;
import ua.zentix.incoming.warhead.BuriedBlast;
import ua.zentix.incoming.warhead.GroundMaterial;
import ua.zentix.incoming.warhead.Warheads;

/**
 * Что видят и слышат игроки при взрывах вблизи: вспышка по модели облака ({@link CloudModel}), твёрдое частицами —
 * осколки, искры, комья грунта, языки пламени, пока горит топливо; огненный шар, дым, пыль и облако конденсации — объём
 * сетки дыма (облако модели — источник сетки, его ведёт {@link FarBlasts}). Звук, слух и толчок тела приходят вместе
 * с фронтом ударной волны (17 блоков за тик). Бомба под землёй — прежняя картинка.
 */
public final class BlastEffects {
    private BlastEffects() {}

    /**
     * Пакет взрыва: ближе {@link FarBlasts#NEAR} — частицы и звук здесь; картинку дальше прорисовки и звук
     * дальше {@link FarBlasts#NEAR} ведёт {@link FarBlasts} (пакеты приходят до {@code far_range}).
     */
    public static void blast(S2C.Blast p) {
        // одно облако на взрыв: у объёма, у картинки вдали и у вспышки с частицами здесь
        CloudModel cloud = FarBlasts.add(p);
        if (bodyOnly(p.pos())) Effects.add(new Body(p.pos(), GroundMaterial.byId(p.material()), p.seed(), BlastSource.of(p)));
        else if (FarBlasts.near(p.pos())) {
            GroundMaterial mat = GroundMaterial.byId(p.material());
            if (cloud == null && p.kind() != S2C.Blast.BUNKER) cloud = FarBlasts.model(p);
            // осколки — только у боевой части оружия; взрыв без оружия в пакете (вторичные подрывы после ядерки, повтор
            // до этого поля) корпуса не имеет
            Vec3 axis = p.weapon() == S2C.Blast.NO_WEAPON ? null : p.axis();
            switch (p.kind()) {
                case S2C.Blast.MISSILE -> Effects.add(new Missile(p.pos(), mat, p.seed(), axis, cloud));
                case S2C.Blast.BUNKER -> Effects.add(new Bunker(p.pos(), mat, p.surfaceY(), p.seed(), cloud));
                case S2C.Blast.ROCKET -> Effects.add(new Drone(p.pos(), mat, p.seed(), axis, true, cloud));
                default -> Effects.add(new Drone(p.pos(), mat, p.seed(), axis, false, cloud));
            }
        }
    }

    /**
     * Зенитная ракета разорвалась в воздухе ({@link S2C.Intercept}): вспышка, клуб огня, чёрное облачко разрыва и осколки;
     * сбила — ещё и горящее топливо цели. Хлопок и лёгкая тряска — с фронтом. Дальше {@link FarBlasts#NEAR} — ничего: разрыв мал.
     */
    public static void intercept(S2C.Intercept p) {
        if (bodyOnly(p.pos())) Effects.add(new Body(p.pos(), GroundMaterial.STONE, p.seed(), BlastSource.air(Airburst.CHARGE_KG)));
        else if (FarBlasts.near(p.pos())) Effects.add(new Airburst(p.pos(), p.seed(), p.kill()));
    }

    /**
     * Камера далеко (камера снаряда), а тело игрока ближе {@link FarBlasts#NEAR}: картинку и звук ведёт {@link FarBlasts}
     * у камеры, а волна у самого игрока всё равно бьёт по ушам и толкает ({@link Body}).
     */
    private static boolean bodyOnly(Vec3 pos) {
        Player player = Minecraft.getInstance().player;
        return !FarBlasts.near(pos) && player != null && player.getEyePosition().distanceTo(pos) <= FarBlasts.NEAR;
    }

    /** Только фронт у тела игрока ({@link BlastHit}): без картинки, звука и эха — их ведёт дальняя модель у камеры. */
    static final class Body extends Timeline {
        Body(Vec3 pos, GroundMaterial mat, long seed, BlastSource source) {
            super(pos, mat, seed, source, false);
        }

        @Override
        boolean run(ClientLevel level, int t) {
            return t <= 60;
        }

        @Override
        void arrive(ClientLevel level, int band) {
        }
    }

    // ---------------------------------------------------------------- общее

    /** Ближе этого вспышка взрыва по модели — в полную силу, блоков: радиус шара продуктов детонации. */
    static double flashNear(CloudModel m) {
        return m.heBall;
    }

    /** Дальше этого вспышки нет, блоков: вуаль спадает как радиус шара / d и здесь слабее {@link Flash#FLOOR}. */
    static double flashRange(CloudModel m) {
        return flashNear(m) * FlashFalloff.PEAK / Flash.FLOOR;
    }

    /**
     * Фронт звука и ударной волны: срабатывает у слушателя (камера) один раз, когда до него дошёл, — звук; у тела
     * игрока — слух и наклон тела ({@link BlastHit}). Обычный взрыв ({@code source} не null) звучит по заряду
     * ({@link BlastSounds#arrive}), его эхо ищет склоны, пока фронт идёт ({@link BlastEchoes}).
     */
    abstract static class Timeline implements Effects.Effect {
        final Vec3 pos;
        final GroundMaterial mat;
        final RandomSource random;
        /** Зерно взрыва: по нему же звук выбирает вариант записи. */
        final long seed;
        /** Что взорвалось; null — у взрыва свои звуки ({@link #arrive}). */
        @Nullable
        final BlastSource source;
        @Nullable
        private final BlastEchoes echoes;
        private boolean arrived, hit, over;
        /** Часы взрыва, по которым эхо и удар у уха ждут начала прямого звука ({@link BlastSounds#clock}). */
        private final OwnSources.Clock clock = BlastSounds.clock();

        Timeline(Vec3 pos, GroundMaterial mat, long seed, @Nullable BlastSource source) {
            this(pos, mat, seed, source, true);
        }

        /** @param heard у камеры звучит (эхо ищет склоны); нет — только фронт у тела ({@link Body}) */
        Timeline(Vec3 pos, GroundMaterial mat, long seed, @Nullable BlastSource source, boolean heard) {
            this.pos = pos;
            this.mat = mat;
            this.seed = seed;
            this.random = RandomSource.create(seed);
            this.source = source;
            // взрыв под землёй волной в воздух не выходит (газы — из хода, своим пакетом): ни эха, ни удара по телу
            this.echoes = source == null || !heard || source.underground() ? null : BlastSounds.echoes(source, pos, seed, clock);
        }

        @Override
        public final boolean tick(ClientLevel level, int t) {
            Minecraft mc = Minecraft.getInstance();
            if (!arrived) {
                Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
                double d = ear.distanceTo(pos);
                if (d <= Warheads.FRONT_SPEED * t || t > 60) {
                    arrived = true;
                    if (t <= 60) arrive(level, (int) (d / Warheads.FRONT_SPEED) + 1);
                } else if (echoes != null) {
                    echoes.approach(ear);
                }
            }
            if (arrived && echoes != null) echoes.tick();
            // тело — после звуков: их слышит ухо, каким оно было до волны
            LocalPlayer player = mc.player;
            if (!hit && source != null && !source.underground() && player != null) {
                Vec3 body = player.getEyePosition();
                double d = body.distanceTo(pos);
                if (d <= Warheads.FRONT_SPEED * t) {
                    hit = true;
                    BlastHit.hit(source, pos, mat, seed, player, body, t - d / Warheads.FRONT_SPEED);
                } else if (t > 60) {
                    hit = true;
                }
            }
            boolean more = run(level, t);
            if (!more && !over) {
                over = true;
                if (echoes != null) echoes.release();
            }
            return more;
        }

        abstract boolean run(ClientLevel level, int t);

        /**
         * Фронт у слушателя: у обычного взрыва — его звук по заряду ({@link BlastSounds#arrive}).
         *
         * @param band пояс фронта: 1 — ближе 17 блоков, 2 — 17..34 …
         */
        void arrive(ClientLevel level, int band) {
            if (source != null) BlastSounds.arrive(source, pos, seed, clock, echoes);
        }

        /**
         * Вспышка на экране: свет мгновенный, сила — {@link FlashFalloff} по расстоянию, взгляду и прямой видимости
         * от камеры (с борта снаряда и в камере наблюдения — оттуда, куда смотрит игрок, а не от его тела) и по свету
         * вокруг глаза: днём под небом экран едва светлеет ({@link FlashFalloff#daylight}), ночью и в укрытии — вспышка.
         *
         * @param near ближе этого — полная сила, блоки
         */
        void flash(ClientLevel level, double near, double range, float decay) {
            flash(level, pos, near, range, decay);
        }

        /**
         * Вспышка по модели облака: свет — шар продуктов детонации ({@link CloudModel#heBall}, ближе его радиуса — полная
         * сила; дальше вуаль спадает как радиус/d и пропадает там, где она слабее {@link Flash#FLOOR}), гаснет, как
         * остывает шар (чёрное тело от {@link CloudModel#HE_BALL_HOT} к {@link CloudModel#HE_BALL_END} за его жизнь),
         * цвета чёрного тела в начале.
         */
        void flash(ClientLevel level, CloudModel m) {
            double near = flashNear(m), range = flashRange(m);
            double fall = BlackBody.luminance(CloudModel.HE_BALL_END) / BlackBody.luminance(CloudModel.HE_BALL_HOT);
            float decay = (float) Math.exp(Math.log(fall) / Math.max(1, 20 * m.heBallSeconds));
            double[] c = new double[4];
            BlackBody.light(CloudModel.HE_BALL_HOT, c);
            double top = Math.max(c[0], Math.max(c[1], c[2]));
            int rgb = (int) Math.round(255 * c[0] / top) << 16 | (int) Math.round(255 * c[1] / top) << 8 | (int) Math.round(255 * c[2] / top);
            flash(level, pos, near, range, decay, rgb);
        }

        /** То же с источником света в {@code at}. */
        void flash(ClientLevel level, Vec3 at, double near, double range, float decay) {
            flash(level, at, near, range, decay, 0xFFF1C8);
        }

        /** То же своего цвета. */
        void flash(ClientLevel level, Vec3 at, double near, double range, float decay, int rgb) {
            Minecraft mc = Minecraft.getInstance();
            Player player = mc.player;
            if (player == null) return;
            Camera camera = mc.gameRenderer.getMainCamera();
            Vec3 eye = camera.getPosition();
            float daylight = FlashFalloff.daylight(FarView.adaptation(level, eye, 1));
            if (daylight <= 0) return;
            Vec3 light = at.add(0, 1.5, 0);
            Vec3 to = light.subtract(eye);
            double d = to.length();
            if (d >= range) return;
            boolean visible = level.clip(new ClipContext(eye, light, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
                    .getType() == HitResult.Type.MISS;
            double cos = d < 1e-6 ? 1 : new Vec3(camera.getLookVector()).dot(to) / d;
            float s = FlashFalloff.strength(d, near, range, cos, visible) * daylight;
            if (s > 0) Flash.trigger(s, decay, rgb);
        }

        /**
         * Выброс воронки заряда {@code tntKg} кг ТНТ: комья цвета местности, сколько и как быстро — по закону выброса
         * (Housen, Schmidt, Holsapple, «Crater ejecta scaling laws», J. Geophys. Res. 88(B3), 1983): скорость кома, вылетевшего
         * с расстояния x от центра, v = C₂·√(g·R)·(x/R)^(−1/μ), доля массы быстрее v — (v/v_R)^(−3μ); сухой грунт μ ≈ 0,41,
         * C₂ ≈ 0,55 (оценка по их графикам для песка), из середины не ближе 0,2 R. Под 45° к горизонту (±10°, оценка).
         * Число комьев — объём чаши ({@link CraterModel#chargeVolume}) на ком {@link #CLOD_M3}; пыль — облако модели.
         */
        void ejecta(ClientLevel level, double tntKg) {
            if (mat.isWater()) {
                splash(level, tntKg);
                return;
            }
            CraterModel.Soil soil = mat.soil();
            ejecta(level, CraterModel.chargeRadius(tntKg, soil), CraterModel.chargeVolume(tntKg, soil));
        }

        /** Выброс воронки радиусом {@code rc} и объёмом {@code volume} м³ с основанием в {@link #pos} — тем же законом. */
        void ejecta(ClientLevel level, double rc, double volume) {
            if (rc <= 0) return;
            int n = (int) Math.min(MAX_CLODS, Math.round(volume / CLOD_M3 * Fx.density(pos)));
            double vRim = EJECTA_C2 * Math.sqrt(G * rc), uMin = Math.pow(0.2, 3);
            BlockState clod = mat.particleBlock();
            for (int i = 0; i < n; i++) {
                double u = uMin + (1 - uMin) * random.nextDouble();
                double v = vRim * Math.pow(u, -1 / (3 * EJECTA_MU)) / 20;
                double el = Math.toRadians(45 + (random.nextDouble() * 2 - 1) * 10), az = random.nextDouble() * Math.PI * 2;
                double from = Math.cbrt(u) * rc;
                Vec3 at = pos.add(Math.cos(az) * from, 0.5, Math.sin(az) * from);
                int life = (int) Math.ceil(2 * v * Math.sin(el) / GRAVITY) + 20;
                Clod p = new Clod(level, at, clod, life);
                p.setParticleSpeed(Math.cos(az) * Math.cos(el) * v, Math.sin(el) * v, Math.sin(az) * Math.cos(el) * v);
                Minecraft.getInstance().particleEngine.add(p);
            }
        }

        /**
         * Взрыв на воде: столб брызг и водяная пыль — облако модели ({@link ua.zentix.incoming.client.fx.smoke.WaterBlast}
         * у события {@code FarBlasts}); здесь — струи и комья воды частицами: бросок со скоростью до √(2gH) (H — верх столба
         * того же закона) из круга радиуса столба, почти отвесно (±15°, О), с настоящей тяжестью. Комьев — по одному на
         * {@link #SPLASH_PER_KG} кг воды столба (О: видимые на экране струи, не капли), не больше {@link #MAX_CLODS}.
         */
        void splash(ClientLevel level, double tntKg) {
            ua.zentix.incoming.client.fx.smoke.WaterBlast w = new ua.zentix.incoming.client.fx.smoke.WaterBlast(tntKg, CloudModel.Air.STANDARD);
            if (w.height <= 0) return;
            int n = (int) Math.min(MAX_CLODS, Math.round(w.water / SPLASH_PER_KG * Fx.density(pos)));
            double v0 = Math.sqrt(2 * G * w.height) / 20;
            for (int i = 0; i < n; i++) {
                double u = random.nextDouble(), v = v0 * Math.sqrt(u), az = random.nextDouble() * Math.PI * 2;
                double tilt = Math.toRadians(15 * random.nextGaussian()), r = w.radius * Math.sqrt(random.nextDouble());
                Vec3 at = pos.add(Math.cos(az) * r, 0.3, Math.sin(az) * r);
                Fx.smoke().vel(Math.cos(az) * Math.sin(tilt) * v, Math.cos(tilt) * v, Math.sin(az) * Math.sin(tilt) * v).size(0.15f, 0.35f)
                        .life((int) Math.ceil(2 * v / GRAVITY) + 10).color(0xDCE6EC, 0xF2F5F7).alpha(0.9f).drag(0.995f).gravity((float) GRAVITY)
                        .collide().wind(0).fadeIn(0).fadeFrom(0.8f).budget(FxBudget.SPRAY).spawn(level, at);
            }
        }

        /** Воды столба на один ком-частицу, кг (О). */
        static final double SPLASH_PER_KG = 5;

        /** Фонтан грунта у входа бомбы в грунт (бетонобойная — своим законом в S5). */
        void spray(ClientLevel level, int scale) {
            Particles.burst(level, FxParticles.block(mat), pos.add(0, 1, 0), 1.5, 1.5, 1.5, scale == 1 ? 0.9 : 1.3, scale == 1 ? 350 : 900);
            Particles.burst(level, FxParticles.block(mat), pos.add(0, 3, 0), 4, 3, 4, 0.4, scale == 1 ? 150 : 400);
            if (mat.isWater()) {
                Particles.burst(level, ParticleTypes.SPLASH, pos.add(0, 1, 0), 3, 3, 3, 0.8, scale == 1 ? 700 : 1800);
                Particles.burst(level, ParticleTypes.BUBBLE_POP, pos.add(0, 1, 0), 4, 2, 4, 0.3, scale == 1 ? 150 : 400);
            }
        }
    }

    /** Ком грунта выброса — объём, м³ (ком ~25 см; оценка), и больше комьев на взрыв не бывает (бюджет частиц). */
    static final double CLOD_M3 = 0.25 * 0.25 * 0.25;
    static final int MAX_CLODS = 1200;
    /** Закон выброса Housen и др. 1983 для сухого грунта: μ и C₂ (оценка). */
    static final double EJECTA_MU = 0.41, EJECTA_C2 = 0.55;
    /** Тяжесть, м/с², и она же в блоках за тик². */
    static final double G = 9.80665, GRAVITY = G / 400;

    /** Ком выброса: частица блока с настоящей тяжестью и сроком на весь полёт (у ванильной — 1,6 g и до 2 с). */
    static final class Clod extends net.minecraft.client.particle.TerrainParticle {
        Clod(ClientLevel level, Vec3 at, BlockState state, int life) {
            super(level, at.x, at.y, at.z, 0, 0, 0, state);
            // ванильная частица тянет вниз 0,04 блока за тик² — 16 м/с²
            this.gravity = (float) (GRAVITY / 0.04);
            this.friction = 0.995f;
            setLifetime(life);
        }
    }

    /**
     * Удары осколков взрыва ({@link FragmentHits}): лучи — в тик взрыва, удары — в тик прилёта каждого. Ось null —
     * осколков нет.
     */
    static final class FragmentQueue {
        private final Vec3 pos;
        @Nullable
        private final Vec3 axis;
        private final long seed;
        private final BlastSource source;
        private java.util.List<FragmentHits.Hit> hits;
        private int next;

        FragmentQueue(Vec3 pos, @Nullable Vec3 axis, long seed, BlastSource source) {
            this.pos = pos;
            this.axis = axis;
            this.seed = seed;
            this.source = source;
        }

        void tick(ClientLevel level, int t, RandomSource random) {
            if (hits == null) hits = axis == null ? java.util.List.of() : FragmentHits.of(level, source.weapon(), pos, axis, seed);
            while (next < hits.size() && hits.get(next).tick() <= t) FragmentHits.show(level, hits.get(next++), random);
        }
    }

    // ================================================================ шахед, «Ланцет», «Град» (fx)

    static final class Drone extends Timeline {
        /** Облако по модели: вспышка, размер шара для осколков и искр, сколько горит топливо. */
        private final CloudModel model;
        /** Снаряд РСЗО: звук свой — сухой разрыв. */
        private final boolean rocket;
        private final FragmentQueue fragments;

        Drone(Vec3 pos, GroundMaterial mat, long seed, @Nullable Vec3 axis, boolean rocket, CloudModel model) {
            super(pos, mat, seed, model.source);
            this.rocket = rocket;
            this.model = model;
            this.fragments = new FragmentQueue(pos, axis, seed, model.source);
        }

        /** Радиус огненного шара модели, блоки: по нему осколки, искры и языки пламени. */
        float radius() {
            return (float) Math.max(0.5 * model.fuelBall, model.heBall);
        }

        @Override
        boolean run(ClientLevel level, int t) {
            fragments.tick(level, t, random);
            if (t == 0) {
                flash(level, model);
                Explosions.solids(level, pos, radius(), model.source.fuelKg() > 0, random);
                Particles.burst(level, ParticleTypes.LAVA, pos.add(0, 1, 0), 2, 1, 2, 0, 30);
                return true;
            }
            if (t == 1) ejecta(level, model.source.chargeKg());
            // лужа топлива, что не сгорела шаром, горит в воронке
            Explosions.pool(level, pos, model.poolRadius, model.poolFlame(), model.poolShare(t / 20.0), random);
            crater(model, pos, t, rocket ? 1.25f : 1.1f);
            return t < Math.max(240, 20 * model.poolSeconds);
        }

    }

    /**
     * Звук воронки по модели облака: выброс падает обратно — через секунду (О: полёт выброса на высоту порядка диаметра
     * воронки), масса — воронка без пыли, что ушла в воздух; гул огня, пока горит лужа топлива, — по её теплу сейчас,
     * запись заново, когда прошлая кончилась ({@link #FIRE_REPEAT}).
     */
    static void crater(CloudModel model, Vec3 pos, int t, float pitch) {
        double charge = model.source.chargeKg();
        if (t == 20 && charge > 0) {
            double kg = CraterModel.chargeVolume(charge, CraterModel.Soil.DRY) * CloudModel.SOIL_DENSITY * (1 - CloudModel.DUST_LOFTED);
            BlastSounds.debris(pos, kg, 2 * CraterModel.chargeRadius(charge, CraterModel.Soil.DRY), pitch);
        }
        double share = model.poolShare(t / 20.0);
        if (t >= 8 && (t - 8) % FIRE_REPEAT == 0 && share > 0) BlastSounds.fire(pos, model.poolWatts * share, pitch);
    }

    /** Запись гула огня — 14 с с краями: следующая — через столько тиков. */
    static final int FIRE_REPEAT = 260;

    // ================================================================ разрыв зенитной ракеты

    static final class Airburst extends Timeline {
        /** Радиус клуба огня, блоки (осколочно-фугасная часть ~20 кг). */
        static final float R = 2.5f;
        /**
         * Заряд разрыва, кг ТНТ — для звука, слуха и удара по телу. Оценка сверху: вся боевая часть ~20 кг, хотя ВВ в
         * осколочной части — её доля (зенитной ракеты в паспорте нет).
         */
        static final double CHARGE_KG = 20;
        static final double FLASH_NEAR = 3 * R, FLASH_RANGE = 160;
        private final boolean kill;

        Airburst(Vec3 pos, long seed, boolean kill) {
            super(pos, GroundMaterial.STONE, seed, BlastSource.air(CHARGE_KG));
            this.kill = kill;
        }

        @Override
        boolean run(ClientLevel level, int t) {
            if (t == 0) {
                flash(level, pos.subtract(0, 1.5, 0), FLASH_NEAR, FLASH_RANGE, 0.6f);
                Explosions.fireball(level, pos, R, 6, random);
                // чёрное облачко разрыва висит в воздухе и тает
                int n = Math.round(10 * Fx.density(pos)) + 3;
                for (int i = 0; i < n; i++) {
                    Vec3 d = Explosions.dir(random, -1);
                    Fx.smoke().vel(d.scale(0.25)).size(R * 0.4f, R * (0.9f + 0.4f * random.nextFloat())).growFast().life(240 + random.nextInt(120))
                            .color(0x1E1C1A, 0x5A5650).alpha(0.85f).glow(1, 4).drag(0.85f).rise(0.001f).fadeIn(1).fadeFrom(0.45f)
                            .budget(FxBudget.GROUND).spawn(level, pos.add(d.scale(R * 0.4)));
                }
                for (int i = 0; i < 30; i++) {
                    Fx.spark().vel(Explosions.dir(random, -1).scale(0.6 + random.nextDouble() * 1.2)).life(8 + random.nextInt(14)).gravity(0.03f)
                            .spawn(level, pos);
                }
                if (kill) Explosions.cookoff(level, pos, 2.0f, random);
            }
            return t < 61;
        }

    }

    // ================================================================ крылатая ракета (mfx)

    static final class Missile extends Timeline {
        /** Облако по модели: вспышка, размер шара, сколько горит топливо. */
        private final CloudModel model;

        private final FragmentQueue fragments;

        Missile(Vec3 pos, GroundMaterial mat, long seed, @Nullable Vec3 axis, CloudModel model) {
            super(pos, mat, seed, model.source);
            this.model = model;
            this.fragments = new FragmentQueue(pos, axis, seed, model.source);
        }

        float radius() {
            return (float) Math.max(0.5 * model.fuelBall, model.heBall);
        }

        @Override
        boolean run(ClientLevel level, int t) {
            fragments.tick(level, t, random);
            if (t == 0) {
                flash(level, model);
                Explosions.solids(level, pos, radius(), model.source.fuelKg() > 0, random);
                Particles.burst(level, ParticleTypes.LAVA, pos.add(0, 1, 0), 4, 2, 4, 0, 80);
                return true;
            }
            if (t == 1) ejecta(level, model.source.chargeKg());
            // вторичных подрывов нет: вторичное — топливо (лужа в воронке) и горящие обломки сервера
            Explosions.pool(level, pos, model.poolRadius, model.poolFlame(), model.poolShare(t / 20.0), random);
            crater(model, pos, t, 0.9f);
            return t < Math.max(340, 20 * model.poolSeconds);
        }

    }

    // ================================================================ бетонобойная бомба (bfx)

    static final class Bunker extends Timeline {
        /** Место заряда; основание эффекта ({@link #pos}) — верх над ним: оттуда вспышка, комья, звук. */
        private final Vec3 charge;
        private final Vec3 surface;
        private final int depth;
        /** Подземный взрыв по приведённой глубине. */
        private final BuriedBlast law;
        /** Облако прорыва наружу ({@link FarBlasts#add}); null — взрыв под землёй. */
        @Nullable
        private final CloudModel cloud;

        Bunker(Vec3 pos, GroundMaterial mat, float surfaceY, long seed, @Nullable CloudModel cloud) {
            super(new Vec3(pos.x, surfaceY, pos.z), mat, seed, BlastSource.buried(pos.y, surfaceY));
            this.charge = pos;
            this.surface = new Vec3(pos.x, surfaceY, pos.z);
            this.depth = BunkerCover.depth((int) surfaceY, charge.y);
            this.law = BlastSource.law(pos.y, surfaceY);
            this.cloud = cloud;
        }

        /**
         * Прорыв наружу — вспышка и комья воронки по той доле заряда, что вышла в воздух (облако — у {@link FarBlasts}); глубже
         * вспышку видно только в самой полости. Поверхность в зоне трещин вспучивается, с потолка ходов сыплется крошка.
         */
        @Override
        boolean run(ClientLevel level, int t) {
            if (t == 0) {
                if (cloud != null) {
                    // вспышка — над воронкой, комья — по воронке выброса закона (её роет сервер)
                    flash(level, cloud);
                    CraterModel.Soil soil = mat.soil();
                    ejecta(level, law.craterRadius(soil), law.craterVolume(soil));
                } else {
                    // под землёй вспышку видно только в самой полости и рядом
                    flash(level, charge, 8, 70, 0.8f);
                }
                return true;
            }
            if (t % 2 == 0 && t >= 2 && t <= 220 && cloud == null) {
                // каменная крошка с потолка полости и ходов рядом
                Particles.burst(level, FxParticles.fallingDust(mat), charge.add(0, 7, 0), 7, 1, 7, 0, 14);
            }
            if (t <= 16 && depth <= law.fracture()) heave(level, t * 2);
            // выброс воронки падает обратно: грунт чаши закона (её роет сервер); под землёй выброса нет
            if (t == 30 && cloud != null) {
                CraterModel.Soil soil = mat.soil();
                BlastSounds.debris(surface, law.craterVolume(soil) * CloudModel.SOIL_DENSITY, law.craterDepth(soil), 0.75f);
            }
            return t < 240;
        }

        private void heave(ClientLevel level, double r) {
            if (r == 0) Fx.ring().size(1, 36).life(16).alpha(0.4f).color(Explosions.rgb(mat), Explosions.rgb(mat)).spawn(level, surface.add(0, 0.3, 0));
            for (double a = 0; a < 360; a += 15) {
                Particles.burst(level, FxParticles.block(mat), FxParticles.ground(level, surface, a, r).add(0, 0.2, 0), 0.7, 0.1, 0.7, 0.3, 10);
            }
        }

        /** Прорыв наружу — звук взрыва той доли заряда, что вышла в воздух ({@link BlastSounds#arrive}); глубже — глухой удар. */
        @Override
        void arrive(ClientLevel level, int band) {
            if (!source.underground()) {
                super.arrive(level, band);
                return;
            }
            Player player = Minecraft.getInstance().player;
            boolean sky = player != null && level.canSeeSky(BlockPos.containing(player.getEyePosition()));
            BlastSounds.buried(source, pos, !sky);
        }
    }

    // ================================================================ вход бомбы, выброс газов, провал

    /**
     * Бомба вошла в грунт: облако пыли выброса из воронки входа ({@link BunkerImpact#entryVolume}, без жара — у земли, по
     * ветру; рисует слой) и комья; тупой удар приходит со скоростью звука.
     */
    public static void bunkerImpact(S2C.BunkerImpact p) {
        GroundMaterial mat = GroundMaterial.byId(p.material());
        Vec3 pos = p.pos();
        ClientLevel world = Minecraft.getInstance().level;
        if (world != null) {
            double d = WeaponType.BUNKER.spec().striker().article().diameter();
            BlastSource none = new BlastSource(WeaponType.BUNKER, 0, 0, true, false);
            FarBlasts.cloud(world, none, CloudModel.dust(none, FarBlasts.air(world, pos), BunkerImpact.entryVolume(d), 2 * d), pos, mat,
                    Double.doubleToLongBits(pos.x * 31 + pos.z));
        }
        Effects.add(new Timeline(pos, mat, 0, null) {
            @Override
            boolean run(ClientLevel level, int t) {
                if (t == 0) spray(level, 1);
                return t < 30;
            }

            @Override
            void arrive(ClientLevel level, int band) {
                BlastSounds.bunkerImpact(pos, BunkerImpact.equivalentTnt(WeaponType.BUNKER.spec().striker().article().dryMass(), p.speed()));
            }
        });
    }

    /**
     * Газы взрыва вырываются по скважине: облако той доли заряда, что вышла в воздух ({@code airKg}), из устья хода — по
     * той же модели облака (жар, сажа, пыль; рисует слой), раскат вдали — глухой, из-под земли; вблизи — рёв выброса.
     */
    public static void vent(S2C.Vent p) {
        GroundMaterial mat = GroundMaterial.byId(p.material());
        Vec3 pos = p.pos();
        ClientLevel world = Minecraft.getInstance().level;
        if (world != null) {
            BlastSource gas = new BlastSource(WeaponType.BUNKER, p.airKg(), 0, true, true);
            FarBlasts.cloud(world, gas, new CloudModel(gas, FarBlasts.air(world, pos)), pos, mat, Double.doubleToLongBits(pos.x * 17 + pos.z));
        }
        Effects.add(new Timeline(pos, mat, 0, null) {
            @Override
            boolean run(ClientLevel level, int t) {
                return t < 40;
            }

            @Override
            void arrive(ClientLevel level, int band) {
                BlastSounds.vent(pos, p.airKg());
            }
        });
    }

    /**
     * Свод над полостью обрушился до верха: пыль над провалом (грунт объёма полости, без жара — у земли, по ветру; рисует
     * слой) и гул обвала.
     */
    public static void collapse(S2C.Collapse p) {
        Vec3 pos = p.pos();
        GroundMaterial mat = GroundMaterial.byId(p.material());
        ClientLevel world = Minecraft.getInstance().level;
        if (world != null) {
            BlastSource none = new BlastSource(WeaponType.BUNKER, 0, 0, true, true);
            double r = Math.cbrt(3 * p.volume() / (4 * Math.PI));
            FarBlasts.cloud(world, none, CloudModel.dust(none, FarBlasts.air(world, pos), p.volume(), r), pos, mat,
                    Double.doubleToLongBits(pos.x * 13 + pos.z));
        }
        Effects.add(new Timeline(pos, mat, 0, null) {
            @Override
            boolean run(ClientLevel level, int t) {
                return t < 20;
            }

            @Override
            void arrive(ClientLevel level, int band) {
                BlastSounds.collapse(pos, BuriedBlast.collapseTnt(p.volume()));
            }
        });
    }
}
