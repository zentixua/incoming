package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.layer.VolumeNoise;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.MushroomModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Законы пелены и огненного шторма в шейдере ({@code shaders/include/pall.glsl}) — те же, что в Java ({@link PallField},
 * {@link FireStorm}, {@link VolumeNoise#shape}): функции шейдера читаются из файла и считаются маленьким разбором
 * выражений GLSL, числа сравниваются с Java по всей области. Правка закона только в одном месте ломает этот тест.
 */
class PallGlslTest {
    private static final String GLSL = read("include/pall.glsl");
    /** 15 кт у земли, масштаб 1, стена пыли вдали — 0,42 скорости звука. */
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f);
    /** Те же 15 кт в воздухе, в 500 м над землёй. */
    private static final Detonation AIR = new Detonation(2, new Vec3(0, 64 + 500, 0), 64, 15, false, 0, 0.5f, 8, 20_000, 9, 1f, false, 0.42f);

    private static String read(String path) {
        try (InputStream in = PallGlslTest.class.getResourceAsStream("/assets/incoming/shaders/" + path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void sceneDistanceKeepsOnlyTheWorldInFront() {
        // расстояние до мира за пикселем (SceneDepth): негодное — NaN, бесконечность, за глазом (чужая глубина LOD) —
        // как небо, 1e9: иначе луч пелены шёл бы сквозь NaN, и пиксель кадра выходил чёрным
        GlslFunction f = GlslFunction.parse(GLSL, "pallScene");
        for (double d : new double[] {0, 0.5, 64, 3000, 9.9e7}) assertEquals(d, f.call(Map.of("d", d)), 0, "d = " + d);
        for (double d : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.01, -5000, 1e8, 1e9, 3e38}) {
            assertEquals(1e9, f.call(Map.of("d", d)), 0, "d = " + d);
        }
    }

    /**
     * Решётка лучей кадра пелены (fx_pall: луч пикселя t — через середину пикселя полного кадра floor((t + 0,5)·factor)):
     * место середины этого пикселя в ней — ровно t, и накопление (fx_pall_resolve) берёт прошлое в нём же, без
     * размытия с соседями. Со смещением на полпикселя полного кадра прошлое каждый кадр смешивалось с соседями и у края
     * предмета брало пелену фона — кайма по контуру блоков.
     */
    @Test
    void historyIsReadWhereTheRayWent() {
        GlslFunction lattice = GlslFunction.parse(GLSL, "pallLattice");
        for (int factor = 2; factor <= 4; factor++) {
            for (int t = 0; t < 40; t++) {
                double ray = Math.floor((t + 0.5) * factor) + 0.5;
                assertEquals(t, lattice.call(Map.of("p", ray, "factor", (double) factor)), 1e-9, "factor " + factor + ", t " + t);
            }
        }
    }

    /**
     * Клин головы стены без шума — тот же, что в Java: поднятое по доле фазы ({@link DustModel#lifted}), верх, густота у
     * земли и переход к пелене ({@link BlastWall#top}, {@link BlastWall#shape}, {@link BlastWall#handoff}) и средняя
     * голова ({@link BlastWall#mean}).
     */
    @Test
    void wallHeadMatchesJava() {
        GlslFunction lifted = GlslFunction.parse(GLSL, "pallLifted"), top = GlslFunction.parse(GLSL, "pallWallTop"),
                shape = GlslFunction.parse(GLSL, "pallWallShape"), fade = GlslFunction.parse(GLSL, "pallWallFade"),
                mean = GlslFunction.parse(GLSL, "pallWallMean");
        for (double u = -0.5; u <= 3; u += 0.001) {
            String at = "u = " + u;
            assertEquals(DustModel.lifted(u), lifted.call(Map.of("s", u)), 1e-6, at);
            assertEquals(BlastWall.top(u), top.call(Map.of("u", u)), 1e-6, at);
            assertEquals(BlastWall.shape(u), shape.call(Map.of("u", u)), 1e-6, at);
            assertEquals(BlastWall.handoff(u), fade.call(Map.of("u", u)), 1e-9, at);
        }
        for (double len : new double[]{3, 25, 300}) {
            for (double h = 10; h <= 300; h += 58) {
                for (double s = -10; s <= 2 * len; s += len / 37) {
                    for (double z = -0.6 * h; z <= 1.2 * h; z += h / 23) {
                        Map<String, Double> args = Map.of("s", s, "z", z, "len", len, "top", h);
                        assertEquals(BlastWall.mean(s, z, len, h), mean.call(args), 1e-6, len + ", " + h + ": " + s + ", " + z);
                    }
                }
            }
        }
    }

    /**
     * Голова стены в точке — та же, что в Java ({@link BlastWall#sigma}): рамка ({@code pallWallInside}), ослабление от края
     * к концу клина или брызг над водой ({@code pallWallDensity}), сдвиг и верх брызг ({@code pallWallAt}), средняя голова
     * ({@code pallWallMean}). У каждой выборки толщ к свету ({@code wallMove}: {@code wallChord}, {@code wallChordNear}, тень
     * клубов в {@code wall}) — своё ослабление, как у {@code NukePall.chord}, а не точки, откуда идёт луч: от края к концу
     * клина оно меняется в разы. Шейдер собирается тем же текстом, что в файле (числа слота — по столбцам mat4), и
     * сверяется с Java по слотам головы подрыва у земли и в воздухе, в точках и в точках, сдвинутых лучом. Переход к пелене
     * за клином — одна постоянная с Java ({@link BlastWall#HANDOFF_TO}) у рамки, у кольца отрезков ({@code pallWallSpans})
     * и у луча к свету ({@code wall}, {@link NukePall#dustLight}).
     */
    @Test
    void wallHeadSigmaMatchesJava() {
        assertEquals(BlastWall.HANDOFF_FROM, scalar(GLSL, "PALL_WALL_HANDOFF_FROM"), 0);
        assertEquals(BlastWall.HANDOFF_TO, scalar(GLSL, "PALL_WALL_HANDOFF_TO"), 0);
        String fx = read("core/fx_pall.fsh");
        String at = body(GLSL, "WallAt pallWallAt("), move = body(fx, "WallAt wallMove("), chord = body(fx, "float wallChord("),
                near = body(fx, "float wallChordNear("), wall = body(fx, "void wall("), spans = body(GLSL, "vec4 pallWallSpans(");
        // где точка: s — за краем пыли от оси кольца, z — над рельефом; сдвинутая — от своего места, вода и рельеф те же
        assertTrue(at.contains("h.s = V[0].x - length(h.rel);"), "pallWallAt: s — не от оси кольца");
        assertTrue(move.contains("m.rel = h.rel + v.xz;") && move.contains("m.s = V[0].x - length(m.rel);") && move.contains("m.z = h.z + v.y;"),
                "wallMove: сдвиг — не по лучу");
        assertTrue(spans.contains("inner = max(V[0].x - PALL_WALL_HANDOFF_TO * V[0].y - V[1].y, 0.0)"), "кольцо отрезков — не до рамки головы");
        assertTrue(wall.contains("float reach = PALL_WALL_HANDOFF_TO * h.P.x + h.P.y,"), "луч к свету — не до рамки головы");
        assertTrue(chord.contains("return sum * ds;") && near.contains("return sum * len;"), "толща к свету — с ослаблением точки, откуда луч");
        // толща к свету и тень клубов — выборки сдвинутых точек, каждая со своим ослаблением
        String sample = glsl(match(chord, "sum \\+= (.+?);"));
        assertEquals(sample + " * (b - a)", glsl(match(near, "sum \\+= (.+?);")), "wallChordNear: выборка не та, что у wallChord");
        assertEquals(sample, glsl(match(wall, "float m1 = (.+?);")), "тень клубов: выборка не та, что у толщ");
        assertEquals(sample, glsl(match(wall, "float m2 = (.+?);")), "тень клубов: выборка не та, что у толщ");
        assertEquals(sample, glsl(match(wall, "float hm = (.+?);")), "голова в точке: не та же выборка");
        String[] stop = match(at, "if \\((.+?)\\) return h;").split("\\|\\|"), p = args(match(at, "h\\.P = vec4\\(([^;]+)\\); return h;"));
        String lag = glsl(match(at, "float lag = (.+?);")), numbers = "float Px = " + glsl(p[0]) + ", Py = " + glsl(p[1]) + ", Pz = " + glsl(p[2]) + ";";
        StringBuilder test = new StringBuilder(GLSL).append("\nfloat headAt(float pts, float ptz, float ptwet").append(SLOT_PARAMS).append(") {\n");
        for (String c : stop) test.append("if (").append(glsl(c)).append(") return 0.0;\n");
        test.append("float lag = ").append(lag).append(";\n").append(numbers).append("\nfloat Pw = ").append(glsl(p[3])).append(";\nreturn ")
                .append(sample).append(";\n}\n");
        test.append("float headMoved(float pts, float ptz, float ptwet").append(SLOT_PARAMS).append(") {\nfloat lag = ").append(lag).append(";\n")
                .append(numbers).append("\nfloat Pw = ").append(glsl(match(move, "m\\.P\\.w = (.+?);"))).append(";\nreturn ").append(sample).append(";\n}\n");
        GlslFunction head = GlslFunction.parse(test.toString(), "headAt"), moved = GlslFunction.parse(test.toString(), "headMoved");
        float[] slot = new float[BlastWall.SLOT];
        int points = 0, inside = 0, steps = 0;
        double[][] rays = {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {0.6, 0.8, 0}, {-0.8, 0.2, 0.566}};
        for (Detonation d : new Detonation[]{GROUND, AIR}) {
            BlastWall w = new BlastWall(d, false);
            for (double t : new double[]{0.5, 1.5, 4, 9, 16}) {
                if (!w.slot(t, slot) || slot[3] <= 0) continue;
                double len = slot[1], top = slot[2], back = BlastWall.HANDOFF_TO * len + slot[5];
                for (double s = -4; s <= back + 4; s += (back + 8) / 41) {
                    for (double z = -0.7 * top; z <= 1.15 * top; z += top / 17) {
                        for (double wet : new double[]{0, 0.35, 1}) {
                            double angle = 0.3 + s / 97, r = slot[0] - s, x = r * Math.cos(angle), y = r * Math.sin(angle);
                            double want = BlastWall.sigma(slot, x, y, z, wet), shader = head.call(args(slot, slot[0] - Math.hypot(x, y), z, wet));
                            String where = d.surface() + ", " + t + " с: s = " + s + ", z = " + z + ", вода " + wet;
                            assertEquals(want, shader, 1e-4 * Math.abs(want) + 1e-7, where);
                            points++;
                            if (want > 0) inside++;
                            else continue;
                            // из точки в голове — лучом вверх, вниз и в стороны: у неё самой, на полклина и дальше клина
                            for (double[] v : rays) {
                                for (double l : new double[]{0.1 * top, 0.5 * len, 1.3 * len}) {
                                    double mx = x + v[0] * Math.cos(angle) * l - v[2] * Math.sin(angle) * l, my = y + v[0] * Math.sin(angle) * l + v[2] * Math.cos(angle) * l;
                                    double mz = z + v[1] * l, javaM = BlastWall.sigma(slot, mx, my, mz, wet);
                                    double shaderM = moved.call(args(slot, slot[0] - Math.hypot(mx, my), mz, wet));
                                    assertEquals(javaM, shaderM, 1e-4 * Math.abs(javaM) + 1e-7, where + ", луч " + java.util.Arrays.toString(v) + " на " + l);
                                    steps++;
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(points > 10_000 && inside > 1_000 && steps > 20_000, points + " точек, в голове " + inside + ", выборок лучей " + steps);
    }

    /**
     * Брызги над водой — своя строка таблицы слота (своя высота, свой верх): {@link PallField#row} пишет пыль, брызги и мглу
     * строками {@link PallField#DUST_ROW}, {@link PallField#SPRAY_ROW}, {@link PallField#HAZE_ROW}, слот указывает на них
     * шейдеру ({@link NukePall#tableRows}: S[2].z, S[3].w, S[2].w), а {@code pallField} читает брызги там — одна выборка
     * таблицы сверх пыли и мглы, только у подрыва с водой под пеленой — и кладёт их, как {@link PallField#sigma}: у края — как
     * пыль, над водой — вместо неё, со своим верхом.
     */
    @Test
    void sprayHasItsOwnRow() {
        PallField p = new PallField(GROUND, 6000);
        float[] row = new float[4 * PallField.ROWS * PallField.BINS];
        double[] v = new double[4];
        boolean spray = false;
        for (double ticks : new double[]{40, 400, 2400}) {
            p.row(ticks, true, true, row);
            for (int i = 0; i < PallField.BINS; i++) {
                p.dust(i, ticks, v);
                for (int k = 0; k < 4; k++) assertEquals((float) v[k], row[4 * (PallField.DUST_ROW * PallField.BINS + i) + k], 0, "пыль, узел " + i);
                p.spray(i, ticks, v);
                for (int k = 0; k < 4; k++) assertEquals((float) v[k], row[4 * (PallField.SPRAY_ROW * PallField.BINS + i) + k], 0, "брызги, узел " + i);
                spray |= v[0] > 0;
                p.haze(i, ticks, v);
                for (int k = 0; k < 4; k++) assertEquals((float) v[k], row[4 * (PallField.HAZE_ROW * PallField.BINS + i) + k], 0, "мгла, узел " + i);
            }
        }
        assertTrue(spray, "брызг нет ни в одном узле");
        for (int k = 0; k < 2; k++) {
            org.joml.Matrix4f m = new org.joml.Matrix4f();
            NukePall.tableRows(m, k);
            assertEquals((PallField.ROWS * k + PallField.DUST_ROW + 0.5) / ua.zentix.incoming.client.fx.layer.PallPass.ROWS, m.m22(), 1e-7, "слот " + k + ": пыль");
            assertEquals((PallField.ROWS * k + PallField.SPRAY_ROW + 0.5) / ua.zentix.incoming.client.fx.layer.PallPass.ROWS, m.m33(), 1e-7, "слот " + k + ": брызги");
            assertEquals((PallField.ROWS * k + PallField.HAZE_ROW + 0.5) / ua.zentix.incoming.client.fx.layer.PallPass.ROWS, m.m23(), 1e-7, "слот " + k + ": мгла");
        }
        assertEquals(2 * PallField.ROWS, ua.zentix.incoming.client.fx.layer.PallPass.ROWS, "строк таблицы — не на два слота");
        String f = body(GLSL, "PallAt pallField(");
        assertTrue(f.contains("vec4 dust = texture(table, vec2(min(ud, 1.0), S[2].z));"), "пыль — не своей строкой");
        assertTrue(f.contains("vec4 haze = texture(table, vec2(min(uh, 1.0), S[2].w));"), "мгла — не своей строкой");
        int water = f.indexOf("if (W.w > 0.5) {"), read = f.indexOf("vec4 spray = texture(table, vec2(min(ud, 1.0), S[3].w));");
        assertTrue(water >= 0 && read > water, "брызги — не своей строкой или читаются и без воды под пеленой");
        assertEquals(1, occurrences(f, "S[3].w"), "строку брызг — не одной выборкой");
        // как PallField.layers и sigma: брызги у края — как пыль, над водой вместо пыли, верх — свой не ниже блока
        for (String law : new String[]{"ss = spray.r * edge;", "topS = max(spray.g, 1.0);", "if (sd + ss > 0.0) wet = pallWater(ground, G, lift);",
                "sd *= 1.0 - wet;", "ss *= wet;", "hs = above / topS;", "ss * pallProfile(hs)", "ss * topS * pallColumn(hs)"}) {
            assertTrue(f.contains(law), "брызги пелены — не как в Java: нет «" + law + "»");
        }
    }

    /** Параметры слота стены у функций проверки: числа mat4 головы по столбцам. */
    private static final String SLOT_PARAMS;

    static {
        StringBuilder b = new StringBuilder();
        for (int c = 0; c < 4; c++) for (String r : new String[]{"x", "y", "z", "w"}) b.append(", float V").append(c).append(r);
        SLOT_PARAMS = b.toString();
    }

    /** Аргументы функции проверки головы: точка (за краем пыли, над рельефом, вода) и числа головы слота [0..15]. */
    private static Map<String, Double> args(float[] slot, double s, double z, double wet) {
        Map<String, Double> a = new HashMap<>();
        a.put("pts", s);
        a.put("ptz", z);
        a.put("ptwet", wet);
        String[] rows = {"x", "y", "z", "w"};
        for (int i = 0; i < 16; i++) a.put("V" + i / 4 + rows[i % 4], (double) slot[i]);
        return a;
    }

    /**
     * Выражение шейдера — в имена функции проверки: {@code V[c].r} — {@code Vcr}; у точки ({@code h}, {@code m},
     * {@code h1}, …) — {@code Px}…{@code Pw}, {@code pts}, {@code ptz}, {@code ptwet}.
     */
    private static String glsl(String e) {
        e = e.replaceAll("V\\[(\\d)]\\.([xyzw])", "V$1$2");
        e = e.replaceAll("\\b[hm]\\d?\\.P\\.([xyzw])\\b", "P$1");
        return e.replaceAll("\\b[hm]\\d?\\.(s|z|wet)\\b", "pt$1").strip();
    }

    /** Аргументы вызова верхнего уровня (через запятые вне скобок). */
    private static String[] args(String list) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int depth = 0, from = 0;
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ',' && depth == 0) {
                out.add(list.substring(from, i).strip());
                from = i + 1;
            }
        }
        out.add(list.substring(from).strip());
        return out.toArray(String[]::new);
    }

    /** Первая группа первого совпадения в тексте. */
    private static String match(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (!m.find()) throw new AssertionError("в шейдере нет «" + regex + "»");
        return m.group(1);
    }

    /** Тело функции шейдера с этим началом объявления, пробелы — по одному. */
    private static String body(String source, String header) {
        int at = source.indexOf(header);
        if (at < 0) throw new AssertionError("в шейдере нет " + header);
        int open = source.indexOf('{', at), depth = 1, i = open + 1;
        for (; depth > 0; i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}') depth--;
        }
        return source.substring(open + 1, i - 1).replaceAll("\\s+", " ");
    }

    @Test
    void profileMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallProfile");
        for (double x = -1.5; x <= 1.5; x += 0.005) assertEquals(PallField.profile(x), f.call(Map.of("x", x)), 1e-9, "x = " + x);
    }

    @Test
    void columnMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallColumn");
        for (double x = -1.5; x <= 1.5; x += 0.005) assertEquals(PallField.column(x), f.call(Map.of("x", x)), 1e-9, "x = " + x);
    }

    @Test
    void edgeMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallEdge");
        for (double u = 0; u <= 1.2; u += 0.002) assertEquals(PallField.edge(u), f.call(Map.of("u", u)), 1e-9, "u = " + u);
    }

    @Test
    void surgeMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallSurge"), top = GlslFunction.parse(GLSL, "pallSurgeTop");
        for (double u = 0; u <= 1.3; u += 0.002) {
            assertEquals(PallField.surgeProfile(u), f.call(Map.of("u", u)), 1e-9, "u = " + u);
            assertEquals(PallField.surgeTop(u), top.call(Map.of("u", u)), 1e-9, "u = " + u);
        }
    }

    /**
     * Водяная пыль пелены ({@code MIST} в {@code fx_pall.fsh}) — того же белого, что стена брызг над водой
     * ({@link CloudPuffs#SPRAY}): пелена за фронтом над водой продолжает её цвет, как бурая пыль — стену над сушей.
     */
    @Test
    void mistIsTheSprayWallColour() {
        double[] mist = vec3(read("core/fx_pall.fsh"), "MIST");
        for (int c = 0; c < 3; c++) assertEquals((CloudPuffs.SPRAY >> (16 - 8 * c) & 0xFF) / 255.0, mist[c], 0.5 / 255, "канал " + c);
    }

    @Test
    void shapeMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallShape");
        for (double r = 0; r <= 1; r += 0.05) {
            for (double g = 0; g <= 1; g += 0.05) {
                for (double fill = 0; fill <= 1; fill += 0.1) {
                    assertEquals(VolumeNoise.shape(r, g, fill), f.call(Map.of("n.x", r, "n.y", g, "fill", fill)), 1e-9,
                            "r = " + r + ", g = " + g + ", fill = " + fill);
                }
            }
        }
    }

    /**
     * Дымка до гриба, пелены и стены ({@code pallAir}) — та же, что у дальней картинки ({@link Sight.Air}): воздух кадра в
     * блоках модели ({@code MushroomField.air}) при любом масштабе, вверх, вниз и вдоль земли.
     */
    @Test
    void airMatchesJava() {
        GlslFunction depth = GlslFunction.parse(GLSL, "pallAirDepth");
        double heights = scalar(GLSL, "PALL_AIR_HEIGHTS");
        assertEquals(Sight.HAZE_HEIGHT / Sight.AIR_HEIGHT, heights, 1e-7);
        for (double scale : new double[] {1, 0.25}) {
            Detonation d = new Detonation(1, new Vec3(0, 64, 0), 64, 15, false, 0, 0.5f, 8, 20_000, 7, (float) scale, false, 0.42f);
            for (double eyeY : new double[] {40, 70, 300}) {
                Vec3 eye = new Vec3(0, eyeY, 0);
                for (double range : new double[] {Sight.CLEAR, Sight.RAIN, Sight.THUNDER}) {
                    org.joml.Vector4f air = new org.joml.Vector4f();
                    MushroomField.air(d, eye, range, air);
                    Sight.Air metres = MushroomField.air(d, eye, range);
                    for (double t : new double[] {0, 5, 400, 6000, 30000}) {
                        for (double dy : new double[] {-0.3, -1e-6, 0, 1e-6, 0.2, 0.9}) {
                            double glsl = Math.exp(-depth.call(Map.of("t", t, "dy", dy, "haze", (double) air.x, "clean", (double) air.y,
                                    "weather", (double) air.z, "kh", (double) air.w, "PALL_AIR_HEIGHTS", heights)));
                            double java = metres.transmittance(d.metres(t), d.metres(dy * t));
                            assertEquals(java, glsl, 1e-5 + 1e-5 * java, "масштаб " + scale + ", глаз " + eyeY + ", t " + t + ", dy " + dy);
                        }
                    }
                }
            }
        }
    }

    /**
     * Докуда идёт луч пелены ({@code pallClip} в {@code fx_pall}): в слое-экспоненте с ослаблением у глаза sigma и толщей
     * tau по вертикали луч с косинусом mu к вертикали набирает толщу 12 ровно там; выходит из слоя, не набрав 12, — не
     * обрывается; почти горизонтальный — 12/sigma, и никогда не ближе. Нет пыли у глаза — не обрывается.
     */
    @Test
    void clipWhereDepthReachesTwelve() {
        GlslFunction f = GlslFunction.parse(read("core/fx_pall.fsh"), "pallClip");
        assertTrue(f.call(Map.of("sigma", 0.0, "tau", 5.0, "mu", 0.0)) >= 1e6);
        // глаз ниже грубой карты рельефа: толщи под ним нет, пыль у глаза есть — как в ровной пыли
        for (double mu : new double[]{0, 0.3, 1}) assertEquals(12 / 0.06, f.call(Map.of("sigma", 0.06, "tau", 0.0, "mu", mu)), 1e-9);
        for (double sigma : new double[]{0.003, 0.06, 0.5}) {
            for (double tau : new double[]{0.1, 2, 30, 400}) {
                for (double mu : new double[]{0, 1e-7, 1e-4, 0.01, 0.1, 0.4, 1}) {
                    double clip = f.call(Map.of("sigma", sigma, "tau", tau, "mu", mu));
                    String at = "sigma " + sigma + ", tau " + tau + ", mu " + mu;
                    assertTrue(clip >= 12 / sigma * (1 - 1e-6), at + ": ближе, чем в ровной пыли");
                    if (tau <= 12 * mu) {
                        assertTrue(clip >= 1e6, at + ": луч выходит из слоя, не набрав 12");
                        continue;
                    }
                    double depth = mu == 0 ? sigma * clip : tau / mu * (1 - Math.exp(-clip * mu * sigma / tau));
                    assertEquals(12, depth, 1e-3, at);
                }
            }
        }
    }

    /** Свет шара на пыли в {@code fx_pall} ({@code pallBallShare}) — закон {@link CloudPuffs#ball}: L·R²/d², у поверхности — L. */
    @Test
    void ballLightMatchesJava() {
        GlslFunction f = GlslFunction.parse(read("core/fx_pall.fsh"), "pallBallShare");
        for (double radius : new double[]{0, 40, 150, 600}) {
            for (double d = 0; d <= 4000; d += 50) {
                assertEquals(CloudPuffs.ball(1, radius, d), f.call(Map.of("radius", radius, "d", d)), 1e-12,
                        "radius = " + radius + ", d = " + d);
            }
        }
    }

    @Test
    void burnMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallBurn");
        for (double r = 0; r <= 4000; r += 25) {
            for (double up = 0; up <= 1; up += 0.25) {
                for (double fireR : new double[]{0, 1900}) {
                    assertEquals(CloudPuffs.burn(r, up, 0.8, fireR), f.call(Map.of("r", r, "up", up, "glow", 0.8, "fireR", fireR)), 1e-9,
                            "r = " + r + ", up = " + up + ", fireR = " + fireR);
                }
            }
        }
    }

    /** Свет и экран шейдера ({@code pallLinear}, {@code pallDisplay}, {@code pallLift}) — те же, что в {@link PallLight}. */
    @Test
    void displayMatchesJava() {
        GlslFunction linear = GlslFunction.parse(GLSL, "pallLinear"), display = GlslFunction.parse(GLSL, "pallDisplay"), lift = GlslFunction.parse(GLSL, "pallLift");
        for (double v = 0; v <= 1.5; v += 0.01) {
            assertEquals(PallLight.linear(v), linear.call(Map.of("v", v)), 1e-12, "v = " + v);
            assertEquals(PallLight.display(v), display.call(Map.of("v", v)), 1e-6 * (1 + v), "v = " + v);
            for (double base : new double[]{0.12, 0.17, 0.6, 1}) {
                assertEquals(PallLight.lift(base, v), lift.call(Map.of("base", base, "x", v)), 1e-12, "небо " + base + ", отсвет " + v);
            }
        }
    }

    /** Мелкие клубы шейдера — те же, что у {@link VolumeNoise#detail}. */
    @Test
    void detailMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallDetail");
        for (double r = 0; r <= 1; r += 0.002) assertEquals(VolumeNoise.detail(r), f.call(Map.of("r", r)), 1e-9, "r = " + r);
    }

    /** Затухание рассеянного света и сам свет в слое ({@code pallK}, {@code pallDiffuse}) — те же, что в {@link PallLight}. */
    @Test
    void lightMatchesJava() {
        GlslFunction k = GlslFunction.parse(GLSL, "pallK"), f = GlslFunction.parse(GLSL, "pallDiffuse");
        // у тёмного дыма (0,55, 0,48) k > 1: пучок гаснет как рассеянный свет при μ = 1/k
        double[] albedos = {1, 0.9989, 0.97, 0.9, 0.76, 0.55, 0.48};
        double[] phases = {0.3333, 0.375, 0.4595};
        for (double w : albedos) {
            for (double g : phases) {
                assertEquals(PallLight.k(w, g), k.call(Map.of("w", w, "g", g)), 1e-12, "w = " + w + ", g = " + g);
                for (double tc : new double[]{0.01, 0.7, 3, 12}) {
                    for (double share : new double[]{0, 0.4, 1}) {
                        // 0,95 и 0,975 — в полосе kμ ≈ 1 у тёмного дыма (сдвиг пучка, pallBeamMu)
                        for (double mu1 : new double[]{0.12, 0.6, 0.95, 0.975, 1}) {
                            for (double f2 : new double[]{0, 0.5}) {
                                for (double top : new double[]{0, 0.2}) {
                                    for (double bottom : new double[]{0, 0.7}) {
                                        for (double c : new double[]{-2.0 / 3, 0.2, 2.0 / 3}) {
                                            double tau = share * tc, ground = bottom > 0 ? 0.2 : 0.06;
                                            double java = PallLight.diffuse(w, g, tau, tc, ground, mu1, 0.8, 0.35, f2, top, bottom, c);
                                            Map<String, Double> args = new HashMap<>(Map.of("w", w, "g", g, "tau", tau, "tc", tc, "ground", ground,
                                                    "mu1", mu1, "f1", 0.8, "mu2", 0.35, "f2", f2, "top", top));
                                            args.put("bottom", bottom);
                                            args.put("c", c);
                                            // 2/3 в шейдере — 0,6666667: при почти непоглощающем веществе (матрица почти вырождена) это 10⁻⁵
                                            assertEquals(java, f.call(args), 1e-4 * (1 + Math.abs(java)), "w = " + w + ", g = " + g + ", τc = " + tc
                                                    + ", τ = " + tau + ", μ1 = " + mu1 + ", f2 = " + f2 + ", верх " + top + ", низ " + bottom + ", c = " + c);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Вещество шейдера ({@code PALL_*_W}, {@code PALL_*_G} в {@code pall.glsl}) — из цветов толстого слоя {@code fx_pall.fsh}
     * в линейном ({@link PallLight#albedo}, асимметрия {@link PallLight#truncated}): толстая пелена на экране того же цвета,
     * что стена пыли и брызг (R∞ альбедо — её цвет). Сажа и пламя смерча в линейном ({@code *_LINEAR}) — те же цвета.
     */
    @Test
    void materialsHaveTheirColours() {
        String fx = read("core/fx_pall.fsh");
        String[][] materials = {{"DUST", "DUST"}, {"SMOKE", "DUST"}, {"HAZE", "DUST"}, {"MIST", "MIST"}, {"SOOT", "SOOT"}};
        Map<String, Double> full = Map.of("DUST", PallLight.DUST_G, "MIST", PallLight.MIST_G, "SOOT", PallLight.SOOT_G);
        for (String[] m : materials) {
            double g = scalar(GLSL, "PALL_" + m[1] + "_G");
            assertEquals(PallLight.truncated(full.get(m[1])), g, 1e-7, m[0]);
            double[] colour = vec3(fx, m[0]), w = vec3(GLSL, "PALL_" + m[0] + "_W");
            for (int c = 0; c < 3; c++) {
                assertEquals(PallLight.albedo(PallLight.linear(colour[c]), g), w[c], 1e-7, m[0] + ", канал " + c);
                assertEquals(colour[c], PallLight.display(PallLight.reflectance(w[c], g)), 1e-4, m[0] + ", канал " + c);
            }
        }
        for (String m : new String[]{"SOOT", "WHIRL_FIRE"}) {
            double[] colour = vec3(fx, m), linear = vec3(fx, m + "_LINEAR");
            for (int c = 0; c < 3; c++) assertEquals(PallLight.linear(colour[c]), linear[c], 1e-7, m + ", канал " + c);
        }
        assertEquals(PallLight.LAND, scalar(GLSL, "PALL_LAND"), 0);
        assertEquals(PallLight.SEA, scalar(GLSL, "PALL_SEA"), 0);
        // пыль у камеры (NukePall.dustLight) — тех же веществ и того же пламени
        double[] dust = vec3(fx, "DUST"), smoke = vec3(fx, "SMOKE"), fire = vec3(fx, "FIRE");
        for (int c = 0; c < 3; c++) {
            assertEquals(dust[c], NukePall.DUST[c], 0, "канал " + c);
            assertEquals(smoke[c], NukePall.SMOKE[c], 0, "дым, канал " + c);
            assertEquals(fire[c], NukePall.FIRE[c], 0, "пламя, канал " + c);
        }
    }

    /**
     * Пелена за фронтом ночью — в том же свете, что голова стены перед ней: толстая пыль ({@code PALL_DUST_W}) в свете шара
     * и пожаров по законам шейдера (pallDiffuse; шар — чёрное тело, как у тела гриба, pallBallShare × привыкание глаза;
     * пожары — сила {@link CloudPuffs#glowOnSmoke}, делённая на цвет пыли {@code DUST}, на экране поверх света неба — в
     * линейном разностью, pallLift; свет неба — {@link NukePall#nightSky} в линейном; итог — на экране, pallDisplay).
     * Сверху, под шаром и шапкой в первые секунды (пучок, pallBallLight), — того же цвета, что верх
     * головы стены там же ({@link NukePall#dustLight} — свет головы, как в кадре), с точностью до трети: голова — не
     * плоский слой. И не тёмная у эпицентра. Своё зарево пожаров пелены ({@code PallFireLight}) разгорается позже, с минуту,
     * — его здесь нет.
     * <p>
     * Свет пожаров — не ярче пламени ({@code pallFlame}, {@link PallLight#flame}). Ночью над зоной пожаров отсвет на экране
     * уже цвета огня ({@code FIRE}), а такой цвет у пыли свет дать не может — обращение через цвет пыли дало бы свет втрое
     * ярче огня. Там пелена у пола — пыль в свете самого пламени: сбоку не ярче пламени и того же оттенка (красный, за ним
     * зелёный, синий), в красном — не меньше 0,6 его: не тёмный занавес над пожарами.
     */
    @Test
    void pallBehindTheFrontTakesTheWallLight() {
        GlslFunction burn = GlslFunction.parse(GLSL, "pallBurn"), light = GlslFunction.parse(GLSL, "pallDiffuse"),
                lift = GlslFunction.parse(GLSL, "pallLift"), display = GlslFunction.parse(GLSL, "pallDisplay"), linear = GlslFunction.parse(GLSL, "pallLinear");
        assertTrue(GLSL.replaceAll("\\s+", " ").contains("vec3 pallFlame(float base, vec3 x, vec3 flame) { return min(pallLift3(base, x), "
                + "vec3(pallLinear(flame.r), pallLinear(flame.g), pallLinear(flame.b))); }"), "pallFlame — не PallLight.flame");
        String fx = read("core/fx_pall.fsh");
        GlslFunction ball = GlslFunction.parse(fx, "pallBallShare");
        double[] dust = vec3(fx, "DUST"), fire = vec3(fx, "FIRE"), w = vec3(GLSL, "PALL_DUST_W");
        assertEquals(PallLight.CROSS, scalar(fx, "CROSS"), 0, "к белому — как PallLight.screen");
        double g = scalar(GLSL, "PALL_DUST_G");
        CloudPuffs puffs = new CloudPuffs(GROUND);
        BlastWall blast = new BlastWall(GROUND, false);
        float ambient = 0.17f;
        double k = CloudPuffs.glowOnSmoke(ambient), sky = linear.call(Map.of("v", (double) NukePall.nightSky(ambient, 1))), tc = 8;
        float[] wall = new float[8], high = new float[3], slot = new float[BlastWall.SLOT];
        NukePall.Sky night = new NukePall.Sky();
        night.sy = -1;
        java.util.Arrays.fill(night.sky, (float) sky);
        night.base = ambient;
        night.exposure = (float) Sight.adapted(1, ambient);
        night.glowOnSmoke = (float) k;
        int checked = 0, burning = 0;
        for (double t : new double[]{2, 6, 10, 20, 40}) {
            assertTrue(puffs.pallLight(MushroomModel.shape(t, 15, 0, true, GROUND.metres(blast.wall(t).head())), wall));
            assertTrue(blast.slot(t, slot));
            double head = blast.wall(t).head();
            for (double share : new double[]{0.01, 0.03, 0.05, 0.2, 0.5, 0.8, 0.97}) {
                double r = head * share;
                float[] here = slot.clone();
                here[0] = (float) (r + here[1]);
                // середина шапки снесена ветром (CloudPuffs.pallLight), точка — на 0,9 высоты головы над r
                double wind = GROUND.windDir(), lx = Math.cos(wind) * wall[2] - r, ly = wall[7] - 0.9 * here[2], lz = Math.sin(wind) * wall[2];
                double toBall = Math.sqrt(lx * lx + ly * ly + lz * lz);
                double a = ball.call(Map.of("radius", (double) wall[0], "d", toBall)) * night.exposure;
                double b = burn.call(Map.of("r", r, "up", 0.0, "glow", (double) wall[1], "fireR", (double) wall[3]));
                double mu = ly / toBall;
                // верх головы стены, будь здесь конец клина (точка — посреди её толщи), — до неё пелена здесь и была ею
                NukePall.dustLight(here, wall, night, wind, r, 0.9 * here[2], 0, 0.9 * here[2], high);
                double sum = 0, fires = 0;
                double[] side = new double[3], top = new double[3], luma = {0.2126, 0.7152, 0.0722};
                // отсвет пожаров на пыли — уже цвет огня во всех каналах: свет пелены — пламя (pallFlame); у края зоны пожаров,
                // где насыщен только синий, пелена в красном и должна быть темнее пламени
                boolean flames = true;
                for (int c = 0; c < 3; c++) {
                    flames &= lift.call(Map.of("base", (double) ambient, "x", fire[c] * b * k / dust[c])) > linear.call(Map.of("v", fire[c]));
                }
                for (int c = 0; c < 3; c++) {
                    double capF = wall[4 + c] * a;
                    double burnF = Math.min(lift.call(Map.of("base", (double) ambient, "x", fire[c] * b * k / dust[c])),
                            linear.call(Map.of("v", fire[c])));
                    fires += luma[c] * burnF;
                    Map<String, Double> args = new HashMap<>(Map.of("w", w[c], "g", g, "tc", tc, "ground", PallLight.LAND, "mu1", 1.0,
                            "f1", 0.0, "mu2", Math.max(mu, 0.12), "f2", capF, "top", sky, "bottom", burnF));
                    args.put("tau", tc);
                    args.put("c", -2.0 / 3);
                    // пелена срезает плечом (fx_pall: shoulder): ярче 1 — как 1
                    double below = Math.min(1, display.call(Map.of("v", light.call(args))));
                    args.put("c", 0.0);
                    side[c] = display.call(Map.of("v", w[c] * light.call(args)));
                    args.put("tau", 0.0);
                    args.put("c", 2.0 / 3);
                    top[c] = light.call(args);
                    sum += Math.max(below, Math.min(1, display.call(Map.of("v", top[c]))));
                }
                if (mu > 0.9 && t <= 10) {
                    // на экран — как у пелены (к белому, плечо): под шаром в первые секунды пересвечено
                    float[] shown = new float[3];
                    PallLight.screen(top, shown);
                    for (int c = 0; c < 3; c++) {
                        assertEquals(high[c], shown[c], 0.03 + 0.3 * high[c], t + " с, " + r + " блоков, сверху, канал " + c);
                        checked++;
                    }
                }
                // пожары светят ярче шара (fx_pall: главный свет — снизу; оба — линейный свет на белой стене)
                if (flames && fires > (luma[0] * wall[4] + luma[1] * wall[5] + luma[2] * wall[6]) * a) {
                    String at = t + " с, " + r + " блоков, у пола сбоку: ";
                    for (int c = 0; c < 3; c++) assertTrue(side[c] <= fire[c] + 0.03, at + "ярче пламени в канале " + c + ", " + side[c]);
                    assertTrue(side[0] > side[1] && side[1] > side[2], at + "не цвета огня");
                    assertTrue(side[0] >= 0.6 * fire[0], at + "тёмная против пламени, " + side[0]);
                    burning++;
                }
                if (r < 1000) assertTrue(sum / 3 > 0.25, t + " с, " + r + " блоков: пелена у эпицентра тёмная, " + sum / 3);
            }
        }
        assertTrue(checked > 15, "сравнений с верхом стены " + checked);
        assertTrue(burning > 10, "сравнений у пола над пожарами " + burning);
    }

    /**
     * Крупный ореол слой эффектов рисует по формуле ({@code glowAlpha} в {@code fx.fsh}), мелкий — из картинки
     * {@code far/glow} ({@code tools/gen_particles.py}): это один и тот же ореол — середины пикселей картинки стоят на r от
     * −1 до 1 через 63 шага, у каждого пикселя формула даёт его значение (с точностью 8 бит).
     */
    @Test
    void glowFormulaIsThePicture() throws IOException {
        GlslFunction f = GlslFunction.parse(read("core/fx.fsh"), "glowAlpha");
        BufferedImage img;
        try (InputStream in = PallGlslTest.class.getResourceAsStream("/assets/incoming/textures/far/glow.png")) {
            assertNotNull(in, "far/glow.png");
            img = ImageIO.read(in);
        }
        int n = img.getWidth();
        assertEquals(64, n, "fx.fsh считает середины крайних пикселей по картинке 64×64");
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double x = -1 + 2.0 * i / (n - 1), y = -1 + 2.0 * j / (n - 1);
                double a = (img.getRGB(i, j) >>> 24) / 255.0;
                assertEquals(a, f.call(Map.of("r", Math.hypot(x, y))), 0.5 / 255 + 1e-4, "пиксель " + i + ", " + j);
            }
        }
    }

    /**
     * Как {@code fx_pall} складывает свет, и как он и {@code fx_pall_composite} переводят его в экран, — по тексту
     * шейдеров: части проверены по отдельности, а собирает их {@code main}, который тест не считает. Огонь в точке — все
     * огни вместе под одним пределом пламени (каждый в своём пределе, а сумма была ярче огня); пожары под пылью — тоже в
     * пределе; толща над точкой — весь столб слоя; свет пелены в экран — один раз на канал, ярче белого — в долю пикселя
     * при настоящем пропускании; тень на мире — один раз.
     */
    @Test
    void pallPutsItsLightTogetherOnce() {
        String march = read("core/fx_pall.fsh"), composite = read("core/fx_pall_composite.fsh");
        assertTrue(march.contains("vec3 fire = min(below + under * (glow + s.fire / max(s.albedo, vec3(1e-7))) + around, gFlame);"),
                "огонь в точке — одной суммой под пределом пламени");
        assertTrue(march.contains("vec3 emit = s.albedo * (field + beams + fire);"), "свет точки — рассеянный, пучки и огонь");
        assertTrue(march.contains("+ min(below * exp(-gSootK * self) + (0.5 + 0.75 * PALL_SOOT_G * mv) * glow + around, gFlame);"),
                "огонь на дыме шторма — одной суммой под пределом пламени");
        assertTrue(march.contains("burnLight = pallFlame(") && march.contains("s.burn += burnLight;"), "пожары под пылью — не ярче пламени");
        assertTrue(march.contains("s.tau += f.column.x + f.column.y + f.column.z;"), "толща над точкой — весь столб слоя");
        assertEquals(3, occurrences(march, "pallDisplay("), "свет пелены в экран — один раз на канал");
        assertTrue(march.contains("shoulder(vec3(pallDisplay(medium.r), pallDisplay(medium.g), pallDisplay(medium.b))) * shownCover"),
                "на выходе — экран с плечом");
        assertTrue(march.contains("shownCover = min(cover * lum, 1.0);"), "пересвет — в долю пикселя, не больше всего пикселя");
        assertTrue(march.contains("vec4 res = vec4(shown, min(transmit + lost, 1.0));"),
                "пропускание кадра — настоящее и при пересвете");
        assertEquals(4, occurrences(composite, "pallDisplay("), "тень на мире в экран — один раз, мир в свете шаров — раз на канал");
        assertTrue(composite.contains("float kept = mix(1.0, pallDisplay(shade(p, over)), seen * pallOpen(PallOpen, PallOpenAt, PallOpenWrap, p - 0.5 * dir));"),
                "тень на мире — в экран, вдали тает в дымке и тумане, под землёй её нет");
        assertTrue(composite.contains("pall.a *= kept;"), "без света шаров тень — множитель мира в смешивании");
        assertTrue(composite.contains("pall = vec4(pall.rgb + pall.a * shown, 0.0);"), "мир в свете шаров — за пеленой, пиксель целиком");
        assertEquals(scalar(march, "CROSS"), scalar(composite, "CROSS"), "пересвет к белому — как у вещества");
        assertArrayEquals(vec3(march, "LUMA"), vec3(composite, "LUMA"));
    }

    private static int occurrences(String text, String what) {
        int n = 0;
        for (int i = text.indexOf(what); i >= 0; i = text.indexOf(what, i + 1)) n++;
        return n;
    }

    /** Константа {@code const float name = a;} шейдера. */
    private static double scalar(String source, String name) {
        Matcher m = Pattern.compile("const\\s+float\\s+" + name + "\\s*=\\s*([^;]+);").matcher(source);
        if (!m.find()) throw new AssertionError("нет const float " + name);
        return Double.parseDouble(m.group(1).strip());
    }

    /** Константа {@code const vec3 name = vec3(a, b, c);} шейдера. */
    private static double[] vec3(String source, String name) {
        Matcher m = Pattern.compile("const\\s+vec3\\s+" + name + "\\s*=\\s*vec3\\(([^)]*)\\)").matcher(source);
        if (!m.find()) throw new AssertionError("нет const vec3 " + name);
        String[] p = m.group(1).split(",");
        return new double[]{Double.parseDouble(p[0].strip()), Double.parseDouble(p[1].strip()), Double.parseDouble(p[2].strip())};
    }

    @Test
    void fireLawsMatchJava() {
        GlslFunction bend = GlslFunction.parse(GLSL, "fireBend"), width = GlslFunction.parse(GLSL, "fireWidth"), lod = GlslFunction.parse(GLSL, "fireLod"),
                glow = GlslFunction.parse(GLSL, "fireGlowLod"), column = GlslFunction.parse(GLSL, "fireColumn");
        for (double h = -60; h <= 400; h += 0.5) {
            assertEquals(FireStorm.bend(h, FireStorm.BEND_HEIGHT), bend.call(Map.of("h", h, "hb", FireStorm.BEND_HEIGHT)), 1e-9, "h = " + h);
            for (double cell : new double[]{4, 32}) {
                assertEquals(FireStorm.width(h, cell), width.call(Map.of("h", h, "cell", cell)), 1e-9, "h = " + h);
                assertEquals(FireStorm.glowLod(h, cell), glow.call(Map.of("h", h, "cell", cell)), 1e-9, "h = " + h);
                double w = FireStorm.width(h, cell);
                assertEquals(FireStorm.lod(w, cell), lod.call(Map.of("w", w, "cell", cell)), 1e-9, "w = " + w);
            }
            assertEquals(FireStorm.column(h, 260), column.call(Map.of("h", h, "top", 260.0)), 1e-9, "h = " + h);
        }
    }
}
