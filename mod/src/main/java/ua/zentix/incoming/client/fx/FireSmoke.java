package ua.zentix.incoming.client.fx;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.layer.FxLights;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.grid.SubstationBlock;
import ua.zentix.incoming.nuclear.model.BlackBody;

import java.util.Locale;

/**
 * Дым над тем, что горит, — облако клиента (его рисуют запечённые клубы слоя эффектов, {@code far/SmokePuffs}): огонь, который видит клиент ({@link FireWatch}: блоки огня в своих чанках,
 * окно выборок), собран в очаги по чанкам (16 блоков; у ядерного шторма — клетки 32, {@code FireStorm}): у очага — шлейф
 * Бриггса ({@link FirePlume}) по ветру мира и устойчивости воздуха ({@link ClientAir}), отрезками сетки
 * ({@link SmokeGrid.Sink#segment}); свет пламени снизу — шаром в свободные места света сетки ({@link #lights}). Откуда
 * огонь — дело сервера (поджиг и горение не трогаются): дым идёт за тем, что горит. Дым, что уже в воздухе, несёт силу
 * очага в миг выхода: место на пути x по ветру — от очага каким он был x/u секунд назад (история очага раз в
 * {@link #PERIOD} тиков; ветер — нынешний на весь шлейф). Так шлейф растёт от очага со скоростью ветра, а когда очаг
 * слабеет или гаснет, у очага дым тоньше, а ушедший — прежний и уходит по ветру. Пакета нет: огонь клиент видит сам. Пожары горящего ядерного подрыва — не здесь: их дым — огненный шторм ({@code FireStorm}).
 */
public final class FireSmoke implements SmokeGrid.Source {
    /** Отрезков шлейфа: гуще у очага, где ось круче (места — квадратично по пути). */
    static final int SEGMENTS = 10;
    /** Очаги пересматриваются раз в столько тиков (окно огня меняется не быстрее обхода чанков). */
    static final int PERIOD = 20;
    /** Шлейф — не дальше по ветру, м (0,75 полустороны последнего каскада сетки: ~0,8 км). */
    static final double REACH = 0.75 * 0.5 * SmokeGrid.N * SmokeGrid.CELL[SmokeGrid.CASCADES - 1];

    private static final Long2ObjectOpenHashMap<FireSmoke> FIRES = new Long2ObjectOpenHashMap<>();
    /** Горящее масло выбитых подстанций по месту блока ({@link #substation}). */
    private static final Long2ObjectOpenHashMap<FireSmoke> OIL_FIRES = new Long2ObjectOpenHashMap<>();

    /**
     * Что горит: тепловыделение и площадь очага на единицу силы очага (у огня Minecraft единица — блок огня), сажи на
     * джоуль.
     */
    public record Fuel(double watts, double area, double sootPerJoule) {
        /** Шлейф очага силы {@code units} в ветре {@code wind} и классе устойчивости. */
        FirePlume plume(double units, double wind, double stability) {
            return new FirePlume(units * watts, Math.sqrt(units * area / Math.PI), sootPerJoule, wind, stability);
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
    /** Дальше этого от глаза у шлейфа меньше отрезков ({@link #FAR_SEGMENTS}): на экране он мельче, а места в сетке общие. */
    static final double FAR = 256;
    static final int FAR_SEGMENTS = 4;
    private static int clock;
    private static final double[] BB = new double[4];
    private static final Vector4f COLUMN = new Vector4f();
    private static final double[] WEIGHT = new double[4];

    /** История очага: столько записей (раз в секунду) — дольше дым до края сетки не идёт и при ветре 1 м/с. */
    static final int HISTORY = 1024;

    /** Что горит. */
    final Fuel fuel;
    /** Место очага, сила (блоков огня или очагов топлива), начало и конец горения (часы сетки, с; конец — NaN, пока горит); пересмотр. */
    double x, y, z, blocks;
    final double start;
    double end = Double.NaN;
    int seen;
    /** Мир сменился: источник уходит из сетки. */
    boolean gone;
    /** Шлейф нынешнего очага в нынешнем ветре и воздухе; направление ветра. */
    FirePlume plume;
    double wx, wz;
    /** Докуда по ветру виден шлейф самого сильного очага за историю, м. */
    double visible;
    /** История очага и самый сильный очаг за неё. */
    private final ua.zentix.incoming.client.fx.smoke.History history = new ua.zentix.incoming.client.fx.smoke.History(HISTORY);
    private double strongest;

    private FireSmoke(Fuel fuel, double x, double y, double z, double blocks, double seconds) {
        this.fuel = fuel;
        this.x = x;
        this.y = y;
        this.z = z;
        this.blocks = blocks;
        this.start = seconds;
        record(seconds, blocks);
        air();
    }

    /** Запись истории: в seconds горело blocks блоков. */
    private void record(double seconds, double blocks) {
        history.record(seconds, blocks);
        strongest = Math.max(strongest, blocks);
    }

    /** Блоков огня в миг seconds (до начала и после конца — 0). */
    double blocksAt(double seconds) {
        if (seconds < start || !Double.isNaN(end) && seconds >= end) return 0;
        return history.at(seconds);
    }

    /** Шлейф по нынешнему ветру на высоте очага и устойчивости. */
    private void air() {
        double ux = 20 * ClientAir.windX(y), uz = 20 * ClientAir.windZ(y), u = Math.hypot(ux, uz);
        wx = u > 1e-6 ? ux / u : 1;
        wz = u > 1e-6 ? uz / u : 0;
        plume = fuel.plume(blocks, u, ClientAir.now().stability());
        visible = fuel.plume(strongest, u, ClientAir.now().stability()).visible(REACH);
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
                f.record(now, n);
                f.air();
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
            f.record(now, 1);
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
        FireSmoke f = new FireSmoke(OIL, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1, SmokeGrid.seconds(0));
        OIL_FIRES.put(key, f);
        SmokeGrid.add(f);
    }

    /** Смена мира, перемотка повтора: очагов нет (источники сетки снимает её сброс). */
    public static void reset() {
        for (FireSmoke f : FIRES.values()) f.gone = true;
        for (FireSmoke f : OIL_FIRES.values()) f.gone = true;
        FIRES.clear();
        OIL_FIRES.clear();
        world = null;
    }

    /**
     * Шлейф в момент seconds: от очага до пути, что дым прошёл с начала горения, и не дальше, чем виден шлейф самого
     * сильного очага. Место оси на пути x — по очагу в миг выхода этого дыма (x/u секунд назад): подъём и σ его шлейфа,
     * сажа на метр — его; где тогда не горело, дыма нет. Отрезки — между местами оси на пути xₖ (квадратично: у очага ось
     * круче).
     */
    @Override
    public boolean emit(double seconds, SmokeGrid.Sink out) {
        if (gone) return false;
        double u = plume.wind, stability = plume.stability;
        double far = Math.min(visible, u * (seconds - start));
        if (!Double.isNaN(end) && u * (seconds - end) >= far) return false;
        if (far <= 0) return true;
        double ex = SmokeGrid.eyeX(), ez = SmokeGrid.eyeZ();
        int segments = (x - ex) * (x - ex) + (z - ez) * (z - ez) > FAR * FAR ? FAR_SEGMENTS : SEGMENTS;
        double prevX = 0, prevB = blocksAt(seconds), prevH = prevB > 0 ? fuel.plume(prevB, u, stability).rise(0) : 0;
        for (int k = 1; k <= segments; k++) {
            double f = (double) k / segments, xk = far * f * f, mid = 0.5 * (xk + prevX);
            double bk = blocksAt(seconds - xk / u), bm = blocksAt(seconds - mid / u);
            double hk = bk > 0 ? fuel.plume(bk, u, stability).rise(xk) : prevH;
            double x0 = prevX, h0 = prevH, b0 = prevB;
            prevX = xk;
            prevH = hk;
            prevB = bk;
            if (bm <= 0 || b0 <= 0 && bk <= 0) continue;
            FirePlume p = fuel.plume(bm, u, stability);
            double ax = 0.5 * (xk - x0);
            double cx = x + wx * mid, cy = y + 0.5 * (h0 + hk), cz = z + wz * mid;
            if (p.tau(mid) < CloudModel.SEEN) continue;
            double kelvin = CloudModel.T_AIR_K + p.excess(0.5 * (h0 + hk));
            out.segment(cx, cy, cz, wx * ax, 0.5 * (hk - h0), wz * ax, p.sigmaY(mid), p.sigmaZ(mid), p.sootPerMetre() * 2 * ax, 0, 0,
                    1, 1, 1, kelvin, p.riseSpeed(mid));
        }
        return true;
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
     * (яркость × R²/d²) — шар чёрного тела {@link FirePlume#FLAME_K} радиусом в полвысоты пламени над очагом; свет — линейный
     * × exposure, в w — air.
     */
    public static void lights(Vec3 eye, double exposure, double air, Matrix4f centres, Matrix4f light) {
        if (FIRES.isEmpty() && OIL_FIRES.isEmpty()) return;
        BlackBody.light(FirePlume.FLAME_K, BB);
        Vector4f column = COLUMN;
        double[] weight = WEIGHT;
        for (int i = 0; i < 4; i++) {
            centres.getColumn(i, column);
            weight[i] = column.w > 0 ? Double.MAX_VALUE : 0;
        }
        for (FireSmoke f : all()) {
            double r = 0.5 * f.plume.flameHeight();
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

    /** Все очаги: огонь Minecraft и масло подстанций. */
    private static Iterable<FireSmoke> all() {
        return () -> java.util.stream.Stream.concat(FIRES.values().stream(), OIL_FIRES.values().stream()).iterator();
    }

    /** Огонь очага на экране (доля белого): свет пламени вокруг не ярче его (как у лужи топлива, {@code FarBlasts}). */
    static final double FLAME_ON_SCREEN = 0.8;

    /**
     * Пламя очагов светит на частицы, клубы и ленты вокруг ({@link FxLights}): шар чёрного тела
     * {@link FirePlume#FLAME_K} радиусом в полвысоты пламени над очагом, с привыканием глаза кадра. Ночью без него дым над
     * пожаром освещала бы одна луна.
     */
    public static void lights(FarView view, FxLights lights) {
        if (FIRES.isEmpty() && OIL_FIRES.isEmpty()) return;
        BlackBody.light(FirePlume.FLAME_K, BB);
        double m = Math.max(BB[0], Math.max(BB[1], BB[2]));
        Vec3 cam = view.camera();
        for (FireSmoke f : all()) {
            if (!Double.isNaN(f.end)) continue;
            double r = 0.5 * f.plume.flameHeight();
            lights.add(f.x - cam.x, f.y + r - cam.y, f.z - cam.z, r, Sight.adapted(BB[3], view.ambient()), FLAME_ON_SCREEN,
                    (float) (BB[0] / m), (float) (BB[1] / m), (float) (BB[2] / m));
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
                OIL.watts() / 1e6, first.plume.flameHeight(), first.plume.finalRise);
        if (big == null) return "fires 0" + oil;
        return String.format(Locale.ROOT, "fires %d blocks %.0f biggest %.0f rise %.0f visible %.0f wind %.1f class %.1f", FIRES.size(), all,
                big.blocks, big.plume.finalRise, big.visible, big.plume.wind, big.plume.stability) + oil;
    }
}
