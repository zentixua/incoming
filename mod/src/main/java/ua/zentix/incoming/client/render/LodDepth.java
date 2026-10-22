package ua.zentix.incoming.client.render;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Глубина рельефа мода дальней прорисовки (LOD) в этом кадре — для того, что рисуется после мира (слой эффектов:
 * снаряды, вспышки и дым вдали, {@code fx/layer/SceneDepth}): аппаратная проверка глубины Minecraft LOD не видит
 * (Distant Horizons рисует их в свою текстуру глубины, Voxy — в свою, а в глубину Minecraft кладёт их прижатыми к её
 * дальней плоскости). Источник — тот мод, что рисовал в этом кадре: {@link DhDepth} (публичное API DH) или
 * {@link VoxyDepth} (Voxy через Roxy: API нет, только чтение его полей отражением). Оба могут стоять сразу — берётся
 * первый, кто рисовал. Без мода LOD или с незнакомой его версией источника нет, и после мира виден только рельеф
 * Minecraft.
 */
public final class LodDepth {
    private static final List<Source> SOURCES = new ArrayList<>();

    private LodDepth() {}

    /** Глубина LOD в кадре: текстура, обратная проекция, как читать значение. */
    public static final class View {
        /** Текстура глубины OpenGL. */
        public int texture;
        /** Глубина → координаты вида (обратная проекция, которой записана глубина). */
        public final Matrix4f inverseProjection = new Matrix4f();
        /** Глубина в [0, 1] — это NDC от −1 до 1 (обычный OpenGL), иначе NDC от 0 до 1. */
        public boolean negativeOneToOne;
        /** Глубина пикселя без LOD (очищенная). */
        public float empty;
        /**
         * Глубина, которой мод метит пиксель, где рисовал Minecraft, а LOD нет (Voxy: ближняя плоскость — так он
         * не рисует LOD поверх мира); −1 — такой метки нет. Расстояние такого пикселя — глубина Minecraft, а не LOD
         * у ближней плоскости в 8–16 блоках, которая закрыла бы всё дальше.
         */
        public float world = -1;
        /**
         * Мод кладёт LOD и в глубину Minecraft, прижимая дальние к её дальней плоскости (Voxy без шейдерпака): пиксель
         * мира на дальней плоскости — это LOD, а не стена в сотнях блоков; его расстояние — по глубине LOD.
         */
        public boolean pinsWorldDepth;
        /** Как читается глубина — для лога и строки сценария. */
        String how = "";

        public String describe() {
            return how;
        }
    }

    /** Мод LOD, который умеет отдать глубину кадра. */
    interface Source {
        /** Глубина этого кадра в out; false — мод в этом кадре не рисовал. */
        boolean current(View out);

        /** Конец кадра: следующий кадр мод должен нарисовать заново, иначе его глубина — не этого кадра. */
        void endFrame();
    }

    /** При запуске клиента: какие моды LOD стоят и отдают глубину. */
    public static void init() {
        Source dh = DhDepth.init();
        if (dh != null) SOURCES.add(dh);
        Source voxy = VoxyDepth.init();
        if (voxy != null) SOURCES.add(voxy);
    }

    /** Глубина LOD этого кадра в out — первого источника, который рисовал; false — ни один. */
    public static boolean current(View out) {
        for (Source s : SOURCES) {
            out.pinsWorldDepth = false;
            out.world = -1;
            if (s.current(out)) return true;
        }
        return false;
    }

    public static void endFrame() {
        for (Source s : SOURCES) s.endFrame();
    }

    /** Строка «как читается» для источника. */
    static String describe(String source, boolean forward, boolean negativeOneToOne, float empty, float nearest, String more) {
        return String.format(Locale.ROOT, "%s: %s Z, NDC %s, empty %.1f, nearest LOD %.1f blocks%s", source,
                forward ? "forward" : "reverse", negativeOneToOne ? "-1..1" : "0..1", empty, nearest, more);
    }
}
