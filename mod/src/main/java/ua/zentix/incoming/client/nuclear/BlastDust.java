package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.Puffs;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.DustModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Пыль проломов у камеры — источник облака клиента (рисуют запечённые клубы слоя эффектов, {@code far/SmokePuffs}): там,
 * где волна снесла блоки ({@code S2C.NukeDebris}), выбитое в пыль вещество уносит ветер за фронтом ({@link BlastGust}).
 * Пыль выходит из пролома, пока дует положительная фаза, — тем больше, чем сильнее ветер (доля вынесенного к мигу —
 * доля пути воздуха, {@link BlastWall#carriedShare}), и ложится полосой по ветру: первая — дальше всех, на путь воздуха с
 * прихода фронта, последняя — у пролома ({@link #parcel}). Полоса расплывается вихрями пыльного слоя и растворяется в
 * нём, когда слой (голова стены, {@link DustModel#waveTop}) её перерастает, — тогда её рисует сама пелена.
 * <p>
 * Сколько пыли: в модели пелены пыль обломков города — {@code DustModel.Surface.REFERENCE.debris} = 0,1 кг/м² там, где
 * рушится всё (подобрано по Хиросиме). На килограмм снесённого это {@link #FINES}: доля, что встаёт мелкой пылью. Масса
 * блока — {@code Shielding.density}. Так пролом одного каменного блока даёт клуб в метры, видный долю секунды, а обрушенный
 * дом — облако. Пелена свою пыль обломков кладёт ровно по давлению и не вычитает эту (⚠ у снесённых домов пыли до вдвое
 * больше, чем в модели над ровным местом; пыль обломков — меньшая часть слоя: грунт ветер поднимает килограммами).
 */
final class BlastDust implements SmokeGrid.Source {
    /**
     * Доля массы снесённого, что встаёт мелкой пылью: 0,1 кг/м² пыли обломков модели на город, где дома занимают половину
     * земли и весят ~400 кг на квадратный метр застройки (двухэтажный дом из дерева, глины и черепицы) — ⚠ оценка.
     */
    static final double FINES = DustModel.Surface.REFERENCE.debris() / (0.5 * 400);
    /**
     * Полоса пыли поднимается и расплывается с пройденным воздухом путём: вверх — 0,02–0,08 пути (Kuhl и др. 1992, §3.4:
     * вертикальная скорость в слое — 0,02 ветра у дна и 0,04–0,12 у верха; та же доля у пыли головы, {@link BlastWall#origin}),
     * вширь — вихрями той же скорости, середина полосы 0,04–0,12 (⚠ оценка середины).
     */
    static final double RISE = 0.05, SPREAD = 0.07;
    /** Пыль пролома в блок — клуб такого полуразмера, блоки. */
    static final double R0 = 0.5;

    /** Полоса одного пролома или кучки проломов: место, ветер там, масса пыли, кг, начальный σ, дальность от эпицентра, м. */
    private record Streak(double x, double y, double z, BlastGust gust, double mass, double r0, double groundM) {}

    private final ClientNuclear.Active a;
    private final List<Streak> streaks = new ArrayList<>();
    private final float dustR, dustG, dustB;
    private final double dustK;
    private final double[] parcel = new double[4];
    private boolean gone;

    BlastDust(ClientNuclear.Active a) {
        this.a = a;
        // пыль стены и пелены того же цвета ({@code NukePall.DUST}): пыль обломков в модели — часть того же слоя
        dustR = SmokeGrid.dustAlbedo((float) NukePall.DUST[0]);
        dustG = SmokeGrid.dustAlbedo((float) NukePall.DUST[1]);
        dustB = SmokeGrid.dustAlbedo((float) NukePall.DUST[2]);
        dustK = SmokeGrid.dustK(dustR, dustG, dustB);
    }

    /** Пыль {@code mass} кг из пролома в {@code at} (кучка — с полуразмером {@code r0} блоков). */
    void add(Vec3 at, double mass, double r0) {
        if (gone || mass <= 0) return;
        Detonation d = a.d;
        streaks.add(new Streak(at.x, at.y, at.z, BlastGust.at(a, at), mass, Math.max(R0, r0),
                d.metres(Math.hypot(at.x - d.burst().x, at.z - d.burst().z))));
    }

    /** Смена мира, перемотка: пыль пропадает сразу. */
    void end() {
        gone = true;
        streaks.clear();
    }

    /**
     * Воздух, что ушёл из места с ветром {@code g} в миг {@code from} (тики после подрыва, не раньше прихода фронта), в миг
     * {@code ticks}: сдвиг от места (into[0..2], блоки) и σ, что набрала полоса вихрями слоя (into[3], блоки).
     */
    static void parcel(BlastGust g, double from, double ticks, double[] into) {
        double s = Math.max(from, g.arrival), path = g.path(ticks) - g.path(Math.min(s, ticks)), travel = g.travel(ticks) - g.travel(Math.min(s, ticks));
        into[0] = g.ax * path;
        into[1] = RISE * travel;
        into[2] = g.az * path;
        into[3] = SPREAD * travel;
    }

    /**
     * Верх слоя пыли, в котором растворяется полоса, блоки: пыльный пограничный слой Kuhl в возрасте пыли на месте
     * ({@link DustModel#waveTop}); слой высотой h перемешан ровно, σ его — h/√12.
     */
    static double layerTop(Detonation d, double groundM, double ageTicks) {
        return d.blocks(DustModel.waveTop(groundM, d.hobMetres(), d.yieldKt(), Math.max(0, ageTicks) / (20.0 * d.scale())));
    }

    /**
     * Ветер волны подрыва {@code d}, что сносит выброс дыма из места {@code at} ({@link Puffs.Carry}: дымки вспышки): с
     * прихода фронта — путь воздуха и рост полосы, как у пыли пролома, и растворение в слое пыли. {@code ticks} — тики
     * после подрыва в миг часов сетки {@code seconds}.
     */
    static Puffs.Carry carry(Detonation d, Vec3 at, double ticks, double seconds) {
        BlastGust g = BlastGust.of(d, at, () -> ticks);
        double groundM = d.metres(Math.hypot(at.x - d.burst().x, at.z - d.burst().z));
        return (now, into) -> {
            double t = ticks + (now - seconds) * 20;
            parcel(g, g.arrival, t, into);
            into[3] = Math.hypot(into[3], layerTop(d, groundM, t - g.arrival) / Math.sqrt(12));
        };
    }

    @Override
    public boolean emit(double seconds, SmokeGrid.Sink out) {
        if (gone) return false;
        Detonation d = a.d;
        double t = a.ticks(0) + (seconds - SmokeGrid.seconds(0)) * 20;
        streaks.removeIf(s -> {
            BlastGust g = s.gust;
            double x = g.x(t);
            if (x <= 0) return false;
            // пыль выходит, пока дует положительная фаза: доля к мигу — доля пути воздуха
            double released = BlastWall.carriedShare(Math.min(x, 1)), last = Math.min(t, g.arrival + g.phase);
            parcel(g, g.arrival, t, parcel);
            double hx = parcel[0], hy = parcel[1], hz = parcel[2], spreadHead = parcel[3];
            parcel(g, last, t, parcel);
            double tx = parcel[0], ty = parcel[1], tz = parcel[2], spreadTail = parcel[3];
            double age = t - g.arrival, top = layerTop(d, s.groundM, age);
            double sigma = Math.hypot(s.r0 + 0.5 * (spreadHead + spreadTail), top / Math.sqrt(12));
            // крупное оседает из слоя высотой с верх головы (не ниже метра), ослабление — по тому, что ещё в воздухе
            double aloft = DustModel.extinctionAloft(age / (20.0 * d.scale()) / Math.max(1, d.metres(top)));
            double mass = s.mass * released * aloft;
            double half = 0.5 * Math.sqrt((hx - tx) * (hx - tx) + (hz - tz) * (hz - tz) + (hy - ty) * (hy - ty));
            // толща поперёк середины полосы: гаусс, размазанный по длине 2·half
            double tau = DustModel.K_MIX * mass / (2 * Math.PI * sigma * (sigma + 2 * half / Math.sqrt(2 * Math.PI)));
            if (x >= 1 && tau < CloudModel.SEEN) return true;
            out.segment(s.x + 0.5 * (hx + tx), s.y + 0.5 * (hy + ty), s.z + 0.5 * (hz + tz), 0.5 * (hx - tx), 0.5 * (hy - ty), 0.5 * (hz - tz),
                    sigma, sigma, 0, mass * DustModel.K_MIX / dustK, 0, dustR, dustG, dustB, CloudModel.T_AIR_K, 0);
            return false;
        });
        return !streaks.isEmpty();
    }

    @Override
    public boolean ended() {
        return gone || streaks.isEmpty();
    }

    @Override
    public long seed() {
        return a.d.seed() ^ 0xD057L;
    }

    /** Кадр клуба — по росту самой молодой полосы (у старых клубы уже крупные). */
    @Override
    public double puffGrowth(double seconds) {
        if (streaks.isEmpty()) return 2;
        Streak s = streaks.getLast();
        double t = a.ticks(0) + (seconds - SmokeGrid.seconds(0)) * 20;
        parcel(s.gust, s.gust.arrival, t, parcel);
        double sigma = Math.hypot(s.r0 + parcel[3], layerTop(a.d, s.groundM, t - s.gust.arrival) / Math.sqrt(12));
        return Math.max(1, sigma / s.r0);
    }
}
