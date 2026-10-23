package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.MushroomModel;
import ua.zentix.incoming.nuclear.model.StemDust;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ковёр пыли и ножка гриба у её основания — одно поле: среднее ослабление пелены ({@link PallField#sigma}) и ножки с
 * подсосом ({@code mushMean}: ножка, а где подсос гуще — он, {@link MushroomField#footDensity}) там, где ковёр переходит в
 * ножку. Прежде ножка стояла на плоском верху слоя: у стенки над верхом слоя густота падала в разы на полвысоты слоя, а
 * поверхность пыли поднималась отвесно от верха ковра.
 */
class StemFootTest {
    private static final String GLSL = read("include/mushroom.glsl");
    private static final GlslFunction EDGE = GlslFunction.parse(GLSL, "mushEdge");

    private static String read(String path) {
        try (InputStream in = StemFootTest.class.getResourceAsStream("/assets/incoming/shaders/" + path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Подрыв без ветра (поле вокруг ножки — круг), масштаб 1: блок — метр. */
    private static Detonation burst(double yieldKt, double hob) {
        return new Detonation(1, new Vec3(0, 64 + hob, 0), 64, yieldKt, hob <= 0, 0, 0, 0, 20_000, 7, 1, false, 1);
    }

    /** Миг подрыва: гриб, пелена и подсос у основания. */
    private record Case(String name, Detonation d, PallField pall, MushroomModel.Shape s, double ticks, double[] foot) {
        static Case of(double yieldKt, double hob, double seconds) {
            Detonation d = burst(yieldKt, hob);
            PallField pall = new PallField(d, 6000);
            double ticks = 20 * seconds, front = d.metres(d.frontRadius(ticks));
            MushroomModel.Shape s = MushroomModel.shape(seconds, yieldKt, hob, d.surface(), Math.sqrt(Math.max(0, front * front - hob * hob)),
                    StemDust.of(yieldKt, hob, d.surface(), d.visibility()));
            double[] foot = new double[4];
            MushroomField.foot(d, pall, PallField.Water.DRY, s, ticks, foot);
            return new Case(String.format(Locale.ROOT, "%.0f кт на %.0f м, %.0f с", yieldKt, hob, seconds), d, pall, s, ticks, foot);
        }

        double top() {
            return foot[0];
        }

        double wall(double h) {
            return MushroomModel.stemRadius(h / s.capBottom(), s.stemR(), MushroomModel.dustFlare(s));
        }

        /** Ослабление ножки на высоте h без доли края ({@code mushStemSigma} у основания: до верха ножки далеко). */
        double stemSigma(double h) {
            return s.stemTau() / (2 * wall(h));
        }

        /** Ножка без подсоса — среднее по клубам ({@code mushMean}). */
        double stem(double rho, double h) {
            double r = wall(h);
            return stemSigma(h) * EDGE.call(Map.of("d", rho - r, "e", 0.5 * MushroomField.LOBES_STEM * r));
        }

        /** Подсос на высоте h без доли края. */
        double footSigma(double h) {
            return MushroomField.footDensity(h, foot[0], foot[1], foot[2], Math.min(foot[3], s.stemTop()), stemSigma(h));
        }

        /** Подсос гуще самой ножки — от верха слоя до этой его доли (шагом в сотую); 1 — уже на верху слоя нет. */
        double dense() {
            double u = 1;
            while (u < 20 && footSigma((u + 0.01) * top()) >= stemSigma((u + 0.01) * top())) u += 0.01;
            return u;
        }

        /** Середина полосы подсоса за стенкой ножки на высоте h — линия тока от верха ковра в ножку. */
        double sheet(double h) {
            return wall(h) + 0.5 * foot[2] * MushroomField.footWidth(Math.max(h, 0), foot[0]);
        }

        /** Подсос — среднее по клубам ({@code mushMean}: край — у ножки). */
        double foot(double rho, double h) {
            double r = wall(h), at = r + foot[2] * MushroomField.footWidth(Math.max(h, 0), foot[0]);
            return footSigma(h) * EDGE.call(Map.of("d", rho - at, "e", 0.5 * MushroomField.LOBES_STEM * r));
        }

        /** Пыль у основания: ковёр и ножка, а где подсос гуще ножки — он. */
        double dust(double rho, double h, boolean withFoot) {
            return dust(rho, rho, h, withFoot);
        }

        /** То же с ковром на {@code carpet} блоках от оси. */
        double dust(double rho, double carpet, double h, boolean withFoot) {
            double stem = stem(rho, h);
            if (withFoot) stem = Math.max(stem, foot(rho, h));
            return pall.sigma(carpet, 0, h, 0, ticks, PallField.Water.DRY) + stem;
        }
    }

    private static final Case[] CASES = {Case.of(15, 0, 45), Case.of(15, 0, 60), Case.of(15, 0, 120), Case.of(15, 0, 240),
            Case.of(15, 592, 60), Case.of(15, 592, 120), Case.of(1, 0, 30), Case.of(1, 0, 90)};

    /** Подсос есть у каждого мига: иначе проверки ниже ничего не проверяют. */
    @Test
    void everyCaseHasInflow() {
        for (Case c : CASES) {
            assertTrue(c.foot[2] > 0.3 && c.foot[1] > 0 && c.top() > 20, c.name + ": доля " + c.foot[2] + ", верх " + c.top());
        }
    }

    /**
     * По линии тока подсоса (середина его полосы за стенкой ножки) от густой части ковра (0,3 его верха) вверх, пока
     * подсос гуще ножки: густота ножки с подсосом без скачков (на сотую верха слоя — не больше 2 % ковра у стенки или в
     * начале пути; сам ковёр — поле пелены, у него скачков нет), а вся пыль — не реже половины ковра в начале пути
     * или самой ножки на этой высоте — что меньше. Без подсоса над верхом ковра там пусто: ковёр и ножка — два тела с
     * пустотой между ними. Путь — не выше верха слоя под фронтом пыли, что подсос несёт вверх с воздухом ножки (у
     * наземного 15 кт в 45 с — 0,9 км): фронт — край, а не стык ковра и ножки. Ковёр — в одном месте, где полоса на
     * верху слоя: таблица пелены у клиента ({@link PallField#sigma}) — по узлам радиуса, и путь, смещаясь к оси на
     * десятки блоков, ловил бы ступени узлов, которых в шейдере нет (там таблица — текстура с интерполяцией).
     */
    @Test
    void noDensityStepFromCarpetIntoStem() {
        int hollow = 0;
        for (Case c : CASES) {
            double top = c.top(), step = top / 100, from = 0.3 * top;
            double end = Math.min(c.dense() * top, Math.min(c.foot[3], c.s.stemTop()) - top);
            double carpet = c.sheet(top);
            double start = c.dust(c.sheet(from), carpet, from, true), prev = start, thinnestOld = Double.POSITIVE_INFINITY;
            for (double h = from + step; h <= end; h += step) {
                double now = c.dust(c.sheet(h), carpet, h, true), floor = 0.5 * Math.min(start, c.stemSigma(h));
                double carpetNow = c.dust(c.sheet(h), carpet, h, false) - c.stem(c.sheet(h), h), carpetPrev = c.dust(c.sheet(h - step), carpet, h - step, false) - c.stem(c.sheet(h - step), h - step);
                assertTrue(Math.abs((now - carpetNow) - (prev - carpetPrev)) <= 0.02 * Math.max(start, c.foot[1]),
                        c.name + String.format(Locale.ROOT, ": скачок ножки и подсоса на %.0f м (%.2f верха): %.4f → %.4f", h, h / top, prev - carpetPrev, now - carpetNow));
                assertTrue(now >= floor, c.name + String.format(Locale.ROOT, ": на %.0f м (%.2f верха) пыли %.4f, меньше %.4f", h, h / top, now, floor));
                thinnestOld = Math.min(thinnestOld, c.dust(c.sheet(h), carpet, h, false) / floor);
                prev = now;
            }
            System.out.printf(Locale.ROOT, "%s: верх %.0f, σ %.3f, доля %.2f, подъём %.0f; путь до %.2f верха, без подсоса — %.3f порога%n",
                    c.name, top, c.foot[1], c.foot[2], c.foot[3], end / top, thinnestOld);
            // у позднего и мелкого ковёр у стенки и без подсоса касается ножки — там пустоты не было
            if (thinnestOld < 0.1) hollow++;
        }
        assertTrue(hollow >= 5, "без подсоса пустота между ковром и ножкой была бы у " + hollow + " мигов");
    }

    /**
     * Поверхность пыли (где среднее ослабление — пятая часть ножки на этой высоте) от верха ковра до места, где подсос
     * уже не гуще ножки, — за поверхностью самой ножки и к ней всё ближе: ковёр поднимается к ножке плавным раструбом. На
     * верху слоя раструб — не меньше четверти верха слоя × доля подсоса. Без подсоса поверхности совпадали. У наземного
     * подрыва подсос на верху слоя гуще ножки (ковёр у стенки гуще её), у воздушного — бывает и нет: там ножка сама
     * густа, как ковёр.
     */
    @Test
    void carpetClimbsIntoTheStem() {
        for (Case c : CASES) {
            double top = c.top(), prev = Double.POSITIVE_INFINITY, dense = c.dense();
            for (double u = 1; u <= dense; u += 0.05) {
                double h = u * top, seen = 0.2 * c.stem(0, h);
                double dust = surface(c, h, seen, true), stem = surface(c, h, seen, false);
                double need = u < 1.001 ? 0.25 * c.foot[2] : 0;
                assertTrue(dust - stem > need * top, c.name + String.format(Locale.ROOT, ": на %.2f верха раструб %.0f м", u, dust - stem));
                assertTrue(dust - stem <= prev + 1e-6 * top, c.name + String.format(Locale.ROOT, ": на %.2f верха раструб шире, чем ниже", u));
                prev = dust - stem;
            }
        }
    }

    /** Дальше всего от оси, где пыль у основания на высоте h не тоньше seen, блоки. */
    private static double surface(Case c, double h, double seen, boolean withFoot) {
        double far = c.wall(h) + 4 * c.top();
        for (double rho = far; rho > 0; rho -= 0.002 * c.top()) {
            if (c.dust(rho, h, withFoot) >= seen) return rho;
        }
        return 0;
    }
}
