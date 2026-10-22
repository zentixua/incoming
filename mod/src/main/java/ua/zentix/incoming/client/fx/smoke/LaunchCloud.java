package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.client.fx.plume.Motor;
import ua.zentix.incoming.nuclear.model.DustModel;

/**
 * Облако старта у пусковой: продукты двигателя, пока его струя бьёт в землю, и пыль, которую она поднимает, — одно тёплое
 * облако ({@link Thermal}, тот же закон, что у облака взрыва): всплывает, сносится ветром и расплывается, как любое
 * облако клиента (его рисуют запечённые клубы слоя эффектов, {@code far/SmokePuffs}; точки ленты шлейфа этих тиков
 * несут только столб самой струи между соплом и землёй, {@link #column}). Без Minecraft: место земли, грунт и воздух даёт вызывающий.
 * <p>
 * <b>Когда продукты — облако, а не след.</b> Струя — струя, пока её несёт импульс: на длине Мортона L_M = M^¾/F^½
 * (M = ṁ·u/ρ — поток импульса, F = g·Q̇/(ρ·c_p·T) — поток плавучести; Morton 1959, Fischer и др. «Mixing in Inland and
 * Coastal Waters», 1979, §9.1) импульс уступает плавучести. Если струя достаёт до земли раньше (её полуширина 0,094 длины —
 * Pope, «Turbulent Flows», 2000, табл. 5.1), она растекается по земле стенной струёй, и продукты остаются у пусковой —
 * облако; дальше — след ({@code TrailModel}). Так у наземного облака старта ракет и в модели REEDM (Bjorklund и др.,
 * NASA CR-3646, 1982: облако у земли — выхлоп, пока ракета ниже высоты, где облако встаёт; ⚠ по памяти), только мерило
 * — длина струи, а не высота: «Град» и шахед уходят под углом.
 * <p>
 * <b>Тепло</b> — тепло двигателя в воздухе ({@link Motor#heatFlow}), как у следа; <b>начало</b> — струя у земли: радиус —
 * её полуширина там, температура — избыток на оси, ΔT = ΔT₀·{@value #DECAY}·d′/ℓ (d′ = d·√(ρ_среза/ρ) — диаметр
 * переменной плотности, Chen и Rodi 1980; ⚠ постоянная спада 5 — для примеси в круглой струе, по памяти). Подача короче
 * подъёма (доли секунды–секунды против минут): облако — один термик всего тепла с начала подачи.
 * <p>
 * <b>Пыль</b> — ⚠ оценка порядка: {@value #DUST_PER_KG} кг пыли на кг выхлопа, ударившего в рыхлый грунт (у посадок
 * «Аполлонов» — тонны реголита на ~тонну топлива последних десятков секунд, Metzger и др., 2011), × рыхлость грунта
 * (пористость, как у звука по земле: камень и бетон — 0). Половина пыли встаёт с облаком, половина стелется у земли —
 * та же оценка, что у облака взрыва ({@link CloudModel#CLOUD_SHARE}); оседает корзинами {@link DustModel}.
 * <p>
 * <b>Пыль у земли</b> уносит стенная струя ({@link WallJet}): у косой струи («Град», шахед и ракета с наклонной
 * направляющей) — больше вперёд, по горизонтальной доле струи (Hasson–Peck), у отвесной — поровну во все стороны.
 * Клуб пыли у земли — гаусс, растянутый отрезком вдоль струи: его середина и разброс вдоль и поперёк — те же, что у пыли
 * затяжки по направлениям; дальше её расплывает воздух (Бриггс) и несёт ветер.
 */
public final class LaunchCloud implements SmokeGrid.Source {
    /** Полуширина круглой струи на длину (Pope 2000, табл. 5.1). */
    public static final double SPREAD = 0.094;
    /** Спад избытка температуры на оси струи: ΔT/ΔT₀ = DECAY·d′/x (⚠ Chen, Rodi 1980 — по памяти). */
    static final double DECAY = 5;
    /** Пыли на кг выхлопа, ударившего в рыхлый грунт, кг (⚠ оценка порядка). */
    public static final double DUST_PER_KG = 1;
    /** Спад скорости на оси круглой струи: u/u₀ = B·d′/x, B = 5,8 (Pope 2000, табл. 5.1; Hussein и др. 1994). */
    static final double JET_DECAY = 5.8;

    private final CloudModel.Air air;
    private final double groundY;
    private final float dustR, dustG, dustB;
    private final double loose;
    /** Часы сетки в начале подачи, с. */
    private final double born;
    /** Подано: тепло, Дж; сажа и капли продуктов, пыль, кг; место удара струи (с весом тепла), м; ось ΔT и радиус там. */
    private double heat, soot, water, dust, sumX, sumZ, sumT, sumR;
    /**
     * Импульс струи в землю за подачу, Н·с: весь и по осям мира (x, вниз, z); и Σ dI·τ (τ — миг подачи от начала облака,
     * с): ∫₀ᵗ I dt = I·t − Σ dI·τ — у затяжки ({@link WallJet#reach}).
     */
    private double impulse, pushX, pushDown, pushZ, pushMoment;
    /** Стенная струя по сборке ({@link #build}): «вперёд» (единичный, по горизонтали), азимуты равной доли и доля потока на радиан у каждого. */
    private double aheadX = 1, aheadZ;
    private double[] fanAzimuth = new double[0], fanShare = new double[0];
    private Thermal thermal;
    private double life;
    private final double[] matter = new double[2];
    private boolean gone;
    /** Тепло, с которым термик построен последний раз, и начало подачи — для пересборки ({@link #REBUILD}). */
    private double builtHeat;
    /** Часы сетки последней подачи, с: пока подача идёт, термик пересобирается только по {@link #REBUILD}. */
    private double lastFeed;

    /**
     * Термик пересобирается, когда тепло выросло с прошлой сборки в столько раз: высота ∝ Q^¼ — ошибка подъёма ≤ 6 %
     * (сборка — тысячи шагов РК4; у МБР подача — сотни тиков); после подачи — по всему теплу ({@link #emit}).
     */
    static final double REBUILD = 1.25;

    /**
     * @param groundY высота земли у пусковой
     * @param loose   рыхлость грунта 0..1 (пористость)
     * @param dustR   цвет грунта на экране (альбедо пыли — {@link SmokeGrid#dustAlbedo})
     * @param seconds часы сетки сейчас
     */
    public LaunchCloud(CloudModel.Air air, double groundY, double loose, float dustR, float dustG, float dustB, double seconds) {
        this.air = air;
        this.groundY = groundY;
        this.loose = Math.max(0, Math.min(1, loose));
        this.dustR = SmokeGrid.dustAlbedo(dustR);
        this.dustG = SmokeGrid.dustAlbedo(dustG);
        this.dustB = SmokeGrid.dustAlbedo(dustB);
        this.born = seconds;
    }

    /** Длина Мортона струи двигателя motor у земли, м. */
    public static double mortonLength(Motor motor) {
        double m = motor.exhaust() * motor.velocity() / CloudModel.RHO;
        double f = Thermal.buoyancy(motor.heatFlow());
        return f <= 0 ? Double.POSITIVE_INFINITY : Math.pow(m, 0.75) / Math.sqrt(f);
    }

    /**
     * Где струя из сопла в height метрах над землёй, бьющая по направлению с вертикальной долей jetY (−1 — отвесно вниз),
     * касается земли своей полушириной: путь по оси, м; бесконечность — не касается.
     */
    public static double reach(double height, double jetY) {
        double down = -jetY + SPREAD * Math.sqrt(Math.max(0, 1 - jetY * jetY));
        if (down <= 1e-6) return Double.POSITIVE_INFINITY;
        return Math.max(0, height) / down;
    }

    /** Струя двигателя бьёт в землю раньше, чем станет шлейфом: её продукты — облако старта, а не след. */
    public static boolean impinges(Motor motor, double height, double jetY) {
        return reach(height, jetY) <= mortonLength(motor);
    }

    /**
     * Ещё dt секунд работы двигателя motor с соплом в (x, y, z) и струёй по единичному (jx, jy, jz): бьёт в землю раньше
     * длины Мортона — продукты и пыль в облако, true; нет — false (это след).
     */
    public boolean feed(Motor motor, double dt, double x, double y, double z, double jx, double jy, double jz) {
        double l = reach(y - groundY, jy);
        if (!(l <= mortonLength(motor)) || dt <= 0) return false;
        double q = motor.heatFlow() * dt;
        if (motor.smoke() != null) {
            Matter.of(motor.smoke(), motor.smokeFlow() * dt, matter);
            soot += matter[0];
            water += matter[1];
        }
        dust += DUST_PER_KG * loose * motor.exhaust() * dt;
        double d = diameter(motor);
        double hot = motor.exitTemperature() - CloudModel.T_AIR;
        double dT = Math.max(1, hot * Math.min(1, DECAY * d / Math.max(l, 1e-6)));
        heat += q;
        double push = motor.exhaust() * motor.velocity() * dt;
        impulse += push;
        pushMoment += push * Math.max(0, SmokeGrid.seconds(0) - born);
        pushX += push * jx;
        pushDown -= push * jy;
        pushZ += push * jz;
        sumX += q * (x + jx * l);
        sumZ += q * (z + jz * l);
        sumT += q * dT;
        sumR += q * (SPREAD * l + motor.exitRadius());
        lastFeed = SmokeGrid.seconds(0);
        if (thermal == null || heat >= REBUILD * builtHeat) build();
        return true;
    }

    /** Диаметр переменной плотности d′ = d·√(ρ_среза/ρ) всех сопел вместе, м (Chen, Rodi 1980; Thring, Newby 1953). */
    static double diameter(Motor motor) {
        return 2 * motor.exitRadius() * Math.sqrt(motor.nozzles() * motor.exitDensity() / CloudModel.RHO);
    }

    /**
     * Доля вещества следа, которую держит столб самой струи в x метрах за соплом, пока струя бьёт в землю. Продукты этих
     * тиков уходят в облако у земли, но не прыжком: между соплом и землёй их несёт струя — это и есть столб от ракеты
     * до облака, который виден на старте (без него на кадре старта сзади под ракетой было пусто, пока струя доставала
     * до земли, — пусто до ~38 м высоты у ускорителя ракеты). Вещество в струе идёт со скоростью её оси
     * u(x) = u₀·B·d′/x (не быстрее среза), на метр столба его ṁ/u(x); след, положенный снарядом, несёт ṁ·inverse
     * (inverse — секунды на метр пути снаряда, {@code TrailModel.inverseSpeed}). Доля — 1/(u(x)·inverse), не больше 1.
     * Скорость струи — против воздуха, без скорости снаряда (на старте она мала против сотен м/с струи).
     *
     * @param x           путь по оси струи от сопла, м (путь, который снаряд пролетел с тех пор, как был в этой точке)
     * @param inverseSpeed секунды на метр следа, с которыми точка положена
     */
    public static double column(Motor motor, double x, double inverseSpeed) {
        double u = motor.velocity() * Math.min(1, JET_DECAY * diameter(motor) / Math.max(x, 1e-6));
        return Math.min(1, 1 / Math.max(u * inverseSpeed, 1e-12));
    }

    /** Термик по всему поданному теплу. */
    private void build() {
        double t0 = CloudModel.T_AIR + sumT / heat;
        thermal = new Thermal(Thermal.buoyancy(heat), CloudModel.G * (1 - CloudModel.T_AIR / t0), Math.max(0.5, sumR / heat), air);
        builtHeat = heat;
        life = lifeSeconds();
        fan();
    }

    /**
     * Стенная струя по всему поданному импульсу: угол к земле — у суммы импульсов струи (вектор), «вперёд» — её
     * горизонтальная доля; азимуты равной доли потока и импульс на радиан в каждом ({@link WallJet}).
     */
    private void fan() {
        double flat = Math.hypot(pushX, pushZ), all = Math.hypot(flat, pushDown);
        if (impulse <= 0 || all <= 0) {
            fanAzimuth = new double[0];
            fanShare = new double[0];
            return;
        }
        if (flat > 1e-9 * all) {
            aheadX = pushX / flat;
            aheadZ = pushZ / flat;
        }
        double sinA = Math.max(0, pushDown) / all;
        fanAzimuth = WallJet.azimuths(sinA, WallJet.DIRECTIONS);
        // доля на радиан у каждого направления; импульс — по мигу (fanMoments)
        fanShare = new double[fanAzimuth.length];
        for (int k = 0; k < fanAzimuth.length; k++) fanShare[k] = WallJet.share(sinA, fanAzimuth[k]);
    }

    /**
     * Пыль стенной струи через t секунд: середина от места удара вдоль «вперёд» и поперёк, разброс вдоль и поперёк (σ²),
     * средний радиус, м — по {@link WallJet#DIRECTIONS} направлениям равной доли, у каждого — затяжка до её фронта
     * (масса ∝ r′²: середина ¾ r, второй момент ⅗ r²); r0 — радиус струи у земли, wind — ветер у земли, м/с.
     */
    double[] fanMoments(double t, double r0, double wind) {
        int n = fanAzimuth.length;
        double[] out = new double[5];
        if (n == 0) {
            out[2] = out[3] = 0.6 * r0 * r0;
            out[4] = 0.75 * r0;
            return out;
        }
        double mx = 0, my = 0, xx = 0, yy = 0, mr = 0, integral = Math.max(0, impulse * t - pushMoment);
        for (int k = 0; k < n; k++) {
            double w = fanShare[k];
            double r = WallJet.reach(impulse * w, integral * w, r0, wind), c = Math.cos(fanAzimuth[k]), s = Math.sin(fanAzimuth[k]);
            mx += 0.75 * r * c;
            my += 0.75 * r * s;
            xx += 0.6 * r * r * c * c;
            yy += 0.6 * r * r * s * s;
            mr += 0.75 * r;
        }
        mx /= n;
        my /= n;
        out[0] = mx;
        out[1] = my;
        out[2] = Math.max(0, xx / n - mx * mx);
        out[3] = Math.max(0, yy / n - my * my);
        out[4] = mr / n;
        return out;
    }

    /** Зерно клубов — по месту и началу подачи (у двух облаков рядом — разные клубы). */
    @Override
    public long seed() {
        return Double.doubleToLongBits(born) * 31 + Double.doubleToLongBits(groundY);
    }

    /** Во сколько раз облако выросло с начала (σ по горизонтали) — кадр запечённого клуба. */
    @Override
    public double puffGrowth(double seconds) {
        if (thermal == null) return 1;
        double t = Math.max(0, seconds - born);
        return thermal.sigmaH(t) / Math.max(thermal.sigmaH(0), 1e-6);
    }

    /** Облако что-то получило. */
    public boolean fed() {
        return thermal != null;
    }

    /** Подъём облака (null — не подано). */
    public Thermal thermal() {
        return thermal;
    }

    public double heat() {
        return heat;
    }

    /** Сажа, капли и пыль, кг. */
    public double soot() {
        return soot;
    }

    public double water() {
        return water;
    }

    public double dust() {
        return dust;
    }

    /** Сколько облако видно, с (по порогу толщи, как у облака взрыва). */
    public double life() {
        return life;
    }

    /** Толща поперёк середины облака в возрасте t (сырые ослабления продуктов и поднятой пыли). */
    double tau(double t) {
        double ext = CloudModel.SOOT_K * soot + CloudModel.DROP_K * water
                + DustModel.K_MIX * CloudModel.CLOUD_SHARE * dust * DustModel.aloft(t, thermal.height(t));
        return ext / (2 * Math.PI * thermal.sigmaH(t) * thermal.sigmaV(t));
    }

    private double lifeSeconds() {
        double lo = 0, hi = CloudModel.LIFE_MAX;
        if (tau(hi) >= CloudModel.SEEN) return hi;
        for (int i = 0; i < 40; i++) {
            double mid = 0.5 * (lo + hi);
            if (tau(mid) >= CloudModel.SEEN) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** Подачи больше не будет и облако растаяло — или мир сменился ({@link #end}). */
    @Override
    public boolean ended() {
        return gone || thermal != null && SmokeGrid.seconds(0) - born >= life;
    }

    /** Облако больше не рисуется (смена мира, перемотка). */
    public void end() {
        gone = true;
    }

    /**
     * Клубы: облако (продукты и доля пыли, термик) и пыль у земли — стенная струя растекается по земле и дальше
     * расплывается по Бриггсу (как пыль у земли облака взрыва), оседая корзинами.
     */
    @Override
    public boolean emit(double seconds, SmokeGrid.Sink out) {
        if (gone) return false;
        if (thermal == null) return true;
        // подача кончилась (два тика без неё) — термик по всему теплу
        if (heat > builtHeat && seconds - lastFeed > 0.1) build();
        double t = Math.max(0, seconds - born);
        if (t >= life) return false;
        // облако, что дожило до предела густым, уходит за последнюю десятую жизни (как облако взрыва)
        double fade = life < CloudModel.LIFE_MAX ? 1 : Math.min(1, (life - t) / (0.1 * life));
        double cx = sumX / heat + thermal.driftX(t), cz = sumZ / heat + thermal.driftZ(t), cy = groundY + thermal.height(t);
        double cloudDust = CloudModel.CLOUD_SHARE * dust * DustModel.aloft(t, thermal.height(t)) * fade;
        out.blob(cx, cy, cz, thermal.sigmaH(t), thermal.sigmaV(t), soot * fade, cloudDust, water * fade, dustR, dustG, dustB,
                thermal.kelvin(t), thermal.rise(t));
        double ground2 = Math.hypot(air.windX(), air.windZ()) * air.wind(2);
        double x = Math.max(1, ground2) * t;
        double s0 = thermal.r0 / Math.sqrt(5);
        double[] fan = fanMoments(t, sumR / heat, ground2);
        // поперёк — разброс затяжки и Бриггса; вдоль — ещё растяжка отрезком: гаусс σ поверх отрезка ±A даёт σ² + A²/3
        double bh2 = Math.pow(CloudModel.spreadH(air.spread(), x), 2), along = Math.sqrt(3 * Math.max(0, fan[2] - fan[3]));
        double bh = Math.max(s0, Math.sqrt(fan[3] + bh2));
        // толщина стенной струи у середины её пыли — S·r на половине скорости (гаусс: полувысота 1,18σ)
        double bv = Math.max(s0, Math.hypot(CloudModel.spreadV(air.spread(), x), WallJet.SPREAD * fan[4] / 1.18));
        double ground = (1 - CloudModel.CLOUD_SHARE) * dust * DustModel.aloft(t, 2 * bv) * fade;
        double lean = air.wind(2) * t;
        double gx = sumX / heat + aheadX * fan[0] - aheadZ * fan[1] + air.windX() * lean;
        double gz = sumZ / heat + aheadZ * fan[0] + aheadX * fan[1] + air.windZ() * lean;
        out.segment(gx, groundY + bv, gz, aheadX * along, 0, aheadZ * along, bh, bv, 0, ground, 0, dustR, dustG, dustB, CloudModel.T_AIR, 0);
        return true;
    }
}
