package ua.zentix.incoming.client.nuclear;

/**
 * Когда фронт подрыва встречает слушателя и тело игрока — без Minecraft (проверяется юнит-тестом). Звук слышит камера
 * (там слушатель движка), а бьёт по ушам и толкает ветер — тело: камера снаряда облетает попадание в 30–40 блоках от
 * эпицентра, а игрок стоит за километры. Камера на игроке (вид от первого и третьего лица) — фронт у неё и у тела
 * один, по телу: звуки волны и удар по ушам — в один тик (звуки волны слышно, как до неё, {@link Deafness#before}).
 * Слушатель снова впереди фронта (камера вернулась к игроку, перенос) встретит его снова; камера вернулась к телу,
 * которое фронт уже прошёл, — фронт мимо неё прошёл, его не слышно.
 */
final class FrontMeeting {
    /**
     * Слушатель впереди фронта больше чем на столько блоков — встретит его снова: у самой границы (камера летит вровень
     * с фронтом) удар не повторяется каждые несколько тиков.
     */
    static final double AHEAD = 64;
    /** Что случилось в тик: прежняя встреча слушателя снята, фронт у слушателя, фронт у тела. */
    static final int AGAIN = 1, HEARD = 2, HIT = 4;

    private boolean heard, hit;

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
        boolean onBody = attached && !Double.isNaN(body), wasHit = hit;
        double at = onBody ? body : ear;
        int out = 0;
        if (heard && front < at - AHEAD) {
            heard = false;
            out |= AGAIN;
        }
        if (!heard && front >= at) {
            heard = true;
            if (!(onBody && wasHit)) out |= HEARD;
        }
        if (!hit && body <= front) {
            hit = true;
            out |= HIT;
        }
        return out;
    }

    /** Фронт уже у слушателя. */
    boolean heard() {
        return heard;
    }
}
