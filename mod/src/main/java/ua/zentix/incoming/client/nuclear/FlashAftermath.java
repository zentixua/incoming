package ua.zentix.incoming.client.nuclear;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.Effects;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.far.FarBlasts;
import ua.zentix.incoming.client.fx.smoke.Puffs;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.grid.Districts;
import ua.zentix.incoming.grid.GridLights;
import ua.zentix.incoming.grid.Outage;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.flash.FlashChar;
import ua.zentix.incoming.nuclear.model.Fragility;
import ua.zentix.incoming.nuclear.world.ThermalShadow;
import ua.zentix.incoming.util.Palettes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Что вспышка делает с округой в первые секунды, до волны (только вживую — из перемотки повтора не рождается):
 * <ul>
 *     <li>ЭМИ — фонари и лампы сети (те, что гасит блэкаут, {@link GridLights}) в кварталах, которые гасит удар (радиус
 *     с сервера: по настройкам сети), коротят: голубая дуга, ломаная разряда и искры, вразнобой, чаще сразу, до прихода
 *     волны к лампе и не позже {@link #ARC_WINDOW} тиков; дуга трещит — 2–4 разряда через 1–3 тика, у части ламп потом
 *     ещё раз. Клиент сам ищет их в своих чанках по палитрам секций, не больше {@link #SECTIONS_PER_TICK} секций за тик,
 *     ближние первыми; дуг — не больше {@link #MAX_ARCS}. Подстанции здесь нет: удар выбивает их на сервере
 *     ({@code Blackouts.nuke}), и у каждой — своя дуга выхода из строя ({@code GridEffects.failure}), вторая была бы
 *     лишней;</li>
 *     <li>дымки с того, что свет прогрел: крыши, земля с травой, листва и деревянные стены лицом к шару курятся дымом
 *     пиролиза, пока до них не дойдёт волна ({@link Smoke}). Места — точки перед камерой (до {@link #SMOKE_RANGE}
 *     блоков), свет — как у гари ({@link FlashChar}): импульс × угол × тень по картам высот клиента; дым — выбросы модели
 *     облака ({@link Puffs}).</li>
 * </ul>
 */
public final class FlashAftermath {
    /** Дуг ЭМИ, не больше (ближние к камере). */
    static final int MAX_ARCS = 48;
    /** Секций, просмотренных за тик в поисках ламп. */
    static final int SECTIONS_PER_TICK = 48;
    /** Дольше этого (тики) после вспышки лампы не коротят. */
    static final int ARC_WINDOW = 50;
    /** Голубовато-белый свет дуги и его синий край — как у подстанции ({@code GridEffects}). */
    private static final int ARC = 0xDCE8FF, ARC_EDGE = 0x5A7CFF;
    /** Звеньев ломаной разряда и точек на звено. */
    private static final int BOLT_SEGMENTS = 5, BOLT_DOTS = 3;
    /** Докуда от камеры ищутся дымки, блоки. */
    static final double SMOKE_RANGE = 128;
    /** Медиана и разброс света, от которого поверхность курится, кал/см². */
    static final double SMOKE_Q = 5, SMOKE_BETA = 0.6;

    /** Дуг у последнего подрыва и выбросов дыма пиролиза (строка сценария клиента). */
    private static volatile int lastArcs = -1;
    private static volatile int puffs;

    private FlashAftermath() {}

    /** Строка {@code SCENARIO}: сколько дуг ЭМИ нашлось у последнего подрыва и сколько мест у дымков. */
    public static String describe() {
        return "emp arcs " + lastArcs + ", scorch smoke " + puffs + " puffs";
    }

    /**
     * Живой подрыв: ЭМИ и дымки. {@code blackout} — радиус, где сервер гасит сеть ({@code Blackouts.nukeRadius}); 0 —
     * не гасит (блэкаут выключен настройкой), и лампы не коротят: иначе они «сгорали» бы и оставались гореть.
     */
    static void detonation(Detonation d, double blackout) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        lastArcs = 0;
        puffs = 0;
        if (blackout > 0) Effects.add(new Emp(d, blackout, level));
        Effects.add(new Smoke(d));
    }

    // ---------------------------------------------------------------- ЭМИ

    /**
     * Поиск ламп по секциям у камеры, ближние первыми, и дуги: найденная лампа сразу получает свои разряды. Поиск по
     * всей прорисовке идёт дольше волны (глобальная палитра секции на «есть ли лампа» отвечает «может быть» всегда), и
     * лампа, найденная, когда волна у неё уже прошла или вышло {@link #ARC_WINDOW}, не коротит.
     */
    private static final class Emp implements Effects.Effect {
        private final Detonation d;
        private final RandomSource rnd;
        private final List<long[]> sections = new ArrayList<>();
        private final List<Arc> arcs = new ArrayList<>();
        private int next;

        Emp(Detonation d, double radius, ClientLevel level) {
            this.d = d;
            this.rnd = RandomSource.create(d.seed() ^ 0x5EED);
            // секции у камеры в кварталах, которые гасит сервер (квартал — по чанку, как у блэкаута): ближние первыми
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            int view = Minecraft.getInstance().options.getEffectiveRenderDistance();
            int ccx = Mth.floor(cam.x) >> 4, ccz = Mth.floor(cam.z) >> 4;
            for (int cz = ccz - view; cz <= ccz + view; cz++) {
                for (int cx = ccx - view; cx <= ccx + view; cx++) {
                    if (!Outage.covers(d.burst().x, d.burst().z, radius, Districts.of(cx, cz))) continue;
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) continue;
                    LevelChunkSection[] secs = chunk.getSections();
                    for (int i = 0; i < secs.length; i++) {
                        if (secs[i].hasOnlyAir() || !secs[i].getStates().maybeHas(FlashAftermath::lit)) continue;
                        sections.add(new long[]{cx, i, cz});
                    }
                }
            }
            sections.sort(Comparator.comparingDouble(s -> Math.hypot((s[0] << 4) + 8 - cam.x, (s[2] << 4) + 8 - cam.z)));
        }

        @Override
        public boolean tick(ClientLevel level, int t) {
            boolean searching = next < sections.size() && arcs.size() < MAX_ARCS && t < ARC_WINDOW;
            if (searching) scan(level, t);
            boolean left = searching;
            for (Arc a : arcs) {
                for (int i = 0; i < a.strikes.length; i++) {
                    // лампа ещё на месте (горит или уже погашена блэкаутом — это её и выбило), а не снесена
                    if (a.strikes[i] == t && lamp(level.getBlockState(a.pos))) arc(level, a, a.strength[i]);
                    if (a.strikes[i] > t) left = true;
                }
            }
            return left;
        }

        /** Секции за тик: горящие лампы в них — дуги, ближние к камере первыми, не больше {@link #MAX_ARCS} всего. */
        private void scan(ClientLevel level, int t) {
            List<BlockPos> found = new ArrayList<>();
            for (int n = 0; n < SECTIONS_PER_TICK && next < sections.size(); n++) {
                long[] s = sections.get(next++);
                LevelChunk chunk = level.getChunkSource().getChunkNow((int) s[0], (int) s[2]);
                if (chunk == null) continue;
                LevelChunkSection sec = chunk.getSections()[(int) s[1]];
                // точный подсчёт по палитре дешевле прохода по местам: без ламп секция дальше не смотрится
                if (!Palettes.contains(sec.getStates(), FlashAftermath::lit)) continue;
                int y0 = chunk.getMinBuildHeight() + ((int) s[1] << 4);
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (lit(sec.getBlockState(x, y, z))) found.add(new BlockPos(((int) s[0] << 4) + x, y0 + y, ((int) s[2] << 4) + z));
                        }
                    }
                }
            }
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            found.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(cam)));
            for (BlockPos pos : found) {
                if (arcs.size() >= MAX_ARCS) break;
                Arc a = schedule(pos, t);
                if (a != null) arcs.add(a);
            }
            lastArcs = arcs.size();
        }

        /**
         * Разряды лампы, найденной в тик {@code t}. Короткое замыкание — вразнобой, чаще сразу, до прихода волны к лампе
         * (после неё лампа в пыли и обломках) и не позже {@link #ARC_WINDOW}; в малом масштабе удара волна — за тики, и
         * лампе достаётся меньше разрядов или ни одного. Дуга не гаснет сразу: разряд проскакивает снова, пока цепь не
         * разомкнётся (2–4 раза через 1–3 тика); у части ламп замыкание повторяется позже, слабее. Null — уже поздно.
         */
        @Nullable
        private Arc schedule(BlockPos pos, int t) {
            int wave = (int) d.arrivalTicks(Math.hypot(pos.getX() + 0.5 - d.burst().x, pos.getZ() + 0.5 - d.burst().z));
            int last = Math.min(wave, ARC_WINDOW);
            if (t >= last) return null;
            float r = rnd.nextFloat();
            int at = t + (int) (r * r * (last - t));
            int again = rnd.nextFloat() < 0.4f ? at + 8 + rnd.nextInt(16) : -1;
            int first = 2 + rnd.nextInt(3), second = again < 0 ? 0 : 1 + rnd.nextInt(2);
            IntArrayList strikes = new IntArrayList(first + second);
            FloatArrayList strength = new FloatArrayList(first + second);
            for (int i = 0, when = at; i < first + second; i++) {
                boolean late = i >= first;
                when = i == 0 ? at : i == first ? again : when + 1 + rnd.nextInt(3);
                float s = (late ? 0.6f : 1) * (i == 0 || i == first ? 1 : 0.55f + 0.35f * rnd.nextFloat());
                if (when >= wave) continue;
                strikes.add(when);
                strength.add(s);
            }
            return new Arc(pos, strikes.toIntArray(), strength.toFloatArray());
        }

        private void arc(ClientLevel level, Arc a, float strength) {
            Vec3 at = Vec3.atCenterOf(a.pos).add(rnd.nextGaussian() * 0.15, 0.2 + rnd.nextGaussian() * 0.1, rnd.nextGaussian() * 0.15);
            // свечение — гладко: ближайшим пикселем крупная вспышка распадается на квадраты пикселей картинки
            Fx.flash().smooth().color(ARC, ARC_EDGE).size(1.4f * strength, 2.6f * strength).life(2 + rnd.nextInt(2)).spawn(level, at);
            Fx.flash().smooth().color(0xFFFFFF, ARC).size(0.5f, 0.9f).life(2).spawn(level, at);
            // сам разряд — ломаная из искр-звёздочек от лампы в сторону и вниз: светлое пятно на земле, которую вспышка
            // и так залила светом, днём почти не видно, мягкие точки — тоже, а звёздочки искр видны. Вниз по самому
            // столбу ломаную закрывали бы фонарь и столб
            double dir = rnd.nextDouble() * Math.PI * 2, ox = Math.cos(dir) * 0.24, oz = Math.sin(dir) * 0.24;
            Vec3 p = at;
            for (int seg = 0; seg < BOLT_SEGMENTS; seg++) {
                Vec3 q = p.add(ox + rnd.nextGaussian() * 0.09, -0.14 + rnd.nextGaussian() * 0.09, oz + rnd.nextGaussian() * 0.09);
                for (int k = 0; k < BOLT_DOTS; k++) {
                    Fx.spark().color(0xFFFFFF, 0x9FB4FF).size(0.11f * strength, 0.07f).life(2).spawn(level, p.lerp(q, (double) k / BOLT_DOTS));
                }
                p = q;
            }
            int n = (int) (6 * strength);
            for (int i = 0; i < n; i++) {
                Fx.spark().color(0xFFFFFF, 0x80A0FF).vel(rnd.nextGaussian() * 0.18, 0.05 + rnd.nextDouble() * 0.25, rnd.nextGaussian() * 0.18)
                        .life(8 + rnd.nextInt(14)).gravity(0.05f).spawn(level, at);
            }
        }
    }

    /** Лампа сети: тики разрядов дуги и их сила. */
    private static final class Arc {
        final BlockPos pos;
        final int[] strikes;
        final float[] strength;

        Arc(BlockPos pos, int[] strikes, float[] strength) {
            this.pos = pos;
            this.strikes = strikes;
            this.strength = strength;
        }
    }

    /** Горящая видимая лампа сети: её ЭМИ замыкает. */
    private static boolean lit(BlockState st) {
        return GridLights.isLit(st) && st.getRenderShape() == RenderShape.MODEL;
    }

    /** Видимая лампа сети, горящая или погашенная блэкаутом (её двойник): на месте, не снесена. */
    private static boolean lamp(BlockState st) {
        return (GridLights.isLit(st) || GridLights.isUnlit(st)) && st.getRenderShape() == RenderShape.MODEL;
    }

    // ---------------------------------------------------------------- дымки

    /**
     * Дымки вспышки — не горение, а пиролиз: свет выше порога обугливания ({@link #SMOKE_Q}, разброс {@link #SMOKE_BETA}
     * по {@link Fragility}) прогревает поверхность дерева, листвы и травы, и она выделяет летучие — белёсый дым смол,
     * которым курились дома в опытах в Неваде до прихода волны (Glasstone и Dolan 1977, §7.40–7.45). Без пламени: огонь
     * ставит сервер (слой вспышки). Пробы — {@link #SMOKE_PROBES} мест, равных по площади, в секторе перед камерой до
     * {@link #SMOKE_RANGE} блоков; у мест, что дымят, — выброс модели облака ({@link Puffs}):
     * <ul>
     * <li>летучих — α·(q − q₀)·S / L<sub>г</sub>: поглощение α = {@value #ABSORB} (О: дерево и листва 0,6–0,9), свет сверх
     * порога q₀ = {@link #SMOKE_Q} кал/см², S — площадь, которую представляет проба, теплота газификации древесины
     * L<sub>г</sub> = {@value #GASIFY} Дж/кг (Tewarson, SFPE Handbook, табл. 3-4.x: 1,8 МДж/кг у дугласовой пихты; ⚠ по памяти);</li>
     * <li>дыма из них — {@value #SMOKE_YIELD} кг на кг (О: выход аэрозоля тления древесины 0,01–0,1), ослабление
     * {@value #SMOKE_K} м²/кг (у дыма тления ~4,4 м²/г, Mulholland, SFPE Handbook; ⚠ по памяти), цвет — серо-бурый;</li>
     * <li>тепло в воздух — {@value #TO_AIR} поглощённого (О), газ ~{@value #SMOKE_K_GAS} K (пиролиз дерева 500–700 K).</li>
     * </ul>
     * Дымок виден, пока до места не дошла волна (её стена пыли сметает его).
     */
    static final int SMOKE_PROBES = 384;
    /** Проб за тик: вся раскладка — за первые {@link #SMOKE_PROBES} / столько тиков вспышки. */
    static final int SMOKE_PER_TICK = 24;
    static final double ABSORB = 0.7, GASIFY = 1.8e6, SMOKE_YIELD = 0.05, SMOKE_K = 4.4e3, TO_AIR = 0.1, SMOKE_K_GAS = 600;
    /** Джоулей на квадратный метр в одной калории на квадратный сантиметр. */
    static final double CAL_CM2 = 41_868;

    /** Пробы мест перед камерой: где свет прогрел поверхность сверх порога и волны ещё нет — выброс дыма пиролиза. */
    private static final class Smoke implements Effects.Effect {
        private final Detonation d;
        private final RandomSource rnd;
        private final double reach;
        private Puffs smoke;
        private int probed;

        Smoke(Detonation d) {
            this.d = d;
            this.rnd = RandomSource.create(d.seed() ^ 0x5307E);
            double lo = 0, hi = Math.max(1, d.ruinRadius());
            for (int i = 0; i < 30; i++) {
                double r = (lo + hi) / 2;
                if (Fragility.possible(d.fluence(d.burst().add(r, 0, 0)), SMOKE_Q, SMOKE_BETA)) lo = r;
                else hi = r;
            }
            this.reach = lo;
        }

        @Override
        public boolean tick(ClientLevel level, int t) {
            if (reach <= 0 || probed >= SMOKE_PROBES) {
                if (smoke != null) smoke.close();
                return false;
            }
            var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
            Vec3 cam = camera.getPosition();
            if (smoke == null) {
                smoke = new Puffs(FarBlasts.air(level, cam), 0.71f, 0.68f, 0.65f, d.seed());
                SmokeGrid.add(smoke);
            }
            double yaw = Math.toRadians(camera.getYRot()), sector = Math.toRadians(120), r0 = 4, r1 = r0 + SMOKE_RANGE;
            // проба — равная доля площади сектора
            double area = 0.5 * sector * (r1 * r1 - r0 * r0) / SMOKE_PROBES;
            double now = SmokeGrid.seconds(0);
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int k = 0; k < SMOKE_PER_TICK && probed < SMOKE_PROBES; k++, probed++) {
                double a = yaw + (rnd.nextDouble() - 0.5) * sector, r = Math.sqrt(r0 * r0 + rnd.nextDouble() * (r1 * r1 - r0 * r0));
                int x = Mth.floor(cam.x - Math.sin(a) * r), z = Mth.floor(cam.z + Math.cos(a) * r);
                double dist = Math.hypot(x + 0.5 - d.burst().x, z + 0.5 - d.burst().z);
                double wave = d.arrivalTicks(dist);
                if (dist > reach || t >= wave) continue;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                if (top <= level.getMinBuildHeight()) continue;
                Direction face = Direction.UP;
                int y = top;
                // половина проб — стена к шару под крышей
                if (rnd.nextBoolean()) {
                    face = Direction.getNearest(d.burst().x - x - 0.5, 0, d.burst().z - z - 0.5);
                    y = top - rnd.nextInt(8);
                }
                BlockState st = level.getBlockState(m.set(x, y, z));
                if (!burns(st, face)) continue;
                BlockState ahead = level.getBlockState(m.move(face));
                if (!ahead.isAir() && !ahead.canBeReplaced()) continue;
                double cos = FlashChar.incidence(d.burst(), x, y, z, face);
                if (cos <= 0) continue;
                Vec3 at = FlashChar.front(x, y, z, face);
                double q = d.fluence(at) * cos;
                if (!Fragility.possible(q, SMOKE_Q, SMOKE_BETA) || !ThermalShadow.visible(level, d.burst(), at)) continue;
                if (!Fragility.hit(q, SMOKE_Q, SMOKE_BETA, rnd.nextGaussian())) continue;
                double absorbed = ABSORB * q * CAL_CM2 * area, volatiles = ABSORB * Math.max(0, q - SMOKE_Q) * CAL_CM2 * area / GASIFY;
                smoke.add(at.x, at.y, at.z, now, TO_AIR * absorbed, SMOKE_K_GAS, 0.5 * Math.sqrt(area), 0, SMOKE_YIELD * volatiles, SMOKE_K,
                        now + (wave - t) / 20);
                puffs++;
            }
            return true;
        }

        private static boolean burns(BlockState st, Direction face) {
            if (st.isAir()) return false;
            if (FlashChar.material(st) == FlashChar.CHARS || st.is(BlockTags.LEAVES)) return true;
            return face == Direction.UP && (st.is(Blocks.GRASS_BLOCK) || st.is(BlockTags.REPLACEABLE_BY_TREES) || st.is(Blocks.HAY_BLOCK));
        }
    }
}
