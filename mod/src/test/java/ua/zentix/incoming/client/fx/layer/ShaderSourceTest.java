package ua.zentix.incoming.client.fx.layer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Шейдеры мода — в том виде, который переживает Veil: его несёт Sable, и он пересобирает все шейдеры {@code core} своим
 * разбором GLSL (glsl-processor 0.2.3), печатая их заново. Условное выражение внутри другого он печатает без скобок:
 * {@code a * (c ? x : y)} выходит {@code a * c ? x : y} — другим выражением (свет стены пыли на пелене стал от плотности
 * не зависеть и выходил в тысячи раз ярче: белый кадр). Условие — только целой правой частью, возвратом или аргументом
 * вызова; внутри выражения — {@code if} или {@code mix}. Увеличение отдельной инструкцией ({@code n++;}) он печатает без
 * «;» — шейдер не собирается (ERROR «Failed to recompile vanilla shader», остаётся прежний): только {@code n += 1;}.
 */
class ShaderSourceTest {
    private static final Pattern TOKEN = Pattern.compile(
            "[A-Za-z_]\\w*|\\d+\\.?\\d*(?:[eE][-+]?\\d+)?|\\.\\d+(?:[eE][-+]?\\d+)?|[-+*/%<>=!&|^]=|&&|\\|\\||\\+\\+|--|\\S");
    /** Где кончается условное выражение слева: оно здесь целое. */
    private static final Set<String> WHOLE = Set.of(",", ";", "{", "}", "=", "+=", "-=", "*=", "/=", "return", "?", ":");

    /** Инструкция-увеличение: {@code n++;}, {@code --k;} ({@code i++} в заголовке {@code for} — без «;» следом). */
    private static final Pattern INCREMENT = Pattern.compile("(\\w\\+\\+|\\w--|\\+\\+\\w|--\\w)\\s*;");

    /**
     * Свет огня на дыме и частицах ({@code fx.vsh}): ночь уже в привыкании глаза ({@code Sight.exposure} в FxBallLight),
     * второй раз её не множить — свет шара на частице не умножается на свет неба, а на клубе (цвет вершины которого уже
     * умножен на свет неба) делится на его линейную величину. Иначе при честном привыкании огонь ночью на дыме в 3–14 раз
     * слабее, чем днём при том же свете у глаза (пыль у бомбы ночью — розово-сиреневая, ревью кадров 22.10).
     */
    @Test
    void fireLightOnSmokeCountsTheNightOnce() throws IOException {
        String vsh = Files.readString(Path.of("src/main/resources/assets/incoming/shaders/core/fx.vsh"));
        String balls = Files.readString(Path.of("src/main/resources/assets/incoming/shaders/include/fx_balls.glsl"));
        assertTrue(vsh.contains("vec3 balls(vec3 p)"), "balls — без множителя света неба");
        assertTrue(!vsh.contains("gain * FxBallLight"), "свет шара не умножается на свет неба");
        assertTrue(balls.contains("/ skyLinear"), "свет шара на клубе — в долях линейного света неба");
    }

    @Test
    void noConditionalInsideAnExpression() throws IOException, URISyntaxException {
        List<String> bad = everyShader(ShaderSourceTest::nested);
        assertTrue(bad.isEmpty(), "«?:» внутри выражения (Veil потеряет скобки): " + bad);
    }

    @Test
    void noIncrementStatement() throws IOException, URISyntaxException {
        List<String> bad = everyShader(ShaderSourceTest::increments);
        assertTrue(bad.isEmpty(), "«n++;» (Veil потеряет «;», пишите «n += 1;»): " + bad);
    }

    @Test
    void findsTheIncrementsVeilBreaks() {
        assertEquals(List.of(1, 3), increments("""
                n++;
                for (int i = 0; i < 4; i++) {
                    --k;
                    n += 1; // n++; в комментарии
                }
                """));
    }

    /**
     * Каждая переменная {@code uniform} программы {@code core} (её вершинный и пиксельный шейдер) объявлена в её JSON:
     * сэмплер — в {@code samplers}, остальное — в {@code uniforms}, и в JSON нет лишнего. Необъявленную ваниль не задаёт
     * и не привязывает: сэмплер читал бы чужую текстуру из своего блока, число — ноль (новая карта или число слоя,
     * добавленные в один проход и забытые в другом). Кроме {@code sampler3D}: их ваниль не привязывает, слой ставит их сам
     * ({@code PallPass}). Переменные файлов {@code include}, которые шейдер берёт {@code #moj_import}, — тоже его
     * ({@code pall_front.glsl}: пелена перед точкой квадратам слоя и пробам).
     */
    @Test
    void everyUniformIsBoundInItsProgram() throws IOException, URISyntaxException {
        Path core = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders/core").toURI());
        Pattern uniform = Pattern.compile("(?m)^\\s*uniform\\s+(\\w+)\\s+(\\w+)\\s*;");
        List<String> bad = new ArrayList<>();
        int programs = 0;
        try (Stream<Path> list = Files.list(core)) {
            for (Path json : list.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                programs++;
                JsonObject program = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                Set<String> samplers = names(program, "samplers"), numbers = names(program, "uniforms"), used = new HashSet<>();
                String name = json.getFileName().toString();
                for (String[] stage : new String[][]{{"vertex", ".vsh"}, {"fragment", ".fsh"}}) {
                    String file = program.get(stage[0]).getAsString().replaceFirst("^incoming:", "") + stage[1];
                    Matcher m = uniform.matcher(blankComments(withImports(core.resolveSibling("include"), Files.readString(core.resolve(file)), new HashSet<>())));
                    Set<String> declared = new HashSet<>();
                    while (m.find()) {
                        String type = m.group(1), u = m.group(2);
                        // объявленный и в include, и в самом шейдере — переопределение, шейдер не собирается
                        if (!declared.add(u)) bad.add(name + ": " + file + " объявлен дважды " + u);
                        used.add(u);
                        if (type.equals("sampler3D")) continue;
                        if (!(type.startsWith("sampler") ? samplers : numbers).contains(u)) bad.add(name + ": " + file + " " + type + " " + u);
                    }
                }
                for (String u : samplers) if (!used.contains(u)) bad.add(name + ": лишний сэмплер " + u);
                for (String u : numbers) if (!used.contains(u)) bad.add(name + ": лишнее число " + u);
            }
        }
        assertTrue(programs > 5, "программ " + programs);
        assertTrue(bad.isEmpty(), "uniform без JSON или JSON без uniform: " + bad);
    }

    /**
     * Пыль пелены — вещество наружного воздуха: луч пелены ({@code fx_pall}: шаг {@code pallStep}), пелена перед
     * квадратами слоя и пробами ({@code pall_front.glsl}: {@code pallThrough}) и тень пелены на мире
     * ({@code fx_pall_composite}) берут долю наружного воздуха в точке ({@code pallOpen}, {@link OpenAir}). Без неё пелена
     * стояла в штольне и пещере под ней — луч в несколько блоков до стены шёл сквозь густую пыль (сценарий клиента
     * {@code pall-mine}).
     */
    @Test
    void pallKnowsWhereTheOpenAirIs() throws IOException, URISyntaxException {
        Path core = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders/core").toURI()), include = core.resolveSibling("include");
        assertTrue(body(Files.readString(core.resolve("fx_pall.fsh")), "void pallStep(").contains("pallOpenHere("), "fx_pall: pallStep без pallOpen");
        assertTrue(body(Files.readString(include.resolve("pall_front.glsl")), "float pallThrough(").contains("pallOpenHere("), "pall_front: pallThrough без pallOpen");
        assertTrue(body(Files.readString(core.resolve("fx_pall_composite.fsh")), "void main(").contains("pallOpenHere("), "fx_pall_composite: тень без pallOpen");
    }

    /**
     * Солнце над пеленой проходит грибы на своей прямой, как над миром: шаг пелены ({@code fx_pall}: {@code pallStep}) берёт
     * свет солнца через карту тени грибов ({@code mushShadow}), тот же, что вывод на кадр у мира. Без этого шапка
     * закрывала от солнца ножку, а ковёр пыли под ней светился полным солнцем: серая ножка на светлом буром ковре с резкой
     * границей (скрины хоста 3.6.0). Пучок и рассеянный свет солнца в шаге — только через неё.
     */
    @Test
    void pallUnderTheCapIsInItsShadow() throws IOException, URISyntaxException {
        Path core = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders/core").toURI());
        String pall = Files.readString(core.resolve("fx_pall.fsh")), step = blankComments(body(pall, "vec4 pallSource("));
        assertTrue(blankComments(body(pall, "vec3 pallSunAt(")).contains("mushShadowFrom("), "fx_pall: солнце над пеленой без тени грибов");
        assertTrue(step.contains("pallSunAt("), "fx_pall: общий источник пыли без тени грибов");
        assertFalse(step.contains("PallSunLight"), "fx_pall: солнце в pallStep — только сквозь тень грибов");
        assertTrue(Files.readString(core.resolve("fx_pall_composite.fsh")).contains("mushShadow("), "мир — в той же тени");
    }

    /** Одинаковая пыль на стыке требует общего источника света, а не двух независимых решателей освещения. */
    @Test
    void inflowAndCarpetUseOneLightingSource() throws IOException, URISyntaxException {
        Path core = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders/core").toURI());
        String pall = Files.readString(core.resolve("fx_pall.fsh"));
        String layer = blankComments(body(pall, "void pallStep(")), stem = blankComments(body(pall, "vec4 mushAt("));
        assertTrue(layer.contains("pallSource(p, cone, vec4(0.0), dustLight)"));
        assertFalse(stem.contains("pallSource("), "в шаге гриба нет встроенного расчёта пелены");
        assertTrue(stem.contains("mushFootLight(k, m, q)"));
        assertTrue(pall.contains("pallSource(p, cone, vec4(m.base, sigma), light)"), "предпроход считает тот же источник");
        assertTrue(stem.contains("mushFootLightShare("));
        assertTrue(stem.contains("mix(source, carpetLight, fromCarpet)"));
        String dense = blankComments(body(pall, "vec4 mushDense("));
        assertTrue(dense.contains("mushInflowLobes("), "подсос — клубами ковра, не растянутыми струями всей ножки");
        assertFalse(dense.contains("mushStemLobes("), "исходный узор ножки не переотображается");
        String slot = blankComments(body(pall, "void slot("));
        assertEquals(1, slot.split("carpetFlowAt\\(", -1).length - 1, "карта потока общая у всех смещённых выборок слота");
        assertTrue(slot.contains("f.column.x += up") && slot.contains("f.slab.x += total"), "поднятая пыль затеняет сама себя");
        String flow = blankComments(body(pall, "vec4 carpetFlow("));
        assertFalse(flow.contains("for ("), "на шаге нет обхода массива грибов");
        String mush = Files.readString(core.resolve("../include/mushroom.glsl"));
        String gap = blankComments(body(mush, "float mushGap("));
        assertTrue(gap.contains("mushFootBandAt(m, q.y)") && gap.contains("q.y < m.footMerge"));

    }

    /**
     * Кадр пелены мельче полного: у края предмета на фоне пелены пелена фона не ложится на предмет. Накопление
     * ({@code fx_pall_resolve}) берёт прошлое в решётке лучей ({@code pallLattice}) и зажимает его соседями того же
     * расстояния до мира ({@code pallAlike}); вывод ({@code fx_pall_composite}: {@code upsample}) у предмета уже шага
     * лучей ищет ближайший по расстоянию луч и досчитывает остаток по полю без шума ({@code pallTau}). Без этого решётка
     * в 5 блоках тонула в пелене за ней, а по контуру блоков шла кайма её цвета (сценарий клиента {@code pall-mine},
     * {@code INCOMING_PALL=LOW}).
     */
    @Test
    void pallKeepsTheBackgroundOffTheEdges() throws IOException, URISyntaxException {
        Path core = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders/core").toURI());
        String resolve = body(Files.readString(core.resolve("fx_pall_resolve.fsh")), "void main(");
        assertTrue(resolve.contains("pallLattice(") && resolve.contains("pallAlike("), "fx_pall_resolve: прошлое не в решётке лучей или пределы без расстояния");
        String up = body(Files.readString(core.resolve("fx_pall_composite.fsh")), "vec4 upsample(");
        assertTrue(up.contains("pallLattice(") && up.contains("pallAlike(") && up.contains("pallTau("), "fx_pall_composite: upsample без решётки, сходства или остатка по полю");
    }

    /**
     * Вызов функции из {@code include} — с тем числом аргументов, что у неё объявлено. Шейдер с неверным вызовом не
     * собирается только в игре (сборка и GameTest шейдеры не компилируют), и проход пропадает целиком: после слияния, что
     * добавило {@code pallField} аргумент, вызов с прежним числом в выводе пелены на кадр снял бы всю пелену.
     */
    @Test
    void everyCallMatchesItsDeclaration() throws IOException, URISyntaxException {
        Path core = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders/core").toURI()), include = core.resolveSibling("include");
        java.util.Map<String, Integer> arity = new java.util.HashMap<>();
        Pattern decl = Pattern.compile("(?m)^\\w+\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*\\{");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> a = Files.list(include); Stream<Path> b = Files.list(core)) {
            files.addAll(a.filter(f -> f.toString().endsWith(".glsl")).sorted().toList());
            files.addAll(b.filter(f -> f.toString().endsWith(".fsh") || f.toString().endsWith(".vsh")).sorted().toList());
        }
        for (Path f : files) {
            if (!f.getParent().equals(include)) continue;
            Matcher m = decl.matcher(blankComments(Files.readString(f)));
            while (m.find()) arity.put(m.group(1), m.group(2).isBlank() ? 0 : m.group(2).split(",").length);
        }
        assertTrue(arity.containsKey("pallField") && arity.containsKey("pallOpen"), "функции include не найдены: " + arity.keySet());
        List<String> bad = new ArrayList<>();
        for (Path f : files) {
            String text = blankComments(Files.readString(f));
            for (var e : arity.entrySet()) {
                Matcher m = Pattern.compile("(?<![\\w.])" + e.getKey() + "\\s*\\(").matcher(text);
                while (m.find()) {
                    // объявление самой функции — не вызов
                    int line = text.lastIndexOf('\n', m.start()) + 1;
                    if (decl.matcher(text.substring(line, Math.min(text.length(), text.indexOf('\n', m.end()) < 0 ? text.length() : text.indexOf('\n', m.end()) + 1))).lookingAt()) continue;
                    int depth = 1, args = 0, i = m.end();
                    boolean any = false;
                    for (; i < text.length() && depth > 0; i++) {
                        char c = text.charAt(i);
                        if (c == '(') depth++;
                        else if (c == ')') depth--;
                        else if (c == ',' && depth == 1) args++;
                        if (depth > 0 && !Character.isWhitespace(c)) any = true;
                    }
                    int n = any ? args + 1 : 0;
                    if (n != e.getValue()) bad.add(f.getFileName() + ": " + e.getKey() + " с " + n + " аргументами, объявлено " + e.getValue());
                }
            }
        }
        assertTrue(bad.isEmpty(), "вызовы не по объявлению: " + bad);
    }

    /**
     * Каждая функция шейдера вызвана после своего объявления — в тексте программы, как его собирает ваниль (файлы
     * {@code #moj_import} на их местах): GLSL не знает функции, объявленной ниже вызова, и шейдер не собирается — а
     * разбор законов по одной функции ({@code GlslFunction}) этого не видит (подсос у основания ножки звал
     * {@code mushStemSigma} выше её определения: не собирались все восемь программ с грибом).
     */
    @Test
    void everyFunctionIsDeclaredBeforeItsCall() throws IOException, URISyntaxException {
        Path core = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders/core").toURI());
        List<String> bad = new ArrayList<>();
        int files = 0;
        try (Stream<Path> list = Files.list(core)) {
            for (Path p : list.filter(f -> f.toString().matches(".*\\.(fsh|vsh)")).sorted().toList()) {
                files++;
                for (String call : beforeDeclaration(withImports(core.resolveSibling("include"), Files.readString(p), new HashSet<>()))) {
                    bad.add(p.getFileName() + ": " + call);
                }
            }
        }
        assertTrue(files > 5, "шейдеров " + files);
        assertTrue(bad.isEmpty(), "функция вызвана выше своего объявления: " + bad);
    }

    @Test
    void findsACallBeforeTheDeclaration() {
        assertEquals(List.of("b"), beforeDeclaration("""
                float c(float x);
                float a(float x) {
                    return b(x) + c(x) + exp(x);
                }
                float b(float x) {
                    return x;
                }
                float c(float x) {
                    return a(x); // b(x) в комментарии
                }
                """));
    }

    /**
     * Имена функций своего текста, вызванных выше первого объявления (определения или прототипа): объявление верхнего
     * уровня пишется с начала строки (как проверяет {@link #everyBodyIsClosed}).
     */
    private static List<String> beforeDeclaration(String source) {
        String text = blankComments(source);
        Matcher d = Pattern.compile("(?m)^[A-Za-z_]\\w*[ \\t]+([A-Za-z_]\\w*)[ \\t]*\\(").matcher(text);
        java.util.Map<String, Integer> declared = new java.util.HashMap<>();
        while (d.find()) declared.putIfAbsent(d.group(1), d.start(1));
        List<String> bad = new ArrayList<>();
        Matcher c = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\(").matcher(text);
        while (c.find()) {
            Integer at = declared.get(c.group(1));
            if (at != null && c.start(1) < at && !bad.contains(c.group(1))) bad.add(c.group(1));
        }
        return bad;
    }

    /** Тело функции, что объявлена с {@code head}. */
    private static String body(String source, String head) {
        int at = source.indexOf(head);
        assertTrue(at >= 0, "нет " + head);
        int open = source.indexOf('{', at), depth = 1, i = open + 1;
        for (; depth > 0; i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}') depth--;
        }
        return source.substring(open, i);
    }

    /** Текст шейдера с файлами {@code #moj_import <incoming:…>} на их местах (каждый раз — один, как у ванили). */
    @Test
    void footLightTableDimensionsMatchItsShader() throws IOException, URISyntaxException {
        Path core = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders/core").toURI());
        String source = Files.readString(core.resolve("fx_pall.fsh"));
        for (Object[] pair : new Object[][]{{"FOOT_LIGHT_AZ", PallPass.FOOT_LIGHT_AZ}, {"FOOT_LIGHT_R", PallPass.FOOT_LIGHT_R}, {"FOOT_LIGHT_H", PallPass.FOOT_LIGHT_H}}) {
            Matcher m = Pattern.compile("const int " + pair[0] + " = (\\d+);").matcher(source);
            assertTrue(m.find(), pair[0].toString());
            assertEquals(pair[1], Integer.valueOf(m.group(1)));
        }
    }

    @Test
    void relativeShaderImportKeepsItsExplicitNamespace() throws IOException, URISyntaxException {
        Path include = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders/include").toURI());
        assertTrue(withImports(include, "#moj_import \"incoming:fx_pall.fsh\"", new HashSet<>()).contains("vec4 pallSource("));
        assertFalse(withImports(include, "#moj_import \"fx_pall.fsh\"", new HashSet<>()).contains("vec4 pallSource("));
    }

    private static String withImports(Path include, String source, Set<String> seen) throws IOException {
        return withImportsFrom(include, include.resolveSibling("core"), source, seen);
    }

    /** Quoted imports are relative to the importing file, as in Mojang's GlslPreprocessor. */
    private static String withImportsFrom(Path include, Path local, String source, Set<String> seen) throws IOException {
        Matcher m = Pattern.compile("(?m)^\\s*#moj_import\\s*(?:<incoming:([\\w./]+)>|\"([\\w:./]+)\")").matcher(source);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, "");
            // A quoted import without a namespace means minecraft:, not the namespace of its parent shader.
            if (m.group(2) != null && !m.group(2).startsWith("incoming:")) {
                out.append(m.group());
                continue;
            }
            Path file = (m.group(1) != null ? include.resolve(m.group(1)) : local.resolve(m.group(2).substring("incoming:".length()))).normalize();
            if (seen.add(file.toString())) out.append(withImportsFrom(include, file.getParent(), Files.readString(file), seen));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static Set<String> names(JsonObject program, String list) {
        Set<String> out = new HashSet<>();
        if (program.has(list)) for (JsonElement e : program.getAsJsonArray(list)) out.add(e.getAsJsonObject().get("name").getAsString());
        return out;
    }

    /**
     * Каждое тело функции закрыто до следующего объявления верхнего уровня (оно пишется с начала строки): незакрытое
     * тело ни драйвер, ни Veil не разбирают, а пелена без шейдера пропадает целиком (слияние потеряло «}» — кадры без
     * гриба, стены и пелены).
     */
    @Test
    void everyBodyIsClosed() throws IOException, URISyntaxException {
        List<String> bad = everyShader(ShaderSourceTest::unclosed);
        assertTrue(bad.isEmpty(), "объявление внутри незакрытого тела (или «{» без пары): " + bad);
    }

    @Test
    void findsAnUnclosedBody() {
        assertEquals(List.of(4), unclosed("""
                float f(float x) {
                    return x;

                float g;
                void main() {
                }
                """));
        assertEquals(List.of(4), unclosed("void main() {\n    if (a) {\n}\n"));
        assertEquals(List.of(), unclosed("struct S {\n    float a;\n};\nvoid main() {\n}\n"));
    }

    /** Строки объявлений верхнего уровня внутри тела, и последняя строка, если «{» осталась без пары. */
    private static List<Integer> unclosed(String source) {
        String[] lines = blankComments(source).split("\n", -1);
        List<Integer> found = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < lines.length; i++) {
            if (depth > 0 && lines[i].matches("[A-Za-z_].*")) {
                found.add(i + 1);
                depth = 0;
            }
            for (char c : lines[i].toCharArray()) depth += c == '{' ? 1 : c == '}' ? -1 : 0;
        }
        if (depth != 0) found.add(lines.length);
        return found;
    }

    /** Строки всех шейдеров мода, которые нашла проверка, — «файл:строка». */
    private static List<String> everyShader(Function<String, List<Integer>> check) throws IOException, URISyntaxException {
        Path root = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders").toURI());
        List<String> bad = new ArrayList<>();
        int files = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(f -> f.toString().matches(".*\\.(fsh|vsh|glsl)")).toList()) {
                files++;
                for (int line : check.apply(Files.readString(p))) bad.add(root.relativize(p) + ":" + line);
            }
        }
        assertTrue(files > 5, "шейдеров " + files);
        return bad;
    }

    /** Строки с инструкцией-увеличением. */
    private static List<Integer> increments(String source) {
        String[] lines = blankComments(source).split("\n", -1);
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) if (INCREMENT.matcher(lines[i]).find()) found.add(i + 1);
        return found;
    }

    @Test
    void findsTheConditionalsVeilBreaks() {
        assertEquals(List.of(1, 2, 3, 8), nested("""
                wall += (a + b) * (burn > lit ? FIRE : H.rgb) * heat;
                float t = max((burn > lit ? x : y) + clump, 0.0);
                float w = (i == 0 ? 1.0 - f.x : f.x) * (j == 0 ? 1.0 - f.y : f.y);
                float d = depth >= 1.0 ? 1e9 : along(InvProj, ndc, depth * 2.0 - 1.0);
                float z = along(DhInvProj, ndc, DhDepth.y > 0.5 ? lod * 2.0 - 1.0 : lod);
                vec4 tex = n > 0.5 ? a : n < -0.5 ? b : c; // (x ? y : z) в комментарии
                return ok ? v : vec4(0.0);
                return(c ? a : b) * y;
                """));
    }

    /** Строки, где «?» стоит внутри скобок выражения (не вызова и не конструктора). */
    private static List<Integer> nested(String source) {
        String s = blankComments(source);
        List<String> tokens = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) {
            tokens.add(m.group());
            at.add(m.start());
        }
        List<Integer> lines = new ArrayList<>();
        for (int q = 0; q < tokens.size(); q++) {
            if (!tokens.get(q).equals("?")) continue;
            int depth = 0, i = q - 1;
            for (; i >= 0; i--) {
                String x = tokens.get(i);
                if (x.equals(")") || x.equals("]")) {
                    depth++;
                } else if (x.equals("(") || x.equals("[")) {
                    if (depth == 0) break;
                    depth--;
                } else if (depth == 0 && WHOLE.contains(x)) {
                    break;
                }
            }
            boolean group = i >= 0 && tokens.get(i).equals("(")
                    && (i == 0 || tokens.get(i - 1).equals("return") || !tokens.get(i - 1).matches("[A-Za-z_]\\w*"));
            if (group) {
                int line = (int) s.substring(0, at.get(q)).chars().filter(c -> c == '\n').count() + 1;
                if (!lines.contains(line)) lines.add(line);
            }
        }
        return lines;
    }

    /** Комментарии и строки препроцессора — пробелами (строки остаются на месте). */
    private static String blankComments(String source) {
        StringBuilder b = new StringBuilder(source);
        Matcher m = Pattern.compile("//[^\n]*|/\\*.*?\\*/|(?m)^\\s*#[^\n]*", Pattern.DOTALL).matcher(source);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) if (b.charAt(i) != '\n') b.setCharAt(i, ' ');
        }
        return b.toString();
    }
}
