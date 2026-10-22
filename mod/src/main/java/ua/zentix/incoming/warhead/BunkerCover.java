package ua.zentix.incoming.warhead;

import java.util.Arrays;

/**
 * Сколько породы (или постройки) над зарядом бетонобойной бомбы — без Minecraft, чтобы проверить тестом.
 * Карта высот в колонке заряда — это дно пробитого бомбой хода (ход до блока вбок), поэтому верх берётся по кольцу
 * колонок в 2 блоках вокруг: крыша постройки, грунт. Прорвётся ли взрыв наружу — по приведённой глубине
 * ({@link BuriedBlast#breaches}).
 */
public final class BunkerCover {
    /** Колонки кольца: сдвиги (dx, dz) от колонки заряда. */
    static final int[][] RING = {{-2, -2}, {-2, 0}, {-2, 2}, {0, -2}, {0, 2}, {2, -2}, {2, 0}, {2, 2}};
    private BunkerCover() {}

    /**
     * Верх над зарядом по высотам колонок кольца (карта высот: первый свободный блок над верхом) — нижняя медиана:
     * до трёх колонок выше или ниже остальных (ствол, край стены, провал рядом) верх не сдвигают.
     */
    public static int surface(int[] ring) {
        int[] h = ring.clone();
        Arrays.sort(h);
        return h[(h.length - 1) / 2];
    }

    /** Глубина заряда под верхом, блоки. */
    public static int depth(int surface, double chargeY) {
        return (int) Math.floor(surface - chargeY);
    }
}
