package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.ThermalModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Где и сколько горит после подрыва — модель клиента для звука горящего города и вторичных взрывов (сам огонь ставит
 * сервер, клиент видит только блоки огня в своих чанках). Кольца вокруг эпицентра шириной {@link #RING}: в кольце
 * свет зажигает долю колонок по той же вероятности, что у сервера ({@code RuinPlanner.fire}: min(1, q/20), q — свет на
 * земле, к краю руин след света сходит на нет), на {@link #SHARE} колонок есть чему гореть. Огонь встаёт со вспышкой
 * (слой вспышки сервера, {@code FlashScorch}: место — когда свет там набрал порог поджига, {@link Detonation#flashTicks}),
 * волна с её приходом сбивает от 2 psi половину ({@link #keep}). Предел пожаров подрыва у сервера ({@link #MAX_FIRES})
 * — на то, что горит после волны ({@code RuinPlan.write}): в счёт идёт только огонь, который волна не собьёт, а слой
 * вспышки зажигает, пока таких меньше предела, и сбиваемый — без счёта. Модель режет кольца от эпицентра тем же счётом,
 * что у слоя: зажигает столько же и до того же кольца (до волны — и то, что она собьёт: у эпицентра от 2 psi вдвое
 * больше предела), а с волной оставляет то, что оставил бы слой без руин. Руин модель не знает — это её известный
 * предел: огонь слоя в счёте, который руины гасят (завал, обвал), уходит из счёта сервера, и руины дальше кольца, где
 * слой упёрся в предел и не зажёг ничего, ставят свои пожары. В городе после волны у сервера горит и за последним
 * кольцом модели (у 15 кт в воздухе — дальше ~146 блоков), а у модели там пусто; рядом с игроком это поправляют
 * {@link FireWatch} (настоящий огонь своих чанков) и {@code NukeAfter.trust}, вдали — нет. Сколько горит потом —
 * {@link #envelope}: разгорается {@link #RISE} тиков от
 * поджига, потом гаснет со временем {@link #TAU}. Что на деле горит рядом, клиент сверяет по своим чанкам
 * ({@link FireWatch}).
 */
final class FireField {
    /** Ширина кольца, блоков. */
    static final double RING = 32;
    /** Доля колонок, где огню есть на чём встать (трава, земля, дерево — не камень и не вода). */
    static final double SHARE = 0.6;
    /** Пожаров подрыва после волны у сервера по умолчанию ({@code fires_per_detonation}); клиент своё число сервера не знает. */
    static final int MAX_FIRES = 20_000;
    /** Огонь разгорается, тиков. */
    static final double RISE = 20 * 20;
    /** И гаснет со временем, тиков: трава и земля прогорают за секунды, дома и лес — минуты. */
    static final double TAU = 5 * 60 * 20;
    /** Порог вероятности пожара от света ({@code RuinPlanner.fire}). */
    private static final double FULL_Q = 20;
    /** К краю руин след света сходит на нет на этой доле радиуса ({@code RuinPlanner.edge}). */
    private static final double EDGE_FADE = 0.2;

    final Detonation d;
    /** Зажжённых вспышкой пожаров в кольце, со сбиваемыми волной (срез по {@link #MAX_FIRES} — по тем, что останутся). */
    final double[] lit;
    /** Доля их, что волна не сбивает (1 или {@code RuinPlanner.BLOWN_KEEP} от 2 psi). */
    final double[] keep;
    /** Пожаров в кольце после волны ({@link #lit} × {@link #keep}): то, что горит потом; всего — не больше {@link #MAX_FIRES}. */
    final double[] fires;
    /**
     * Поджиг кольца вспышкой и приход фронта к земле в середине кольца (по наклонной от точки подрыва: у воздушного
     * подрыва волна доходит до земли под ним через секунду, а свет — сразу), тиков после подрыва.
     */
    final double[] ignite, arrival;
    /** Пожаров после волны всего. */
    final double total;

    FireField(Detonation d) {
        this.d = d;
        int rings = (int) Math.ceil(d.ruinRadius() / RING);
        double[] l = new double[rings], k = new double[rings], f = new double[rings], ig = new double[rings], at = new double[rings];
        double after = 0;
        for (int i = 0; i < rings; i++) {
            double r = radius(i);
            at[i] = d.arrivalTicks(Math.hypot(r, d.burst().y - d.groundY()));
            k[i] = 1;
            double places = after < MAX_FIRES ? candidates(d, i) : 0;
            if (places <= 0) continue;
            k[i] = keepAt(d, i);
            // в счёте сервера — только несбиваемые: на кольце, где он полон, — их остаток и сбиваемые между ними
            f[i] = Math.min(MAX_FIRES - after, places * k[i]);
            l[i] = f[i] / k[i];
            ig[i] = d.flashTicks(d.fluence(new Vec3(d.burst().x + r, d.groundY() + 1, d.burst().z)), ThermalModel.IGNITE);
            after += f[i];
        }
        this.lit = l;
        this.keep = k;
        this.fires = f;
        this.ignite = ig;
        this.arrival = at;
        this.total = after;
    }

    /** Доля пожаров, которых волна не сбивает от 2 psi ({@code RuinPlanner.BLOWN_KEEP}). */
    static final double BLOWN_KEEP = 0.5;

    /**
     * Сколько мест кольца {@code ring} зажгла бы вспышка без предела пожаров: доля колонок, где огню есть на чём встать,
     * по вероятности света в середине кольца ({@code RuinPlanner.fire}); в огненном шаре у земли и где свет ниже порога
     * поджига — 0.
     */
    static double candidates(Detonation d, int ring) {
        double r = radius(ring), ruin = d.ruinRadius();
        if (d.surface() && r < d.fireballRadius() * 0.8) return 0;
        double q = d.fluence(new Vec3(d.burst().x + r, d.groundY() + 1, d.burst().z)) * edge(r, ruin);
        if (q < ThermalModel.IGNITE) return 0;
        double area = Math.PI * (Math.pow((ring + 1) * RING, 2) - Math.pow(ring * RING, 2));
        return SHARE * Math.min(1, q / FULL_Q) * area;
    }

    /** Доля огня кольца {@code ring}, которую волна не собьёт: {@link #BLOWN_KEEP} от 2 psi в середине кольца, иначе 1. */
    static double keepAt(Detonation d, int ring) {
        return d.psi(new Vec3(d.burst().x + radius(ring), d.groundY() + 1, d.burst().z)) >= 2 ? BLOWN_KEEP : 1;
    }

    private static double edge(double r, double ruin) {
        double f = Math.max(0, Math.min(1, (1 - r / ruin) / EDGE_FADE));
        return f * f * (3 - 2 * f);
    }

    static double radius(int ring) {
        return (ring + 0.5) * RING;
    }

    /** Доля огня, что горит через {@code ticks} после того, как его зажгла вспышка. */
    static double envelope(double ticks) {
        if (ticks <= 0) return 0;
        return ticks < RISE ? ticks / RISE : Math.exp(-(ticks - RISE) / TAU);
    }

    /** Сколько горит в кольце через {@code ticks} после подрыва: зажжённое вспышкой, с приходом волны — без сбитого. */
    double burning(int ring, double ticks) {
        return lit[ring] * envelope(ticks - ignite[ring]) * (ticks < arrival[ring] ? 1 : keep[ring]);
    }

    /** Сколько горит всего. */
    double burning(double ticks) {
        double s = 0;
        for (int i = 0; i < fires.length; i++) s += burning(i, ticks);
        return s;
    }

    /** Огонь кончился: тиков после подрыва, когда от него осталось меньше 2 %. */
    double endTicks() {
        return lastIgnition() + RISE + TAU * Math.log(50);
    }

    /** Поджиг последнего кольца с огнём (вспышка), тики после подрыва. */
    double lastIgnition() {
        double last = 0;
        for (int i = 0; i < lit.length; i++) if (lit[i] > 0) last = Math.max(last, ignite[i]);
        return last;
    }

    /** Вторичный взрыв в горящем городе: газ, топливо, склады. */
    record Secondary(double ticks, double x, double z, boolean big, long seed) {}

    /**
     * Вторичные взрывы подрыва (всё — от зерна подрыва: у всех клиентов и в повторе одни и те же): чем больше горит,
     * тем больше их, по месту — где горит, по времени — от {@link #SECONDARY_AFTER} после прихода волны, всё реже.
     */
    List<Secondary> secondaries() {
        List<Secondary> out = new ArrayList<>();
        if (total < 1) return out;
        RandomSource random = RandomSource.create(d.seed() ^ 0x5EC0DA2L);
        int n = (int) Math.round(Math.max(4, Math.min(24, total / 800)));
        for (int k = 0; k < n; k++) {
            double u = random.nextDouble() * total;
            int ring = 0;
            while (ring < fires.length - 1 && u > fires[ring]) u -= fires[ring++];
            double r = (ring + random.nextDouble()) * RING;
            double angle = random.nextDouble() * 2 * Math.PI;
            // время: после того как разгорелось, всё реже (среднее — SECONDARY_TAU), не позже SECONDARY_END
            double t = arrival[ring] + SECONDARY_AFTER - SECONDARY_TAU * Math.log(1 - random.nextDouble() * (1 - Math.exp(-SECONDARY_END / SECONDARY_TAU)));
            out.add(new Secondary(t, d.burst().x + Math.cos(angle) * r, d.burst().z + Math.sin(angle) * r, random.nextFloat() < 0.2f, random.nextLong()));
        }
        out.sort((a, b) -> Double.compare(a.ticks, b.ticks));
        return out;
    }

    /** Вторичные взрывы — не раньше стольких тиков после прихода волны, в среднем через столько и не позже. */
    static final double SECONDARY_AFTER = 30 * 20, SECONDARY_TAU = 3 * 60 * 20, SECONDARY_END = 10 * 60 * 20;
}
