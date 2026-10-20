package ua.zentix.incoming.client.fx;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.nuclear.model.BlackBody;

import java.util.Locale;

/**
 * Дым над тем, что горит, — объём сетки дыма: огонь, который видит клиент ({@link FireWatch}: блоки огня в своих чанках,
 * окно выборок), собран в очаги по чанкам (16 блоков; у ядерного шторма — клетки 32, {@code FireStorm}): у очага — шлейф
 * Бриггса ({@link FirePlume}) по ветру мира и устойчивости воздуха ({@link ClientAir}), отрезками сетки
 * ({@link SmokeGrid.Sink#segment}); свет пламени снизу — шаром в свободные места света сетки ({@link #lights}). Откуда
 * огонь — дело сервера (поджиг и горение не трогаются): дым идёт за тем, что горит. Шлейф растёт от очага со скоростью
 * ветра, а погасший очаг отпускает его: шлейф уходит по ветру и тает, как ушёл бы настоящий дым. Пакета нет: огонь
 * клиент видит сам.
 */
public final class FireSmoke implements SmokeGrid.Source {
    /** Отрезков шлейфа: гуще у очага, где ось круче (места — квадратично по пути). */
    static final int SEGMENTS = 10;
    /** Очаги пересматриваются раз в столько тиков (окно огня меняется не быстрее обхода чанков). */
    static final int PERIOD = 20;
    /** Шлейф — не дальше по ветру, м (дальше сетки дыма — 0,75 полустороны последнего каскада). */
    static final double REACH = 0.75 * 0.5 * SmokeGrid.N * SmokeGrid.CELL[SmokeGrid.CASCADES - 1];

    private static final Long2ObjectOpenHashMap<FireSmoke> FIRES = new Long2ObjectOpenHashMap<>();
    private static int clock;
    private static final double[] BB = new double[4];
    private static final Vector4f COLUMN = new Vector4f();
    private static final double[] WEIGHT = new double[4];

    /** Место очага, блоков огня, начало и конец горения (часы сетки, с; конец — NaN, пока горит); пересмотр. */
    double x, y, z, blocks;
    final double start;
    double end = Double.NaN;
    int seen;
    /** Шлейф в нынешнем ветре и воздухе; направление ветра. */
    FirePlume plume;
    double wx, wz;
    /** Докуда по ветру шлейф виден, м. */
    double visible;

    private FireSmoke(double x, double y, double z, double blocks, double seconds) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.blocks = blocks;
        this.start = seconds;
        air();
    }

    /** Шлейф по нынешнему ветру на высоте очага и устойчивости. */
    private void air() {
        double ux = 20 * ClientAir.windX(y), uz = 20 * ClientAir.windZ(y), u = Math.hypot(ux, uz);
        wx = u > 1e-6 ? ux / u : 1;
        wz = u > 1e-6 ? uz / u : 0;
        plume = new FirePlume(blocks, u, ClientAir.now().stability());
        visible = plume.visible(REACH);
    }

    /** Раз в тик клиента после {@link FireWatch#tick}: очаги по окну огня (раз в {@link #PERIOD}). */
    public static void tick(ClientLevel level) {
        if (++clock % PERIOD != 0) return;
        double now = SmokeGrid.seconds(0);
        int pass = clock;
        FireWatch.forEachGroup((chunk, fx, fy, fz, n) -> {
            FireSmoke f = FIRES.get(chunk);
            if (f == null || !Double.isNaN(f.end)) {
                f = new FireSmoke(fx, fy, fz, n, now);
                FIRES.put(chunk, f);
                SmokeGrid.add(f);
            } else {
                f.x = fx;
                f.y = fy;
                f.z = fz;
                f.blocks = n;
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
    }

    /** Смена мира, перемотка повтора: очагов нет (источники сетки снимает её сброс). */
    public static void reset() {
        FIRES.clear();
    }

    /**
     * Шлейф в момент seconds: от очага до пути, что дым прошёл с начала горения, и не дальше, чем он виден; у погасшего —
     * с пути, что ветер унёс его хвост. Отрезки — между местами оси на пути xₖ (квадратично), масса — сажа на метр × путь.
     */
    @Override
    public boolean emit(double seconds, SmokeGrid.Sink out) {
        FirePlume p = plume;
        double age = seconds - start;
        double far = Math.min(visible, p.wind * age);
        double near = Double.isNaN(end) ? 0 : p.wind * (seconds - end);
        if (near >= far) return Double.isNaN(end);
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double lambda = p.sootPerMetre();
        double prev = near;
        for (int k = 1; k <= SEGMENTS; k++) {
            double u = (double) k / SEGMENTS, xk = near + (far - near) * u * u;
            double ax = 0.5 * (xk - prev), mid = 0.5 * (xk + prev);
            double h0 = p.rise(prev), h1 = p.rise(xk);
            double cx = x + wx * mid, cy = y + 0.5 * (h0 + h1), cz = z + wz * mid;
            double share = SmokeGrid.volumeShare(cx - eye.x, cy - eye.y, cz - eye.z);
            prev = xk;
            if (share <= 0) continue;
            double kelvin = CloudModel.T_AIR_K + p.excess(0.5 * (h0 + h1));
            out.segment(cx, cy, cz, wx * ax, 0.5 * (h1 - h0), wz * ax, p.sigmaY(mid), p.sigmaZ(mid), lambda * 2 * ax * share, 0, 0, 1, 1, 1,
                    kelvin, p.riseSpeed(mid));
        }
        return true;
    }

    @Override
    public boolean ended() {
        return !Double.isNaN(end) && SmokeGrid.seconds(0) - end > REACH / plume.wind;
    }

    /**
     * Пламя очагов светит на дым снизу: в свободные места света сетки (занятые — шарами взрывов и ядерки), самые заметные
     * (яркость × R²/d²) — шар чёрного тела {@link FirePlume#FLAME_K} радиусом в полвысоты пламени над очагом; свет — линейный
     * × exposure, в w — air.
     */
    public static void lights(Vec3 eye, double exposure, double air, Matrix4f centres, Matrix4f light) {
        if (FIRES.isEmpty()) return;
        BlackBody.light(FirePlume.FLAME_K, BB);
        Vector4f column = COLUMN;
        double[] weight = WEIGHT;
        for (int i = 0; i < 4; i++) {
            centres.getColumn(i, column);
            weight[i] = column.w > 0 ? Double.MAX_VALUE : 0;
        }
        for (FireSmoke f : FIRES.values()) {
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

    /** Для строки {@code SCENARIO}: очагов, блоков огня в них, самый большой — подъём и докуда виден шлейф. */
    public static String describe() {
        double all = 0;
        FireSmoke big = null;
        for (FireSmoke f : FIRES.values()) {
            all += f.blocks;
            if (big == null || f.blocks > big.blocks) big = f;
        }
        if (big == null) return "fires 0";
        return String.format(Locale.ROOT, "fires %d blocks %.0f biggest %.0f rise %.0f visible %.0f wind %.1f class %.1f", FIRES.size(), all,
                big.blocks, big.plume.finalRise, big.visible, big.plume.wind, big.plume.stability);
    }
}
