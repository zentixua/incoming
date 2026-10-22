package ua.zentix.incoming.client.sound;

import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectrum;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Звук разрушений у слушателя — стёкла и обломки, которые волна действительно снесла рядом (без Minecraft, проверяется
 * юнит-тестом). Каждое событие — место, миг, когда до него дошёл фронт, и мощность звука по закону источника
 * ({@link Sources#impact}: доля механической энергии сорванного); к уху звук идёт со скоростью звука, по дороге — путь
 * ISO 9613 ({@link Propagation}: разлёт, воздух, земля, тень днём). Громкость — {@link Loudness}, как у всех звуков мира.
 * <p>
 * Разрушений у города — тысячи мест за секунды, а каналов у звукового движка — сотни. Поэтому дошедшее до уха
 * складывается по мощности (звуки разных мест некогерентны) в {@link #QUADRANTS} четвертях вокруг уха, у каждого вида
 * своё: первый звук четверти звучит сразу, что пришло за ним в {@link #HOLD} тиков, — следующим, суммой. Событие длится
 * {@code seconds} — пока падает сорванное; окно длиннее события растягивает его энергию: мощность в окне —
 * P·min(1, длительность / окно). Направление и расстояние звука — средние по силе того, что в нём.
 */
public final class Clatter {
    /** Виды разрушений: свой звук и своя запись у каждого. */
    public static final int GLASS = 0, DEBRIS = 1, KINDS = 2;
    static final int QUADRANTS = 4;
    /** Звук одной четверти и вида — не чаще раза в столько тиков; что пришло между, — следующим (0,5 с). */
    static final int HOLD = 10;
    /** Дальше {@link ua.zentix.incoming.strike.Hearing#RANGE} звук клиент не несёт — и событие забывается. */
    static final double MAX_TICKS = 60 * 20;

    /**
     * Событие разрушения: место, миг начала (тики часов вызывающего), уровень мощности звука L<sub>W</sub>, дБ (не A),
     * длительность, с, вид ({@link #GLASS}, {@link #DEBRIS}).
     */
    public record Event(double x, double y, double z, double startTick, double lw, double seconds, int kind) {}

    /**
     * Звук, который пора сыграть: вид, точка (направление и расстояние — средние по силе), A-уровень у уха, дБА, запас над
     * фоном в лучшей октаве, дБ, сколько событий в нём.
     */
    public record Play(int kind, double x, double y, double z, double level, double margin, int events) {}

    /** Дошедшее до уха в четверти: мощность по октавам (доли, A), направление и расстояние по силе. */
    private static final class Bin {
        final double[] bands = new double[Propagation.BANDS];
        double weight, dx, dy, dz, dist;
        int events;
        /** Когда четверть звучала последний раз, тики; NaN — тихо (следующее звучит сразу). */
        double since = Double.NaN;

        boolean empty() {
            return events == 0;
        }

        void clear() {
            java.util.Arrays.fill(bands, 0);
            weight = dx = dy = dz = dist = 0;
            events = 0;
        }
    }

    private final Spectrum[] spectra;
    private final List<Event> pending = new ArrayList<>();
    private final Bin[] bins = new Bin[KINDS * QUADRANTS];
    private final double[] scratch = new double[Propagation.BANDS];

    /** {@code spectra} — октавный спектр записи каждого вида ({@link ua.zentix.incoming.sound.Spectra}). */
    /** Ускорение свободного падения, м/с². */
    static final double G = 9.81;
    /** Падение сорванного не короче, чем с полуметра: сорванное у самой земли тоже падает и бьётся. */
    static final double FALL_MIN = 0.5;

    /**
     * Сколько длится звук сорванного с высоты {@code height} м над землёй, с: пока оно падает, √(2h/g) (бросок напором
     * вбок падения не удлиняет).
     */
    public static double seconds(double height) {
        return Math.sqrt(2 * Math.max(FALL_MIN, height) / G);
    }

    /**
     * Мощность звука сорванного, Вт: {@code kg} вещества, брошенного напором со скоростью {@code speed} м/с и упавшего с
     * {@code height} м, — энергия m(v²/2 + g·h), звуком — доля {@link Sources#IMPACT_EFFICIENCY} за время падения
     * ({@link #seconds}).
     */
    public static double watts(double kg, double speed, double height) {
        double h = Math.max(FALL_MIN, height);
        return Sources.IMPACT_EFFICIENCY * kg * (speed * speed / 2 + G * h) / seconds(h);
    }

    public Clatter(Spectrum[] spectra) {
        if (spectra.length != KINDS) throw new IllegalArgumentException("спектр — у каждого вида");
        this.spectra = spectra;
        for (int i = 0; i < bins.length; i++) bins[i] = new Bin();
    }

    public void add(Event e) {
        if (e.lw > Noise.SILENT) pending.add(e);
    }

    /** Событий в пути к уху. */
    public int pending() {
        return pending.size();
    }

    public void clear() {
        pending.clear();
        for (Bin b : bins) {
            b.clear();
            b.since = Double.NaN;
        }
    }

    /**
     * Тик {@code now}: события, чей звук дошёл до уха (ex, ey, ez), — в свои четверти; четверти, которым пора, — в
     * {@code out}. Слышимые — с запасом над фоном больше нуля.
     */
    public void tick(double now, double ex, double ey, double ez, Propagation.Weather weather, List<Play> out) {
        for (Iterator<Event> it = pending.iterator(); it.hasNext(); ) {
            Event e = it.next();
            if (now - e.startTick > MAX_TICKS) {
                it.remove();
                continue;
            }
            double dx = e.x - ex, dy = e.y - ey, dz = e.z - ez, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // звук идёт от места с мига, когда до него дошёл фронт
            if (now < e.startTick + 20 * d / Noise.SOUND) continue;
            it.remove();
            Bin b = bins[e.kind * QUADRANTS + quadrant(dx, dz)];
            Propagation.level(e.lw, spectra[e.kind], Propagation.Path.over(d, Math.max(0, e.y - ey + Propagation.EAR), weather), scratch);
            // окно длиннее события: его энергия — на всё окно (первый звук тихой четверти — в своём тике)
            double window = Double.isNaN(b.since) ? 0.05 : HOLD / 20.0;
            double share = Math.min(1, e.seconds / window);
            double w = 0;
            for (int k = 0; k < scratch.length; k++) {
                double p = Math.pow(10, scratch[k] / 10) * share;
                b.bands[k] += p;
                w += p;
            }
            double len = Math.max(d, 1e-6);
            b.weight += w;
            b.dx += w * dx / len;
            b.dy += w * dy / len;
            b.dz += w * dz / len;
            b.dist += w * d;
            b.events++;
        }
        for (int i = 0; i < bins.length; i++) {
            Bin b = bins[i];
            boolean quiet = Double.isNaN(b.since);
            if (quiet && b.empty()) continue;
            // тихая четверть звучит сразу; звучавшая — когда кончилось её окно
            if (!quiet && now - b.since < HOLD) continue;
            if (b.empty()) {
                b.since = Double.NaN;
                continue;
            }
            double sum = 0;
            for (int k = 0; k < b.bands.length; k++) {
                sum += b.bands[k];
                scratch[k] = 10 * Math.log10(Math.max(b.bands[k], 1e-30));
            }
            double margin = Propagation.margin(scratch, weather);
            if (margin > 0) {
                double r = Math.sqrt(b.dx * b.dx + b.dy * b.dy + b.dz * b.dz), dist = b.dist / b.weight;
                double ux = r > 1e-9 ? b.dx / r : 1, uy = r > 1e-9 ? b.dy / r : 0, uz = r > 1e-9 ? b.dz / r : 0;
                out.add(new Play(i / QUADRANTS, ex + ux * dist, ey + uy * dist, ez + uz * dist, 10 * Math.log10(sum), margin, b.events));
            }
            b.clear();
            b.since = now;
        }
    }

    /** Четверть вокруг уха по направлению на место (0 — восток, дальше против часовой по x, z). */
    static int quadrant(double dx, double dz) {
        double a = Math.atan2(dz, dx) + Math.PI / 4;
        int q = (int) Math.floor(a / (Math.PI / 2));
        return Math.floorMod(q, QUADRANTS);
    }

    /** Громкость в игре звука {@code p} у файла громкостью {@code fileLufs}, с потолком {@code ceilingLufs} ({@link Loudness}). */
    public static float volume(Play p, double fileLufs, double ceilingLufs) {
        return Loudness.volume(Loudness.gain(p.level, p.margin, ceilingLufs), fileLufs);
    }
}
