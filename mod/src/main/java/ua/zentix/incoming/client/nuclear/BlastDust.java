package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
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
 * прихода фронта, последняя — у пролома ({@link #parcel}). Полоса расплывается вихрями пыльного слоя и уходит в него,
 * когда слой (голова стены, {@link DustModel#waveTop}) её перерастает, — тогда её рисует сама пелена ({@link #apart}).
 * <p>
 * Сколько пыли: в модели пелены пыль обломков города — {@code DustModel.Surface.REFERENCE.debris} = 0,1 кг/м² там, где
 * рушится всё (подобрано по Хиросиме). На килограмм снесённого минерального это {@link #FINES}: доля, что встаёт мелкой
 * пылью. Масса блока — {@code Shielding.density}. Так пролом одного каменного блока даёт клуб в метры, видный долю секунды, а обрушенный
 * дом — облако. Пелена свою пыль обломков кладёт ровно по давлению и не вычитает эту (⚠ у снесённых домов пыли до вдвое
 * больше, чем в модели над ровным местом; пыль обломков — меньшая часть слоя: грунт ветер поднимает килограммами).
 */
final class BlastDust implements SmokeGrid.Source {
    /**
     * Доля массы снесённой кладки (кирпич, булыжник, бетон, обтёсанный камень), что встаёт мелкой пылью: 0,1 кг/м² пыли
     * обломков модели — глина стен и штукатурка города, где дома занимают половину земли, а глины, штукатурки и черепицы
     * в них ~400 кг на квадратный метр застройки (двухэтажный дом) — ⚠ оценка. Дерево, листва, стекло и металл в пыль не
     * крошатся: доски и стволы ломаются кусками.
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
    /**
     * Полос у подрыва — не больше (каждая в кадре — до 12 клубов слоя): когда мест нет, новая полоса ближе к камере
     * вытесняет самую дальнюю, иначе не встаёт — волна идёт к камере, и ближние проломы приходят последними.
     */
    static final int MAX_STREAKS = 256;

    /** Полоса одного пролома или кучки проломов: место, ветер там, масса пыли, кг, начальный σ, дальность от эпицентра, м. */
    private static final class Streak {
        final double x, y, z, mass, r0, groundM;
        final BlastGust gust;
        /**
         * Верх слоя пыли, блоки, и доля ослабления в воздухе на целый тик {@link #tick}: верх слоя — корень по таблице прихода
         * фронта, считать его в каждом кадре у сотен полос дорого, а за тик он меняется на доли процента.
         */
        double top, aloft;
        long tick = Long.MIN_VALUE;

        Streak(double x, double y, double z, BlastGust gust, double mass, double r0, double groundM) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.gust = gust;
            this.mass = mass;
            this.r0 = r0;
            this.groundM = groundM;
        }

        /** Верх слоя и доля в воздухе на миг {@code t} (тики после подрыва) — по целому тику. */
        void layer(Detonation d, double t) {
            long k = (long) Math.floor(t);
            if (k == tick) return;
            tick = k;
            double age = k - gust.arrival;
            top = layerTop(d, groundM, age);
            // крупное оседает из слоя высотой с верх головы (не ниже метра), ослабление — по тому, что ещё в воздухе
            aloft = DustModel.extinctionAloft(Math.max(0, age) / (20.0 * d.scale()) / Math.max(1, d.metres(top)));
        }
    }

    private final ClientNuclear.Active a;
    private final List<Streak> streaks = new ArrayList<>();
    private final float dustR, dustG, dustB;
    private final double dustK;
    private final double[] parcel = new double[4];
    /** Для строки сценария: полос, отданных в последнем кадре, наибольшая толща поперёк, масса пыли в них, кг. */
    private int drawn;
    private double tauMax, massDrawn;
    private boolean gone;

    BlastDust(ClientNuclear.Active a) {
        this.a = a;
        // пыль стены и пелены того же цвета ({@code NukePall.DUST}): пыль обломков в модели — часть того же слоя
        dustR = SmokeGrid.dustAlbedo((float) NukePall.DUST[0]);
        dustG = SmokeGrid.dustAlbedo((float) NukePall.DUST[1]);
        dustB = SmokeGrid.dustAlbedo((float) NukePall.DUST[2]);
        dustK = SmokeGrid.dustK(dustR, dustG, dustB);
    }

    /** Пыль {@code mass} кг из пролома в {@code at} (кучка — с полуразмером {@code r0} блоков); {@code eye} — камера. */
    void add(Vec3 at, double mass, double r0, Vec3 eye) {
        if (gone || mass <= 0) return;
        if (streaks.size() >= MAX_STREAKS) {
            int far = -1;
            double farD = at.distanceToSqr(eye);
            for (int i = 0; i < streaks.size(); i++) {
                Streak s = streaks.get(i);
                double dd = eye.distanceToSqr(s.x, s.y, s.z);
                if (dd > farD) {
                    farD = dd;
                    far = i;
                }
            }
            if (far < 0) return;
            streaks.remove(far);
        }
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
     * Доля полосы с σ {@code sigma}, что ещё не смешалась со слоем пыли верхом {@code top} (блоки): слой высотой h перемешан
     * ровно, σ его — h/√12; полоса, которую слой перерос, — уже его пыль, и её рисует пелена (как голова стены отдаёт место
     * пелене, {@link BlastWall#handoff}): доля — σ²/(σ² + σ_слоя²) (⚠ оценка формы перехода). Раздувать полосу до слоя
     * нельзя: в кадре вышла бы вторая пыль поверх пелены — светом неба, без тени головы стены над ней (сцена {@code film}:
     * ровная бежевая вуаль на весь кадр).
     */
    static double apart(double sigma, double top) {
        double layer = top / Math.sqrt(12);
        return sigma * sigma / (sigma * sigma + layer * layer);
    }

    /**
     * Верх слоя пыли, в котором растворяется полоса, блоки: пыльный пограничный слой Kuhl в возрасте пыли на месте
     * ({@link DustModel#waveTop}); слой высотой h перемешан ровно, σ его — h/√12.
     */
    static double layerTop(Detonation d, double groundM, double ageTicks) {
        return d.blocks(DustModel.waveTop(groundM, d.hobMetres(), d.yieldKt(), Math.max(0, ageTicks) / (20.0 * d.scale())));
    }

    @Override
    public boolean emit(double seconds, SmokeGrid.Sink out) {
        if (gone) return false;
        Detonation d = a.d;
        double t = a.ticks(0) + (seconds - SmokeGrid.seconds(0)) * 20;
        drawn = 0;
        tauMax = massDrawn = 0;
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
            s.layer(d, t);
            double sigma = s.r0 + 0.5 * (spreadHead + spreadTail);
            double mass = s.mass * released * s.aloft * apart(sigma, s.top);
            double half = 0.5 * Math.sqrt((hx - tx) * (hx - tx) + (hz - tz) * (hz - tz) + (hy - ty) * (hy - ty));
            // толща поперёк середины полосы: гаусс, размазанный по длине 2·half
            double tau = DustModel.K_MIX * mass / (2 * Math.PI * sigma * (sigma + 2 * half / Math.sqrt(2 * Math.PI)));
            if (x >= 1 && tau < CloudModel.SEEN) return true;
            drawn++;
            tauMax = Math.max(tauMax, tau);
            massDrawn += mass;
            out.segment(s.x + 0.5 * (hx + tx), s.y + 0.5 * (hy + ty), s.z + 0.5 * (hz + tz), 0.5 * (hx - tx), 0.5 * (hy - ty), 0.5 * (hz - tz),
                    sigma, sigma, 0, mass * DustModel.K_MIX / dustK, 0, dustR, dustG, dustB, CloudModel.T_AIR_K, 0);
            return false;
        });
        return !streaks.isEmpty();
    }

    /** Строка сценария: полос живых и в кадре, наибольшая толща поперёк, масса пыли в кадре. */
    String describe() {
        return String.format(java.util.Locale.ROOT, "[%d live, %d drawn, tau max %.2f, %.1f kg]", streaks.size(), drawn, tauMax, massDrawn);
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
        return Math.max(1, (s.r0 + parcel[3]) / s.r0);
    }
}
