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
import ua.zentix.incoming.client.fx.smoke.BlastTarget;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.Ejecta;
import ua.zentix.incoming.client.sound.BlastEchoes;
import ua.zentix.incoming.client.sound.BlastSounds;
import ua.zentix.incoming.client.sound.OwnSources;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.net.S2C;
import net.minecraft.world.level.block.state.BlockState;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.ChemicalFront;
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
        // топливо в воронке: от сервера — пакетом своего огня на блоках (fuelFire); в повторе, записанном до него, и у
        // взрыва без сервера (песочница) — дым и гул у места удара, как тогда
        if (!p.fuelApart() && cloud != null && cloud.poolWatts > 0) legacyPool(p.pos(), cloud.pool);
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
     * Зенитная ракета разорвалась в воздухе ({@link S2C.Intercept}): облако разрыва — модель облака заряда боевой части
     * ({@link FarBlasts#airburst}: вспышка, шар продуктов, облачко сажи — запечёнными клубами слоя, вдали — раскат), вблизи
     * ещё искры осколков; сбила — горящее топливо цели. Хлопок и лёгкая тряска — с фронтом.
     */
    public static void intercept(S2C.Intercept p) {
        BlastSource source = BlastSource.air(Airburst.CHARGE_KG);
        CloudModel cloud = FarBlasts.airburst(p.pos(), source, p.seed());
        if (bodyOnly(p.pos())) Effects.add(new Body(p.pos(), GroundMaterial.STONE, p.seed(), source));
        else if (FarBlasts.near(p.pos()) && cloud != null) Effects.add(new Airburst(p.pos(), p.seed(), p.kill(), cloud));
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

    /** Тики, за которые звук события без своей волны (со скоростью звука) проходит {@code range} блоков. */
    static int hearTicks(double range) {
        return (int) Math.ceil(range / Warheads.FRONT_SPEED);
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
        /**
         * Фронт волны во времени ({@link ChemicalFront}: Кинни–Грэхем и Ренкин–Гюгонио) — по нему приходят звук и удар по
         * телу и его же видно кольцом ({@code FarBlasts.front}); null — у взрыва своя волна (бомба под землёй): со
         * скоростью звука.
         */
        @Nullable
        private final ChemicalFront front;
        private boolean arrived, hit, over;
        private long bornTick = Long.MIN_VALUE, lastTick = Long.MIN_VALUE;
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
            this.front = source == null || source.underground() || source.chargeKg() <= 0 ? null : ChemicalFront.cached(source.chargeKg(), source.surface());
        }

        /** Тики с взрыва до прихода фронта на {@code d} блоков: вблизи фронт быстрее звука (у шахеда в 100 м — на 0,7 тика). */
        double arrivalTicks(double d) {
            return FarBlasts.arrivalTicks(front, d);
        }

        /** Фронт срывает траву и листья вокруг ({@link FrontLitter}): огненный шар модели, высота разрыва над землёй. */
        void litter(ClientLevel level, CloudModel model) {
            FrontLitter.spawn(level, pos, front, model.ballRadius(0.5 * model.burnSeconds), model.hob, random);
        }

        @Override
        public final boolean tick(ClientLevel level, int scheduledTick) {
            long now = BlastTime.ticks();
            if (bornTick == Long.MIN_VALUE) bornTick = now;
            if (now == lastTick) return true; // Effects.add already ran t=0 in this same post tick.
            if (now < bornTick) return false;
            lastTick = now;
            int t = (int) Math.min(Integer.MAX_VALUE, now - bornTick);
            Minecraft mc = Minecraft.getInstance();
            if (!arrived) {
                Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
                double d = ear.distanceTo(pos);
                int wait = soundWait();
                if (arrivalTicks(d) <= t || t > wait) {
                    arrived = true;
                    if (t <= wait) arrive(level, (int) Math.ceil(arrivalTicks(d)));
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
                if (arrivalTicks(d) <= t) {
                    hit = true;
                    BlastHit.hit(source, pos, mat, seed, player, body, t - arrivalTicks(d));
                } else if (t > 60) {
                    hit = true;
                }
            }
            if (!over && !run(level, t)) {
                over = true;
                if (echoes != null) echoes.release();
            }
            // картинка кончилась, а звук ещё идёт к уху: событие живёт до его прихода, а не столько, сколько видно
            return !over || !arrived;
        }

        abstract boolean run(ClientLevel level, int t);

        /**
         * Дольше этого фронт до уха не ждём, тиков. Обычный взрыв дальше {@link FarBlasts#NEAR} озвучивает
         * {@link FarBlasts}, ближней модели хватает 60 тиков (1 км); событие бомбы без дальнего звука (вход в грунт,
         * обвал) ждёт путь звука на всю свою слышимость ({@link #hearTicks}).
         */
        int soundWait() {
            return 60;
        }

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
         * Выброс по цели модели облака ({@link BlastTarget}): у грунта — комья воронки, у твёрдого — сколы её (связной) чаши, у
         * плиты над пустотой — обломки пролома с лица и с тыла, у воды — струи, у разрыва в воздухе — ничего (грунта под ним нет;
         * прежде комья летели и у разрыва над крышей, physics.md фронта F). Пыль — облако модели, завеса зёрен — его событие;
         * кусками летит только своя доля выброса ({@link BlastTarget#clods}).
         */
        void ejecta(ClientLevel level, CloudModel model) {
            BlastTarget t = model.target;
            double tnt = model.source.chargeKg();
            switch (t.family()) {
                case WATER -> splash(level, tnt);
                case SOIL, HARD -> pieces(level, new Ejecta(CraterModel.chargeRadius(tnt, mat.soil()), 0, 0), pos, UP, t.clodVolume(tnt));
                case SLAB -> slab(level, t, tnt);
                case AIR -> {
                }
            }
        }

        /**
         * Куски выброса {@code law} объёмом {@code volume} м³ с грани в {@code origin} наружу {@code up} ({@link Ejecta#pieces}:
         * место вылета, скорость и угол — те же, что у завесы слоя эффектов). Частица — кусок или горсть: число — объём на ком
         * {@link #CLOD_M3} (счёт представления массы, а не дробления).
         */
        void pieces(ClientLevel level, Ejecta law, Vec3 origin, Vec3 up, double volume) {
            if (law.radius <= 0) return;
            BlockState clod = mat.particleBlock();
            law.pieces(origin, up, count(volume), random::nextDouble, (at, v) -> spawn(level, clod, at, v, 0));
        }

        /**
         * Обломки пролома плиты над пустотой: лицевая крошка — с грани заряда наружу, против оси дыры, тем же законом выброса,
         * что у воронки радиуса пролома (⚠ своего закона крошки лицевой грани в моде нет); тыльный откол — из дыры на тыльной
         * грани по оси дыры ({@link BlastTarget#spall}: нормаль пробитой грани — у крыши вниз, у стены вбок; скорость пробки по
         * Гёрни). Кусками — весь выброс пролома без пыли ({@link BlastTarget#clodVolume}), поровну спереди и сзади (⚠ доли нет в
         * источниках: physics.md фронта F — дробление спереди и откол сзади, без чисел). Места заряда на грани нет (опоры в
         * пакете нет) — крошка с точки взрыва, откола нет.
         */
        void slab(ClientLevel level, BlastTarget t, double tnt) {
            double half = t.clodVolume(tnt) / 2;
            pieces(level, new Ejecta(t.hole(), 0, 0), t.face() != null ? t.face() : pos, t.normal().scale(-1), half);
            BlockState piece = mat.particleBlock();
            // откол летит в пустоту под плитой — до пола; срок с запасом (5 с, как прежде)
            t.spall(tnt, count(half), random::nextDouble, (p, v) -> spawn(level, piece, p, v, 100));
        }

        /** Частиц на объём {@code m3}: ком {@link #CLOD_M3}, с плотностью частиц игрока, не больше {@link #MAX_CLODS}. */
        private int count(double m3) {
            return (int) Math.min(MAX_CLODS, Math.round(m3 / CLOD_M3 * Fx.density(pos)));
        }

        /**
         * Кусок с места {@code at} со скоростью {@code v} (м/с): живёт до возврата на высоту вылета и ещё секунду, но не меньше
         * {@code least} тиков.
         */
        private void spawn(ClientLevel level, BlockState block, Vec3 at, Vec3 v, int least) {
            Vec3 tick = v.scale(1 / 20.0);
            int life = (int) Math.ceil(2 * Math.max(tick.y, 0) / GRAVITY) + 20;
            Clod p = new Clod(level, at, block, Math.max(life, least));
            p.setParticleSpeed(tick.x, tick.y, tick.z);
            Minecraft.getInstance().particleEngine.add(p);
        }

        /** Вверх мира. */
        static final Vec3 UP = new Vec3(0, 1, 0);

        /**
         * Взрыв на воде: столб брызг и водяная пыль — облако модели ({@link ua.zentix.incoming.client.fx.smoke.WaterBlast}
         * у события {@code FarBlasts}); здесь — струи и комья воды частицами: бросок со скоростью до √(2gH) (H — верх столба
         * того же закона) из полости в половину радиуса столба, в конусе струй закона, с настоящей тяжестью. Комьев — по
         * одному на {@link #SPLASH_PER_KG} кг воды столба (О: видимые на экране струи, не капли), не больше {@link #MAX_CLODS}.
         */
        void splash(ClientLevel level, double tntKg) {
            ua.zentix.incoming.client.fx.smoke.WaterBlast w = FarBlasts.water(level, pos, tntKg, FarBlasts.air(level, pos));
            if (w.height <= 0) return;
            int n = (int) Math.min(MAX_CLODS, Math.round(w.water / SPLASH_PER_KG * Fx.density(pos)));
            double v0 = w.speed / 20;
            for (int i = 0; i < n; i++) {
                double u = random.nextDouble(), v = v0 * Math.sqrt(0.1 + 0.9 * u), az = random.nextDouble() * Math.PI * 2;
                double tilt = w.cone * Math.sqrt(random.nextDouble()), r = 0.5 * w.radius * Math.sqrt(random.nextDouble());
                Vec3 at = pos.add(Math.cos(az) * r, w.depth + 0.3, Math.sin(az) * r);
                Fx.smoke().vel(Math.cos(az) * Math.sin(tilt) * v, Math.cos(tilt) * v, Math.sin(az) * Math.sin(tilt) * v).size(0.3f, 0.7f)
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
                litter(level, model);
                Particles.burst(level, ParticleTypes.LAVA, pos.add(0, 1, 0), 2, 1, 2, 0, 30);
                return true;
            }
            if (t == 1) ejecta(level, model);
            // топливо, что не сгорело шаром, горит в воронке огнём Minecraft на блоках (сервер, `FuelFires`): его дым и звук
            // приходят пакетом с этими блоками (`fuelFire`)
            crater(model, pos, t, rocket ? 1.25f : 1.1f);
            return t < 240;
        }

    }

    /**
     * Звук воронки по модели облака: выброс падает обратно — через секунду (О: полёт выброса на высоту порядка диаметра
     * воронки), масса — воронка без пыли, что ушла в воздух. Гул горящего топлива — от его огня на блоках ({@link #fuelFire}).
     */
    static void crater(CloudModel model, Vec3 pos, int t, float pitch) {
        double charge = model.source.chargeKg();
        if (t == 20 && charge > 0) {
            // масса — разрушенного у цели (тот же бюджет, что у пыли облака и выброса); у разрыва в воздухе и на воде — 0
            double kg = model.target.ballistic(charge);
            if (kg > 0) BlastSounds.debris(pos, kg, 2 * CraterModel.chargeRadius(charge, model.target.material().soil()), pitch);
        }
    }

    // ================================================================ разрыв зенитной ракеты

    static final class Airburst extends Timeline {
        /** Заряд разрыва, кг ТНТ — для облака, звука, слуха и удара по телу: боевая часть ракеты ЗРК по её паспорту. */
        static final double CHARGE_KG = InterceptorSpec.SAM.passport().warhead().tnt();
        /** Облако разрыва по модели: вспышка, шар, облачко ({@link FarBlasts#airburst}). */
        private final CloudModel model;
        private final boolean kill;

        Airburst(Vec3 pos, long seed, boolean kill, CloudModel model) {
            super(pos, GroundMaterial.STONE, seed, model.source);
            this.model = model;
            this.kill = kill;
        }

        @Override
        boolean run(ClientLevel level, int t) {
            if (t == 0) {
                flash(level, model);
                // осколки корпуса боевой части — искрами
                for (int i = 0; i < 30; i++) {
                    Fx.spark().vel(Explosions.dir(random, -1).scale(0.6 + random.nextDouble() * 1.2)).life(8 + random.nextInt(14)).gravity(0.03f)
                            .spawn(level, pos);
                }
                if (kill) Explosions.cookoff(level, pos, 2.0f, random);
                litter(level, model);
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
                litter(level, model);
                Particles.burst(level, ParticleTypes.LAVA, pos.add(0, 1, 0), 4, 2, 4, 0, 80);
                return true;
            }
            if (t == 1) ejecta(level, model);
            // вторичных подрывов нет: вторичное — топливо в воронке (огонь на блоках сервера) и горящие обломки сервера
            crater(model, pos, t, 0.9f);
            return t < 340;
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
                    pieces(level, new Ejecta(law.craterRadius(soil), 0, 0), pos, UP, law.craterVolume(soil));
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
            // выброс воронки падает обратно, как у воронки наземного взрыва ({@link #crater}): грунт чаши закона без пыли,
            // что ушла в воздух, с высоты порядка диаметра воронки; под землёй и в воде выброса нет
            if (t == 20 && cloud != null && !mat.isWater()) {
                CraterModel.Soil soil = mat.soil();
                BlastSounds.debris(surface, law.craterVolume(soil) * CloudModel.SOIL_DENSITY * (1 - CloudModel.DUST_LOFTED),
                        2 * law.craterRadius(soil), 0.75f);
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
        if (world != null && !mat.isWater()) {
            // в воду — без пыли грунта (брызги — частицами ниже)
            double d = WeaponType.BUNKER.spec().striker().article().diameter();
            BlastSource none = new BlastSource(WeaponType.BUNKER, 0, 0, true, false);
            FarBlasts.cloud(world, none, CloudModel.dust(none, FarBlasts.air(world, pos), BunkerImpact.entryVolume(d), 2 * d), pos, mat,
                    Double.doubleToLongBits(pos.x * 31 + pos.z));
        }
        double tnt = BunkerImpact.equivalentTnt(WeaponType.BUNKER.spec().striker().article().dryMass(), p.speed());
        Effects.add(new Timeline(pos, mat, 0, null) {
            @Override
            boolean run(ClientLevel level, int t) {
                if (t == 0) {
                    if (mat.isWater()) Particles.burst(level, ParticleTypes.SPLASH, pos.add(0, 1, 0), 2, 2, 2, 0.8, 400);
                    else spray(level, 1);
                }
                return t < 30;
            }

            @Override
            int soundWait() {
                return hearTicks(BlastSounds.bunkerImpactRange(tnt));
            }

            @Override
            void arrive(ClientLevel level, int band) {
                BlastSounds.bunkerImpact(pos, tnt);
            }
        });
    }

    /**
     * Топливо удара горит на блоках (сервер, {@code warhead/FuelFires}): пламя рисует сам мир; очаг пожара
     * ({@link FireSmoke#fuel}: дым, свет пламени на мир и дым, пламя вдали) и его гул — у клиента, по закону топлива
     * оружия на клетках пакета ({@code WeaponSpec.pool(lit)}: легло на меньшую площадь — горит дольше и слабее, как у
     * сервера). Пакет приходит и с переменой (стекло, засыпало, залило) — очаг и гул идут за ней.
     */
    public static void fuelFire(S2C.FuelFire p) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || p.cells().isEmpty()) return;
        ua.zentix.incoming.nuclear.model.FuelPool pool = WeaponType.byId(p.weapon()).spec().pool(p.cells().size());
        long age = Math.max(0, level.getGameTime() - p.blastTick());
        if (pool.seconds() <= 0 || age / 20.0 >= pool.seconds()) return;
        FuelBurn burn = FireSmoke.fuel(level, p.id(), pool, p.cells(), p.smothered(), age / 20.0);
        // гул — у пожара ближе {@link FarBlasts#NEAR}, как и прежде (звук воронки дальше не заводится)
        if (burn != null && FarBlasts.near(burn.centre())) BlastSounds.fire(burn, (int) age);
    }

    /** Дым и гул топлива в воронке у самой точки удара (огня сервера на блоках нет: старая запись, песочница без сервера). */
    private static void legacyPool(Vec3 pos, ua.zentix.incoming.nuclear.model.FuelPool pool) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        FuelBurn burn = FuelBurn.at(pool, BlockPos.containing(pos));
        FireSmoke.pool(level, burn, 0);
        if (FarBlasts.near(burn.centre())) BlastSounds.fire(burn, 0);
    }

    /**
     * Газы взрыва вырываются по скважине: облако той доли заряда, что вышла в воздух ({@code airKg}), из устья хода — по
     * той же модели облака (жар, сажа, пыль; рисует слой), раскат вдали — глухой, из-под земли; вблизи — рёв выброса.
     * Голос у выброса один: дальше {@link FarBlasts#NEAR} его раскат заводит облако ({@link FarBlasts#cloud}), ближе —
     * рёв здесь.
     */
    public static void vent(S2C.Vent p) {
        GroundMaterial mat = GroundMaterial.byId(p.material());
        Vec3 pos = p.pos();
        ClientLevel world = Minecraft.getInstance().level;
        if (world != null) {
            BlastSource gas = new BlastSource(WeaponType.BUNKER, p.airKg(), 0, true, true);
            FarBlasts.cloud(world, gas, new CloudModel(gas, FarBlasts.air(world, pos), mat.isWater()), pos, mat, Double.doubleToLongBits(pos.x * 17 + pos.z));
        }
        if (!FarBlasts.near(pos)) return;
        Effects.add(new Timeline(pos, mat, 0, null) {
            @Override
            boolean run(ClientLevel level, int t) {
                return t < 40;
            }

            @Override
            int soundWait() {
                return hearTicks(FarBlasts.NEAR);
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
        double tnt = BuriedBlast.collapseTnt(p.volume());
        Effects.add(new Timeline(pos, mat, 0, null) {
            @Override
            boolean run(ClientLevel level, int t) {
                return t < 20;
            }

            @Override
            int soundWait() {
                return hearTicks(BlastSounds.collapseRange(tnt));
            }

            @Override
            void arrive(ClientLevel level, int band) {
                BlastSounds.collapse(pos, tnt);
            }
        });
    }
}
