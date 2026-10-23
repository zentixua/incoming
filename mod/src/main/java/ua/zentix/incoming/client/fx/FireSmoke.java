package ua.zentix.incoming.client.fx;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.client.far.FarSprites;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.layer.FxLights;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.grid.SubstationBlock;

import java.util.Locale;

/**
 * Дым над тем, что горит, — облако клиента (его рисуют запечённые клубы слоя эффектов, {@code far/SmokePuffs}): огонь, который видит клиент ({@link FireWatch}: блоки огня в своих чанках,
 * окно выборок), собран в очаги по чанкам (16 блоков; у ядерного шторма — клетки 32, {@code FireStorm}): у очага — шлейф
 * Бриггса ({@link FirePlume}) по ветру мира и устойчивости воздуха ({@link ClientAir}), кусками трубки
 * ({@link SmokeGrid.Sink#tube}, {@link #lay}: рисуются как след снаряда); свет пламени снизу — шаром в свободные места света сетки ({@link #lights}). Откуда
 * огонь — дело сервера (поджиг и горение не трогаются): дым идёт за тем, что горит. Дым, что уже в воздухе, несёт силу
 * очага в миг выхода: место на пути x по ветру — от очага каким он был x/u секунд назад (история очага раз в
 * {@link #PERIOD} тиков; ветер — нынешний на весь шлейф). Так шлейф растёт от очага со скоростью ветра, а когда очаг
 * слабеет или гаснет, у очага дым тоньше, а ушедший — прежний и уходит по ветру.
 * <p>
 * Очаги рядом — один пожар ({@link #merge}): дом на стыке чанков горит одним столбом, а не двумя-четырьмя слабыми
 * (подъём шлейфа — по всему теплу, ∝ F^⅓ у пути и F^⅗ у конечного; половина тепла даёт столб ниже на 20–35 %). Каждый
 * очаг несёт свою сажу, а поднимается шлейфом всего пожара. Пакета нет: огонь клиент видит сам. Пожары горящего ядерного подрыва — не здесь: их дым — огненный шторм ({@code FireStorm}).
 */
public final class FireSmoke implements SmokeGrid.Source {
    /**
     * Кусков трубки шлейфа: гуще у очага, где ось круче (места — квадратично по пути); одни на любой дальности от глаза
     * (прежние 4 дальше 256 м с 12 клубами на кусок клали клубы реже их размера — шлейф вдали шёл бусами).
     */
    static final int SEGMENTS = 10;
    /** Очаги пересматриваются раз в столько тиков (окно огня меняется не быстрее обхода чанков). */
    static final int PERIOD = 20;
    /** Шлейф — не дальше по ветру, м (0,75 полустороны последнего каскада сетки: ~0,8 км). */
    public static final double REACH = 0.75 * 0.5 * SmokeGrid.N * SmokeGrid.CELL[SmokeGrid.CASCADES - 1];

    private static final Long2ObjectOpenHashMap<FireSmoke> FIRES = new Long2ObjectOpenHashMap<>();
    /** Горящее масло выбитых подстанций по месту блока ({@link #substation}). */
    private static final Long2ObjectOpenHashMap<FireSmoke> OIL_FIRES = new Long2ObjectOpenHashMap<>();
    /** Топливо в воронках взрывов ({@link #pool}). */
    private static final java.util.List<FireSmoke> POOLS = new java.util.ArrayList<>();

    /**
     * Что горит: тепловыделение и площадь очага на единицу силы очага (у огня Minecraft единица — блок огня), сажи на
     * джоуль.
     */
    public record Fuel(double watts, double area, double sootPerJoule) {
        /** Шлейф очага силы {@code units} в ветре {@code wind} и классе устойчивости. */
        FirePlume plume(double units, double wind, double stability) {
            return plume(units, units, wind, stability);
        }

        /**
         * Шлейф очага силы {@code units} в пожаре силы {@code fire} (в тех же единицах этого топлива, {@link #merge}):
         * поднимается и расплывается шлейф всего пожара, сажа — только своя.
         */
        FirePlume plume(double units, double fire, double wind, double stability) {
            double all = Math.max(fire, units);
            return new FirePlume(all * watts, Math.sqrt(all * area / Math.PI), all > 0 ? sootPerJoule * units / all : sootPerJoule, wind, stability);
        }
    }

    /** Огонь Minecraft: блок огня — грань ~1 м² горящей древесины ({@link FirePlume#BLOCK_WATTS}, {@link FirePlume#SOOT_PER_JOULE}). */
    public static final Fuel WOOD = new Fuel(FirePlume.BLOCK_WATTS, 1, FirePlume.SOOT_PER_JOULE);
    /**
     * Масло трансформатора выбитой подстанции, разлитое и горящее лужей: минеральное масло — выгорание 0,039 кг/(м²·с)
     * (Babrauskas, SFPE Handbook, 5-е изд., табл. «Pool fire burning rates», трансформаторное масло; ⚠ по памяти),
     * эффективная теплота сгорания ~46 МДж/кг (как у дизельного топлива; ⚠ по памяти), выход сажи ~0,06 кг/кг (О:
     * между керосином 0,012 и тяжёлыми маслами ~0,1, Tewarson); лужа ~{@value #OIL_AREA} м² (О: масло подстанционного
     * трансформатора — сотни литров, обвалование вокруг). Тепло — 3,6 МВт, пламя по Хескестаду ~4,5 м; масла — на часы
     * горения, поэтому очаг горит, пока подстанция выбита.
     */
    public static final double OIL_AREA = 2;
    public static final Fuel OIL = new Fuel(0.039 * OIL_AREA * 46e6, OIL_AREA, 0.06 / 46e6);
    /** Мир очагов: сменился (портал) — прежние шлейфы уходят сразу. */
    private static ClientLevel world;
    private static int clock;
    private static final double[] BB = new double[4];
    private static final Vector4f COLUMN = new Vector4f();
    private static final double[] WEIGHT = new double[4];

    /** История очага: столько записей (раз в секунду) — дольше дым до края сетки не идёт и при ветре 1 м/с. */
    static final int HISTORY = 1024;

    /** Что горит. */
    final Fuel fuel;
    /** Доля топлива в воронке, что горит, по возрасту очага (null — не воронка). */
    @org.jetbrains.annotations.Nullable
    java.util.function.DoubleUnaryOperator burning;
    /**
     * Место очага, сила (блоков огня или очагов топлива), сила всего пожара, куда он входит (в единицах его топлива,
     * {@link #merge}), начало и конец горения (часы сетки, с; конец — NaN, пока горит); пересмотр.
     */
    double x, y, z, blocks, fire;
    final double start;
    double end = Double.NaN;
    int seen;
    /** Мир сменился: источник уходит из сетки. */
    boolean gone;
    /**
     * Шлейф нынешнего очага в нынешнем ветре и воздухе (подъём — по теплу всего пожара, {@link #merge}) и шлейф одного
     * его тепла (горячий газ и пламя над очагом); направление ветра.
     */
    FirePlume plume, own;
    double wx, wz;
    /** Докуда по ветру виден шлейф самого сильного очага за историю, м; высота пламени самого очага, м. */
    double visible, flame;
    /** История очага и его пожара; самый сильный пожар за неё. */
    private final ua.zentix.incoming.client.fx.smoke.History history = new ua.zentix.incoming.client.fx.smoke.History(HISTORY),
            fires = new ua.zentix.incoming.client.fx.smoke.History(HISTORY);
    private double strongest;

    private FireSmoke(Fuel fuel, double x, double y, double z, double blocks, double seconds) {
        this.fuel = fuel;
        this.x = x;
        this.y = y;
        this.z = z;
        this.blocks = blocks;
        this.fire = blocks;
        this.start = seconds;
    }

    /** Запись истории: в seconds горело blocks блоков в пожаре силы fire. */
    private void record(double seconds, double blocks, double fire) {
        history.record(seconds, blocks);
        fires.record(seconds, Math.max(fire, blocks));
        strongest = Math.max(strongest, Math.max(fire, blocks));
    }

    /** Блоков огня в миг seconds (до начала и после конца — 0). */
    double blocksAt(double seconds) {
        if (seconds < start || !Double.isNaN(end) && seconds >= end) return 0;
        return history.at(seconds);
    }

    /** Сила пожара очага в миг seconds (в единицах его топлива). */
    double fireAt(double seconds) {
        return fires.at(seconds);
    }

    /** Ветер у очага, м/с. */
    private double wind() {
        return 20 * Math.hypot(ClientAir.windX(y), ClientAir.windZ(y));
    }

    /** Шлейф по нынешнему ветру на высоте очага и устойчивости. */
    private void air() {
        double ux = 20 * ClientAir.windX(y), uz = 20 * ClientAir.windZ(y), u = Math.hypot(ux, uz);
        wx = u > 1e-6 ? ux / u : 1;
        wz = u > 1e-6 ? uz / u : 0;
        plume = fuel.plume(blocks, fire, u, ClientAir.now().stability());
        // пламя и горячий газ над очагом — у каждого очага свои (свет и дрожание от пожара — сумма очагов, а не очаги ×
        // пламя всего пожара)
        own = fuel.plume(blocks, u, ClientAir.now().stability());
        flame = own.flameHeight();
        visible = fuel.plume(strongest, u, ClientAir.now().stability()).visible(REACH);
    }

    /**
     * Очаги, чьи шлейфы сливаются, — один пожар: шлейф, согнутый ветром, — радиусом ~0,6 своего подъёма (вовлечение
     * β = 0,6, Briggs, «Plume Rise Predictions», 1975), и два шлейфа сходятся, когда сумма радиусов на конечном подъёме
     * больше расстояния между очагами: d ≤ 0,6·(Δh₁ + Δh₂) (⚠ оценка слияния по касанию). Пожар — связная группа (по
     * цепочке); сила каждого очага в группе — тепло всей группы в единицах его топлива.
     */
    static void merge(java.util.List<FireSmoke> all) {
        int n = all.size();
        if (n == 0) return;
        double[] x = new double[n], z = new double[n], watts = new double[n], rise = new double[n];
        double stability = ClientAir.now().stability();
        for (int i = 0; i < n; i++) {
            FireSmoke f = all.get(i);
            x[i] = f.x;
            z[i] = f.z;
            watts[i] = f.blocks * f.fuel.watts;
            rise[i] = f.fuel.plume(f.blocks, f.wind(), stability).finalRise;
        }
        double[] fire = fires(x, z, watts, rise);
        for (int i = 0; i < n; i++) all.get(i).fire = fire[i] / all.get(i).fuel.watts;
    }

    /**
     * Тепло пожара каждого очага, Вт: очаги на (x, z) с теплом {@code watts} и конечным подъёмом своего шлейфа
     * {@code rise} сливаются, когда d ≤ 0,6·(Δh₁ + Δh₂) ({@link #merge}), по цепочке.
     */
    static double[] fires(double[] x, double[] z, double[] watts, double[] rise) {
        int n = x.length;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (Math.hypot(x[i] - x[j], z[i] - z[j]) <= 0.6 * (rise[i] + rise[j])) parent[root(parent, i)] = root(parent, j);
            }
        }
        double[] sum = new double[n], out = new double[n];
        for (int i = 0; i < n; i++) sum[root(parent, i)] += watts[i];
        for (int i = 0; i < n; i++) out[i] = sum[root(parent, i)];
        return out;
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) i = parent[i] = parent[parent[i]];
        return i;
    }

    /** Раз в тик клиента после {@link FireWatch#tick}: очаги по окну огня (раз в {@link #PERIOD}). */
    public static void tick(ClientLevel level) {
        if (level != world) {
            reset();
            world = level;
        }
        if (++clock % PERIOD != 0) return;
        double now = SmokeGrid.seconds(0);
        int pass = clock;
        FireWatch.forEachGroup((chunk, fx, fy, fz, n) -> {
            // пожары ядерного подрыва дымят огненным штормом ({@code FireStorm}) — не здесь
            if (ua.zentix.incoming.client.nuclear.ClientNuclear.stormFire(fx, fz)) return;
            FireSmoke f = FIRES.get(chunk);
            if (f == null || !Double.isNaN(f.end)) {
                f = new FireSmoke(WOOD, fx, fy, fz, n, now);
                FIRES.put(chunk, f);
                SmokeGrid.add(f);
            } else {
                f.x = fx;
                f.y = fy;
                f.z = fz;
                f.blocks = n;
            }
            f.seen = pass;
        });
        for (var it = FIRES.values().iterator(); it.hasNext(); ) {
            FireSmoke f = it.next();
            if (f.seen == pass) continue;
            // погас: шлейф отпущен и уходит по ветру
            if (Double.isNaN(f.end)) f.end = now;
            it.remove();
        }
        for (var it = OIL_FIRES.long2ObjectEntrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            FireSmoke f = e.getValue();
            BlockPos at = BlockPos.of(e.getLongKey());
            // чанк у клиента — смотрим блок: подстанция снова под током или снесена — масло больше не горит
            if (level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4) != null && !SubstationBlock.out(level.getBlockState(at))) {
                f.end = now;
                it.remove();
                continue;
            }
        }
        for (var it = POOLS.iterator(); it.hasNext(); ) {
            FireSmoke f = it.next();
            double share = f.burning.applyAsDouble(now - f.start);
            f.blocks = share;
            if (share <= 0 && now > f.start) {
                f.end = now;
                it.remove();
            }
        }
        // очаги рядом — один пожар; потом — запись и шлейф по нему
        java.util.List<FireSmoke> burning = new java.util.ArrayList<>(FIRES.values());
        burning.addAll(OIL_FIRES.values());
        burning.addAll(POOLS);
        merge(burning);
        for (FireSmoke f : burning) {
            f.record(now, f.blocks, f.fire);
            f.air();
        }
    }

    /**
     * Подстанция выбита ({@code GridEffects.failure}; у вошедшего позже — по случайному тику блока рядом): над ней
     * горит масло трансформатора ({@link #OIL}), пока она не под током.
     */
    public static void substation(ClientLevel level, BlockPos pos) {
        if (level != world) {
            reset();
            world = level;
        }
        long key = pos.asLong();
        if (OIL_FIRES.containsKey(key)) return;
        double now = SmokeGrid.seconds(0);
        FireSmoke f = new FireSmoke(OIL, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1, now);
        f.record(now, 1, 1);
        f.air();
        OIL_FIRES.put(key, f);
        SmokeGrid.add(f);
    }

    /**
     * Топливо в воронке взрыва {@code model} у точки (x, y, z) ({@code CloudModel#poolSeconds}): горит лужей с теплом
     * {@code poolWatts} и сажей топлива, долей {@code poolShare} по возрасту, — очаг среди пожаров клиента: его шлейф
     * сливается с огнём, что поджёг шар топлива рядом ({@link #merge}), его пламя светит на дым и мир.
     */
    public static void pool(ClientLevel level, double x, double y, double z, CloudModel model) {
        if (level != world) {
            reset();
            world = level;
        }
        double now = SmokeGrid.seconds(0);
        Fuel fuel = new Fuel(model.poolWatts, Math.PI * model.poolRadius * model.poolRadius,
                model.source.fuel().sootYield / model.source.fuel().heatJPerKg);
        FireSmoke f = new FireSmoke(fuel, x, y, z, 1, now);
        f.burning = model::poolShare;
        f.record(now, 1, 1);
        f.air();
        POOLS.add(f);
        SmokeGrid.add(f);
    }

    /** Смена мира, перемотка повтора: очагов нет (источники сетки снимает её сброс). */
    public static void reset() {
        for (FireSmoke f : FIRES.values()) f.gone = true;
        for (FireSmoke f : OIL_FIRES.values()) f.gone = true;
        for (FireSmoke f : POOLS) f.gone = true;
        FIRES.clear();
        OIL_FIRES.clear();
        POOLS.clear();
        world = null;
    }

    /**
     * Шлейф в момент seconds: от очага до пути, что дым прошёл с начала горения, и не дальше, чем виден шлейф самого
     * сильного очага ({@link #lay}).
     */
    @Override
    public boolean emit(double seconds, SmokeGrid.Sink out) {
        if (gone) return false;
        double u = plume.wind, stability = plume.stability;
        double far = Math.min(visible, u * (seconds - start));
        if (!Double.isNaN(end) && u * (seconds - end) >= far) return false;
        if (far <= 0) return true;
        lay(fuel, x, y, z, wx, wz, u, stability, far, plume.size, path -> blocksAt(seconds - path / u), path -> fireAt(seconds - path / u), out);
        return true;
    }

    /**
     * Шлейф очага (x, y, z) топлива fuel по ветру (wx, wz) скорости u до пути far — кусками трубки ({@link SmokeGrid.Sink#tube})
     * между местами оси на пути xₖ (квадратично: у очага ось круче), одними на любой дальности от глаза: сколько квадратов
     * на кусок, решает тот, кто рисует, по размеру на экране (вдали — лента). Место оси на пути x — по очагу в миг выхода
     * этого дыма (x/u секунд назад, blocks и fire — сила очага и его пожара по пути): подъём и σ его шлейфа, сажа на метр —
     * его; где тогда не горело, сажи нет: кусок кончается там, где дым кончается (граница — по истории очага), пустые куски
     * тоже отдаются (без сажи). sigma0 — σ у самого очага.
     */
    public static void lay(Fuel fuel, double x, double y, double z, double wx, double wz, double u, double stability, double far, double sigma0,
                    java.util.function.DoubleUnaryOperator blocks, java.util.function.DoubleUnaryOperator fire, SmokeGrid.Sink out) {
        int n = SEGMENTS + 1;
        double[] path = new double[n], b = new double[n];
        for (int k = 0; k < n; k++) {
            double f = (double) k / SEGMENTS;
            path[k] = far * f * f;
            b[k] = blocks.applyAsDouble(path[k]);
        }
        FirePlume[] plume = new FirePlume[n];
        for (int k = 0; k < n; k++) if (b[k] > 0) plume[k] = fuel.plume(b[k], fire.applyAsDouble(path[k]), u, stability);
        // где не горело — без сажи, с геометрией ближайшего места, что горело (вещество сходит к нулю вдоль куска)
        for (int k = 0; k < n; k++) {
            if (plume[k] != null) continue;
            for (int j = 1; j < n && plume[k] == null; j++) {
                if (k - j >= 0 && b[k - j] > 0) plume[k] = plume[k - j];
                else if (k + j < n && b[k + j] > 0) plume[k] = plume[k + j];
            }
            if (plume[k] == null) return;
        }
        sigma0 = Math.max(sigma0, 0.25);
        for (int k = 1; k < n; k++) {
            boolean burnA = b[k - 1] > 0, burnB = b[k] > 0;
            // кусок отдаётся всегда (пустой — без сажи): номер куска — зерно его клубов у приёмника, и выпавший кусок не
            // сдвигал бы клубы всего шлейфа дальше по ветру
            if (!burnA && !burnB) {
                empty(out, x, y, z, wx, wz, path[k - 1], path[k], plume[k - 1], plume[k], sigma0);
                continue;
            }
            double xa = path[k - 1], xb = path[k];
            FirePlume pa = plume[k - 1], pb = plume[k];
            // край дыма (очаг загорелся или погас посреди куска) — граница по истории очага: кусок от неё, вещество до
            // самого края полное — край идёт по ветру ровно, а не рампой от узла к узлу со щелчком, когда проходит узел
            if (burnA != burnB) {
                double lo = xa, hi = xb;
                for (int i = 0; i < 24; i++) {
                    double m = 0.5 * (lo + hi);
                    if (blocks.applyAsDouble(m) > 0 == burnA) lo = m;
                    else hi = m;
                }
                if (burnA) {
                    xb = lo;
                    pb = pa;
                } else {
                    xa = hi;
                    pa = pb;
                }
            }
            // толща через середину на обоих концах ниже порога видимости — куска не видно
            if (Math.max(pa.tau(xa), pb.tau(xb)) < CloudModel.SEEN) {
                empty(out, x, y, z, wx, wz, xa, xb, pa, pb, sigma0);
                continue;
            }
            double ha = pa.rise(xa), hb = pb.rise(xb), mid = 0.5 * (xa + xb);
            // сажа шлейфа — ṁ/u на метр пути по ветру; на метр оси куска — меньше во столько, во сколько ось длиннее пути
            // (у очага, где шлейф круто поднимается)
            double dx = xb - xa, len = Math.sqrt(dx * dx + (hb - ha) * (hb - ha)), along = len > 0 ? dx / len : 1;
            double kelvin = CloudModel.T_AIR_K + pb.excess(0.5 * (ha + hb));
            out.tube(x + wx * xa, y + ha, z + wz * xa, pa.sigmaY(xa), pa.sigmaZ(xa), pa.sootPerMetre() * along, 0, x + wx * xb, y + hb,
                    z + wz * xb, pb.sigmaY(xb), pb.sigmaZ(xb), pb.sootPerMetre() * along, 0, 1, 1, 1, kelvin, pb.riseSpeed(mid), sigma0);
        }
    }

    /** Кусок шлейфа без сажи (там не горело или его не видно): только номер куска у приёмника. */
    private static void empty(SmokeGrid.Sink out, double x, double y, double z, double wx, double wz, double xa, double xb, FirePlume pa,
                              FirePlume pb, double sigma0) {
        out.tube(x + wx * xa, y + pa.rise(xa), z + wz * xa, pa.sigmaY(xa), pa.sigmaZ(xa), 0, 0, x + wx * xb, y + pb.rise(xb), z + wz * xb,
                pb.sigmaY(xb), pb.sigmaZ(xb), 0, 0, 1, 1, 1, CloudModel.T_AIR_K, 0, sigma0);
    }

    @Override
    public boolean ended() {
        return gone || !Double.isNaN(end) && SmokeGrid.seconds(0) - end > REACH / plume.wind;
    }

    @Override
    public long seed() {
        return Double.doubleToLongBits(start) * 31 + Double.doubleToLongBits(x) * 17 + Double.doubleToLongBits(z);
    }

    /**
     * Пламя очагов светит на дым снизу: в свободные места света сетки (занятые — шарами взрывов и ядерки), самые заметные
     * (яркость × R²/d²) — шар видимого пламени ({@link FirePlume#flameLight}) радиусом в полвысоты пламени над очагом; свет — линейный
     * × exposure, в w — air.
     */
    public static void lights(Vec3 eye, double exposure, double air, Matrix4f centres, Matrix4f light) {
        if (FIRES.isEmpty() && OIL_FIRES.isEmpty() && POOLS.isEmpty()) return;
        FirePlume.flameLight(BB);
        Vector4f column = COLUMN;
        double[] weight = WEIGHT;
        for (int i = 0; i < 4; i++) {
            centres.getColumn(i, column);
            weight[i] = column.w > 0 ? Double.MAX_VALUE : 0;
        }
        for (FireSmoke f : all()) {
            double r = 0.5 * f.flame;
            double dx = f.x - eye.x, dy = f.y + r - eye.y, dz = f.z - eye.z;
            double w = BB[3] * r * r / Math.max(dx * dx + dy * dy + dz * dz, r * r);
            int slot = 0;
            for (int i = 1; i < 4; i++) if (weight[i] < weight[slot]) slot = i;
            if (w <= weight[slot]) continue;
            weight[slot] = w;
            centres.setColumn(slot, column.set((float) dx, (float) dy, (float) dz, (float) r));
            light.setColumn(slot, column.set((float) (BB[0] * exposure), (float) (BB[1] * exposure), (float) (BB[2] * exposure), (float) air));
        }
    }

    /** Все очаги: огонь Minecraft, масло подстанций и топливо в воронках. */
    private static Iterable<FireSmoke> all() {
        return () -> java.util.stream.Stream.concat(java.util.stream.Stream.concat(FIRES.values().stream(), OIL_FIRES.values().stream()),
                POOLS.stream()).iterator();
    }

    /** Огонь очага на экране (доля белого): свет пламени вокруг не ярче его (как у лужи топлива, {@code FarBlasts}). */
    static final double FLAME_ON_SCREEN = 0.8;

    /**
     * Пламя очагов светит на частицы, клубы и ленты вокруг ({@link FxLights}): шар видимого пламени
     * ({@link FirePlume#flameLight}) радиусом в полвысоты пламени над очагом, с привыканием глаза кадра. Ночью без него дым над
     * пожаром освещала бы одна луна.
     */
    public static void lights(FarView view, FxLights lights) {
        if (FIRES.isEmpty() && OIL_FIRES.isEmpty() && POOLS.isEmpty()) return;
        FirePlume.flameLight(BB);
        double m = Math.max(BB[0], Math.max(BB[1], BB[2]));
        Vec3 cam = view.camera();
        for (FireSmoke f : all()) {
            if (!Double.isNaN(f.end)) continue;
            double r = 0.5 * f.flame;
            lights.add(f.x - cam.x, f.y + r - cam.y, f.z - cam.z, r, BB[3] * view.exposure(), FLAME_ON_SCREEN,
                    (float) (BB[0] / m), (float) (BB[1] / m), (float) (BB[2] / m));
        }
    }

    /**
     * Пламя очагов топлива (лужа в воронке, масло подстанции) там, где его языков-частиц уже нет: с 0,8 прорисовки они
     * тают ({@code FxFrame.near}), и в 300 м ночью от пожара оставался только свет на земле — серая точка. Тело — круг
     * площади пламени сбоку (высота по Хескестаду × диаметр очага), свет и цвет — те же, что у языков и у света пламени
     * на дыме ({@link FirePlume#FLAME_LUMINANCE}, {@link FirePlume#FLAME_COLOR}, с привыканием глаза кадра), блик и вуаль —
     * как у огненного шара и факела ({@link FarSprites#light}). Огонь Minecraft вдали рисует сам мир.
     */
    public static void far(FarView view, FarSprites out) {
        if (OIL_FIRES.isEmpty() && POOLS.isEmpty()) return;
        Vec3 cam = view.camera();
        int c = FirePlume.FLAME_COLOR;
        float r = (c >> 16 & 255) / 255f, g = (c >> 8 & 255) / 255f, b = (c & 255) / 255f;
        for (FireSmoke f : (Iterable<FireSmoke>) () -> java.util.stream.Stream.concat(OIL_FIRES.values().stream(), POOLS.stream()).iterator()) {
            if (!Double.isNaN(f.end) || f.gone) continue;
            if (f.blocks <= 0 || f.flame <= 0) continue;
            // пламя сбоку: высота по Хескестаду (по нынешней силе очага: у лужи — доля, что ещё горит) × диаметр очага (у
            // масла — всех его очагов), круг той же площади — на половине высоты
            double area = f.burning == null ? f.blocks * f.fuel.area : f.fuel.area;
            double across = 2 * Math.sqrt(area / Math.PI), radius = Math.sqrt(f.flame * across / Math.PI);
            double dx = f.x - cam.x, dy = f.y + 0.5 * f.flame - cam.y, dz = f.z - cam.z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz), w = view.farShare(d), t = view.air(d, dy);
            // ядро сменяет языки-частицы с 0,8 прорисовки; блик и вуаль — на любой дальности, как у факела и шара
            out.light(dx, dy, dz, radius, FirePlume.FLAME_LUMINANCE * view.exposure() * t, t, view.pixel(), r, g, b, w, 1, FAR_LIGHT);
        }
    }

    private static final double[] FAR_LIGHT = new double[5];

    /**
     * Свет и горячий газ очагов на мир кадра ({@code HeatPass}): горячий газ над каждым горящим очагом (столб по
     * Хескестаду), свет — у масла подстанций и топлива в воронках: огонь Minecraft светит на мир сам, светом блоков.
     */
    public static void heat(Vec3 eye, ua.zentix.incoming.client.fx.layer.HeatPass.Frame f, double air) {
        if (FIRES.isEmpty() && OIL_FIRES.isEmpty() && POOLS.isEmpty()) return;
        FirePlume.flameLight(BB);
        for (FireSmoke fire : all()) {
            if (!Double.isNaN(fire.end) || fire.gone) continue;
            double dx = fire.x - eye.x, dy = fire.y - eye.y, dz = fire.z - eye.z;
            f.plume(dx, dy, dz, fire.own, 1);
            if (fire.fuel != WOOD) {
                double r = 0.5 * fire.flame;
                f.light(dx, dy + r, dz, r, BB[0], BB[1], BB[2], air, r);
            }
        }
    }

    /** Для строки {@code SCENARIO}: очагов, блоков огня в них, самый большой — подъём и докуда виден шлейф. */
    public static String describe() {
        double all = 0;
        FireSmoke big = null;
        for (FireSmoke f : FIRES.values()) {
            all += f.blocks;
            if (big == null || f.blocks > big.blocks) big = f;
        }
        FireSmoke first = OIL_FIRES.isEmpty() ? null : OIL_FIRES.values().iterator().next();
        String oil = first == null ? "" : String.format(Locale.ROOT, " oil %d (%.1f MW, flame %.1f, rise %.0f)", OIL_FIRES.size(),
                OIL.watts() / 1e6, first.flame, first.plume.finalRise);
        if (!POOLS.isEmpty()) {
            FireSmoke p = POOLS.get(POOLS.size() - 1);
            oil += String.format(Locale.ROOT, " pools %d (%.1f MW, fire %.1f MW, rise %.0f)", POOLS.size(), p.blocks * p.fuel.watts() / 1e6,
                    p.fire * p.fuel.watts() / 1e6, p.plume.finalRise);
        }
        if (big == null) return "fires 0" + oil;
        return String.format(Locale.ROOT, "fires %d blocks %.0f biggest %.0f (fire %.1f MW) rise %.0f visible %.0f wind %.1f class %.1f", FIRES.size(), all,
                big.blocks, big.fire * big.fuel.watts() / 1e6, big.plume.finalRise, big.visible, big.plume.wind, big.plume.stability) + oil;
    }
}
