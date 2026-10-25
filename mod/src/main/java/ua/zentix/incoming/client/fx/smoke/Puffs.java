package ua.zentix.incoming.client.fx.smoke;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Дым короткими выбросами — источник облака клиента (рисуют запечённые клубы слоя эффектов, {@code far/SmokePuffs}):
 * каждый выброс — тёплый клуб одним термиком ({@link Thermal}, тот же закон MTT, что у облака взрыва и облака старта):
 * всплывает своим теплом, сносится ветром, расплывается и тает, пока толща поперёк его середины больше
 * {@link CloudModel#SEEN}. Так рисуется дым того, что горит или тлеет недолго и мелко для шлейфа Бриггса: горящий
 * обломок в полёте (выброс за каждый отрезок пути), дымки поверхностей, подожжённых ядерной вспышкой.
 * <p>
 * Вещество выброса — сажа (чёрная, {@link CloudModel#SOOT_K}) или светлый аэрозоль тления: у него своё ослабление на
 * массу и альбедо, в клуб он идёт пылью с тем же ослаблением ({@link SmokeGrid#dustK}).
 * <p>
 * Термики одной плавучести и начального радиуса (с точностью до {@link #BIN}) — один и тот же расчёт: подъём
 * интегрируется тысячами шагов, а выбросов — сотни.
 */
public final class Puffs implements SmokeGrid.Source {
    /** Плавучесть и радиус термиков одного расчёта совпадают с точностью до множителя (ошибка высоты ∝ B^¼ — до 4 %). */
    static final double BIN = Math.sqrt(2);
    /**
     * Кэш термиков: воздух — до шага, который подъёма не меняет (N — 0,001 с⁻¹, ветер — 0,5 м/с), плавучесть и радиус —
     * до {@link #BIN}; последние {@value #CACHED} расчётов. Воздух у каждого источника свой объект ({@code FarBlasts.air}
     * по времени суток), а расчёт — тысячи шагов: по одному объекту кэш сбрасывался бы на каждом выбросе.
     */
    static final int CACHED = 64;
    private static final Map<String, Thermal> CACHE = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Thermal> e) {
            return size() > CACHED;
        }
    };

    private record Puff(double x, double y, double z, double born, double until, Thermal thermal, double soot, double dust,
                        double life) {}

    private final CloudModel.Air air;
    private final float dustR, dustG, dustB;
    private final long seed;
    private final List<Puff> puffs = new ArrayList<>();
    /** Новых выбросов больше не будет (источник иссяк или ушёл из мира). */
    private boolean closed;
    /** Часы сетки последнего выброса: без новых дольше {@link #QUIET} источник иссяк сам (обломок упал или исчез). */
    private double lastFed = Double.NaN;
    static final double QUIET = 1;
    private boolean gone;

    /**
     * @param air    воздух на месте
     * @param albedo цвет аэрозоля тления на экране (сажа — своя, чёрная): альбедо — {@link SmokeGrid#dustAlbedo}
     */
    public Puffs(CloudModel.Air air, float albedoR, float albedoG, float albedoB, long seed) {
        this.air = air;
        this.dustR = SmokeGrid.dustAlbedo(albedoR);
        this.dustG = SmokeGrid.dustAlbedo(albedoG);
        this.dustB = SmokeGrid.dustAlbedo(albedoB);
        this.seed = seed;
    }

    /** Термик плавучести тепла {@code heat}, Дж, с начальной температурой {@code kelvin} и радиусом не меньше {@code r0}. */
    static Thermal thermal(CloudModel.Air air, double heat, double kelvin, double r0) {
        long kb = Math.round(Math.log(Math.max(heat, 1)) / Math.log(BIN)), kr = Math.round(Math.log(Math.max(r0, 0.05)) / Math.log(BIN));
        long kt = Math.round(kelvin / 50);
        String key = kb + "/" + kr + "/" + kt + "/" + Math.round(air.n() * 1000) + "/" + Math.round(air.windX() * 2) + "/" + Math.round(air.windZ() * 2)
                + "/" + Math.round(air.shear() * 100) + "/" + air.spread() + "/" + air.site().key();
        return CACHE.computeIfAbsent(key, k -> new Thermal(Thermal.buoyancy(Math.pow(BIN, kb)), CloudModel.G * (1 - CloudModel.T_AIR / Math.max(kt * 50.0, CloudModel.T_AIR + 1)),
                Math.pow(BIN, kr), air));
    }

    /**
     * Выброс в (x, y, z) в миг {@code seconds} (часы сетки): тепло {@code heat}, Дж, газ температуры {@code kelvin} в шаре не
     * меньше {@code r0} м; сажи {@code soot}, кг; аэрозоля тления {@code aerosol}, кг, с ослаблением {@code k} м²/кг; виден не
     * дольше, чем до {@code until} (часы сетки; волна ядерки сметает дымки) — NaN без срока.
     */
    public void add(double x, double y, double z, double seconds, double heat, double kelvin, double r0, double soot, double aerosol, double k,
                    double until) {
        if (closed || gone) return;
        lastFed = seconds;
        Thermal th = thermal(air, heat, kelvin, r0);
        double dust = aerosol * k / SmokeGrid.dustK(dustR, dustG, dustB);
        double life = life(th, soot, dust);
        if (life <= 0) return;
        puffs.add(new Puff(x, y, z, seconds, until, th, soot, dust, life));
    }

    /** Сколько выброс видно, с: пока толща поперёк середины больше порога ({@link CloudModel#SEEN}). */
    private double life(Thermal th, double soot, double dust) {
        double ext = CloudModel.SOOT_K * soot + SmokeGrid.dustK(dustR, dustG, dustB) * dust;
        if (tau(th, ext, 0) < CloudModel.SEEN) return 0;
        double lo = 0, hi = CloudModel.LIFE_MAX;
        if (tau(th, ext, hi) >= CloudModel.SEEN) return hi;
        for (int i = 0; i < 30; i++) {
            double mid = 0.5 * (lo + hi);
            if (tau(th, ext, mid) >= CloudModel.SEEN) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    private static double tau(Thermal th, double ext, double t) {
        return ext / (2 * Math.PI * th.sigmaH(t) * th.sigmaV(t));
    }

    /** Новых выбросов не будет: источник уходит, когда растают уже выпущенные. */
    public void close() {
        closed = true;
    }

    /** Смена мира, перемотка: дым пропадает сразу. */
    public void end() {
        gone = true;
        puffs.clear();
    }

    /** Живых выбросов. */
    public int size() {
        return puffs.size();
    }

    @Override
    public boolean emit(double seconds, SmokeGrid.Sink out) {
        if (gone) return false;
        if (!Double.isNaN(lastFed) && seconds - lastFed > QUIET) closed = true;
        puffs.removeIf(p -> seconds - p.born >= p.life || !Double.isNaN(p.until) && seconds >= p.until);
        for (Puff p : puffs) {
            double t = Math.max(0, seconds - p.born);
            Thermal th = p.thermal;
            // выброс, что дожил до предела густым, тает за последнюю десятую жизни (как облако взрыва)
            double fade = p.life < CloudModel.LIFE_MAX ? 1 : Math.min(1, (p.life - t) / (0.1 * p.life));
            out.blob(p.x + th.driftX(t), p.y + th.height(t), p.z + th.driftZ(t), th.sigmaH(t), th.sigmaV(t), p.soot * fade, p.dust * fade, 0,
                    dustR, dustG, dustB, th.kelvin(t), th.rise(t));
        }
        return !puffs.isEmpty() || !closed;
    }

    @Override
    public boolean ended() {
        return gone || closed && puffs.isEmpty();
    }

    @Override
    public long seed() {
        return seed;
    }

    /** Кадр клуба — по росту самого молодого выброса (у старых клубы уже крупные, кадр у них не меняется заметно). */
    @Override
    public double puffGrowth(double seconds) {
        if (puffs.isEmpty()) return 2;
        Puff p = puffs.getLast();
        Thermal th = p.thermal;
        return th.sigmaH(Math.max(0, seconds - p.born)) / Math.max(th.sigmaH(0), 1e-6);
    }
}
