package ua.zentix.incoming.client.nuclear;

/**
 * Когда фронт подрыва встречает слушателя и тело игрока — без Minecraft (проверяется юнит-тестом). Звук слышит камера
 * (там слушатель движка), а бьёт по ушам и толкает ветер — тело: камера снаряда облетает место взрыва в 32–42 блоках,
 * а игрок стоит за километры. Камера на игроке (вид от первого и третьего лица) или рядом с ним (ближе к взрыву или
 * дальше не больше, чем фронт проходит за тик) встречает фронт с телом, по телу: звуки волны и удар по ушам — в один
 * тик (звуки волны слышно, как до неё, {@link Deafness#before}). Фронт встречает слушателя и тело, только когда прошёл
 * их в этот тик: перенесённых за фронт (камера вернулась к телу, которое фронт уже прошёл, облёт попадания после того,
 * как фронт оттуда ушёл) он не встретил. Слушатель снова впереди фронта (камера вернулась к игроку, перенос) встретит
 * его снова; тело — один раз.
 */
final class FrontMeeting {
    /**
     * Слушатель впереди фронта больше чем на столько блоков — встретит его снова: у самой границы (камера летит вровень
     * с фронтом) удар не повторяется каждые несколько тиков.
     */
    static final double AHEAD = 64;
    /** Сдвиг дальше стольких блоков за тик — перенос, а не движение: перенесённого за фронт фронт не прошёл. */
    static final double JUMP = 64;
    /** Что случилось в тик: прежняя встреча слушателя снята, фронт у слушателя, фронт у тела. */
    static final int AGAIN = 1, HEARD = 2, HIT = 4;

    private boolean heard, hit;
    /** Радиус фронта в прошлый тик; до первого — минус бесконечность (слушатель внутри шара слышит сразу). */
    private double last = Double.NEGATIVE_INFINITY;
    /** Где были слушатель и тело в прошлый тик (от подрыва); NaN — не было. */
    private double lastAt = Double.NaN, lastBody = Double.NaN;

    /**
     * Тик: где фронт и где слушатель с телом.
     *
     * @param front    радиус фронта, блоки
     * @param ear      от подрыва до камеры, блоки
     * @param body     от подрыва до глаз игрока, блоки; NaN — игрока нет
     * @param attached камера на игроке
     * @return что случилось ({@link #AGAIN}, {@link #HEARD}, {@link #HIT}), в этом порядке
     */
    int tick(double front, double ear, double body, boolean attached) {
        double step = Double.isInfinite(last) ? 0 : front - last;
        double at = !Double.isNaN(body) && (attached || Math.abs(ear - body) <= step) ? body : ear;
        int out = 0;
        if (heard && front < at - AHEAD) {
            heard = false;
            out |= AGAIN;
        }
        if (!heard && front >= at) {
            heard = true;
            if (passed(at, lastAt)) out |= HEARD;
        }
        if (!hit && body <= front && passed(body, lastBody)) {
            hit = true;
            out |= HIT;
        }
        last = front;
        lastAt = at;
        lastBody = body;
        return out;
    }

    /**
     * Фронт прошёл точку в этот тик: был до неё, где она сейчас, или она была впереди фронта и шла к взрыву (тогда она
     * пересекает фронт между тиками). Перенос дальше {@link #JUMP} за тик — не движение.
     */
    private boolean passed(double now, double before) {
        return last < now || last < before && Math.abs(now - before) <= JUMP;
    }

    /** Фронт уже у слушателя. */
    boolean heard() {
        return heard;
    }
}
