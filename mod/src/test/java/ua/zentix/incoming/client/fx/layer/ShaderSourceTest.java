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
  * The mod's shaders, in the form that survives Veil: Sable carries it, and it rebuilds all {@code core} shaders with
  * its parsing of GLSL (glsl-processor 0.2.3), printing them again. A conditional expression nested in another it
  * prints without brackets: {@code a * (c ? x : y)} comes out as {@code a * c ? x : y} — a different expression (the
  * light of a dust wall on the pall became independent of density and came out thousands of times brighter: a white
  * frame). A condition is allowed only as a whole right-hand side, a return value or an argument of a call; inside an
  * expression, use {@code if} or {@code mix}. An increment as a separate statement ({@code n++;}) it prints without “;”
  * — the shader does not build (ERROR “Failed to recompile vanilla shader”, the previous one stays): only {@code n +=
  * 1;}.
 */
class ShaderSourceTest {
    private static final Pattern TOKEN = Pattern.compile(
            "[A-Za-z_]\\w*|\\d+\\.?\\d*(?:[eE][-+]?\\d+)?|\\.\\d+(?:[eE][-+]?\\d+)?|[-+*/%<>=!&|^]=|&&|\\|\\||\\+\\+|--|\\S");
    /** Where the conditional expression on the left ends: here it is whole. */
    private static final Set<String> WHOLE = Set.of(",", ";", "{", "}", "=", "+=", "-=", "*=", "/=", "return", "?", ":");

    /** Increment statement: {@code n++;}, {@code --k;} ({@code i++} in the header of {@code for} — no “;” after it). */
    private static final Pattern INCREMENT = Pattern.compile("(\\w\\+\\+|\\w--|\\+\\+\\w|--\\w)\\s*;");

    /**
      * Fire light on smoke and particles ({@code fx.vsh}): night is already in the eye's adaptation ({@code
      * Sight.exposure} in FxBallLight), do not multiply it a second time: the fireball light on a particle is not
      * multiplied by the sky light, but on a puff (whose vertex color is already multiplied by the sky light) it is
      * divided by its linear value. Otherwise, with honest adaptation, fire at night on smoke is 3–14 times weaker than
      * by day at the same light at the eye (dust from a bomb at night is pink-lilac, frame review 22.10).
     */
    @Test
    void fireLightOnSmokeCountsTheNightOnce() throws IOException {
        String vsh = Files.readString(Path.of("src/main/resources/assets/incoming/shaders/core/fx.vsh"));
        String balls = Files.readString(Path.of("src/main/resources/assets/incoming/shaders/include/fx_balls.glsl"));
        assertTrue(vsh.contains("vec3 balls(vec3 p)"), "balls — without the sky-light factor");
        assertTrue(!vsh.contains("gain * FxBallLight"), "the fireball light is not multiplied by the sky light");
        assertTrue(balls.contains("/ skyLinear"), "fireball light on a puff — in fractions of the linear sky light");
    }

    @Test
    void noConditionalInsideAnExpression() throws IOException, URISyntaxException {
        List<String> bad = everyShader(ShaderSourceTest::nested);
        assertTrue(bad.isEmpty(), "“?:” inside an expression (Veil loses the brackets): " + bad);
    }

    @Test
    void noIncrementStatement() throws IOException, URISyntaxException {
        List<String> bad = everyShader(ShaderSourceTest::increments);
        assertTrue(bad.isEmpty(), "“n++;” (Veil loses the “;”, write “n += 1;”): " + bad);
    }

    @Test
    void findsTheIncrementsVeilBreaks() {
        assertEquals(List.of(1, 3), increments("""
                n++;
                for (int i = 0; i < 4; i++) {
                    --k;
                    n += 1; // n++; in a comment
                }
                """));
    }

    /**
      * Every {@code uniform} variable of a {@code core} program (its vertex and pixel shader) is declared in its JSON:
      * a sampler goes in {@code samplers}, everything else in {@code uniforms}, and the JSON has nothing extra. An
      * undeclared vanilla uniform is not set or bound: a sampler would read someone else's texture from its own block,
      * a number would be zero (a new map or a layer number added in one pass and forgotten in another). Except {@code
      * sampler3D}: vanilla does not bind them, the layer sets them itself ({@code PallPass}). The variables of {@code
      * include} files that a shader takes via {@code #moj_import} are its too ({@code pall_front.glsl}: the pall in
      * front of a point for the layer's quads and probes).
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
                        // declared both in an include and in the shader itself is a redefinition: the shader does not
                        // build
                        if (!declared.add(u)) bad.add(name + ": " + file + " declared twice " + u);
                        used.add(u);
                        // the layer binds these itself: 3D textures and the half-resolution tile cuts and quad
                        // rectangles (FxQuads.CUT_UNIT, RECT_UNIT)
                        if (type.equals("sampler3D") || u.equals("HalfCut") || u.equals("HalfRect")) continue;
                        if (!(type.startsWith("sampler") ? samplers : numbers).contains(u)) bad.add(name + ": " + file + " " + type + " " + u);
                    }
                }
                for (String u : samplers) if (!used.contains(u)) bad.add(name + ": extra sampler " + u);
                for (String u : numbers) if (!used.contains(u)) bad.add(name + ": extra number " + u);
            }
        }
        assertTrue(programs > 5, "programs " + programs);
        assertTrue(bad.isEmpty(), "uniform without JSON or JSON without uniform: " + bad);
    }

    /**
      * Pall dust is matter of outside air: the pall's ray ({@code fx_pall}: step {@code pallStep}), the pall in front
      * of the layer's quads and probes ({@code pall_front.glsl}: {@code pallThrough}) and the pall's shadow on the
      * world ({@code fx_pall_composite}) take the share of outside air at the point ({@code pallOpen}, {@link
      * OpenAir}). Without it the pall stood in a mine shaft and a cave below it — a ray of several blocks to a wall
      * went through thick dust (client scenario {@code pall-mine}).
     */
    @Test
    void pallKnowsWhereTheOpenAirIs() throws IOException, URISyntaxException {
        Path core = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders/core").toURI()), include = core.resolveSibling("include");
        assertTrue(body(Files.readString(core.resolveSibling("include").resolve("fx_pall.glsl")), "void pallStep(").contains("pallOpenHere("), "fx_pall: pallStep without pallOpen");
        assertTrue(body(Files.readString(include.resolve("pall_front.glsl")), "float pallThrough(").contains("pallOpenHere("), "pall_front: pallThrough without pallOpen");
        assertTrue(body(Files.readString(core.resolve("fx_pall_composite.fsh")), "void main(").contains("pallOpenHere("), "fx_pall_composite: shadow without pallOpen");
    }

    /**
      * The sun over the pall passes the mushrooms on its straight line, as over the world: the pall's step ({@code
      * fx_pall}: {@code pallStep}) takes the sunlight through the mushroom shadow map ({@code mushShadow}), the same as
      * the output to the frame for the world. Without this the cap shadowed the stem from the sun, and the dust carpet
      * under it glowed in full sun: a gray stem on a light brown carpet with a sharp border (host screenshots 3.6.0).
      * The sun's beam and its diffuse light in the step pass only through it.
     */
    @Test
    void pallUnderTheCapIsInItsShadow() throws IOException, URISyntaxException {
        Path core = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders/core").toURI());
        String pall = Files.readString(core.resolveSibling("include").resolve("fx_pall.glsl")), step = blankComments(body(pall, "vec4 pallSource("));
        assertTrue(blankComments(body(pall, "vec3 pallSunAt(")).contains("mushShadowFrom("), "fx_pall: sun over the pall without mushroom shadow");
        assertTrue(step.contains("pallSunAt("), "fx_pall: shared dust source without mushroom shadow");
        assertFalse(step.contains("PallSunLight"), "fx_pall: sun in pallStep — only through the mushroom shadow");
        assertTrue(Files.readString(core.resolve("fx_pall_composite.fsh")).contains("mushShadow("), "the world — in the same shadow");
    }

    /** Identical dust at a joint requires one shared light source, not two independent lighting solvers. */
    @Test
    void inflowAndCarpetUseOneLightingSource() throws IOException, URISyntaxException {
        Path core = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders/core").toURI());
        String pall = Files.readString(core.resolveSibling("include").resolve("fx_pall.glsl"));
        String layer = blankComments(body(pall, "void pallStep(")), stem = blankComments(body(pall, "vec4 mushAt("));
        assertTrue(layer.contains("pallSource(p, cone, vec4(0.0), dustLight)"));
        assertFalse(stem.contains("pallSource("), "the mushroom step has no built-in pall calculation");
        assertTrue(stem.contains("mushFootLight(k, m, q)"));
        assertTrue(pall.contains("pallSource(p, cone, vec4(m.base, sigma), light)"), "the pre-pass computes the same source");
        assertTrue(stem.contains("mushFootLightShare("));
        assertTrue(stem.contains("mix(source, carpetLight, fromCarpet)"));
        String dense = blankComments(body(pall, "vec4 mushDense("));
        assertTrue(dense.contains("mushInflowLobes("), "the inflow — by carpet puffs, not by jets stretched over the whole stem");
        assertFalse(dense.contains("mushStemLobes("), "the original stem pattern is not re-rendered");
        String slot = blankComments(body(pall, "void slot("));
        assertEquals(1, slot.split("carpetFlowAt\\(", -1).length - 1, "the flow map is shared by all offset samples of the slot");
        assertTrue(slot.contains("f.column.x += up") && slot.contains("f.slab.x += total"), "lifted dust shades itself");
        assertTrue(slot.contains("mushFootRemoved(Sampler7, k, m, rho - r)"), "the column is limited by the radius of the narrowing band");
        assertTrue(slot.contains("mushFootColumn(Sampler7, k, m, q.y) - removed"));
        assertTrue(slot.contains("mushFootColumn(Sampler7, k, m, 0.0) - removed"), "the shared layer does not include dust outside the given radius");
        String flow = blankComments(body(pall, "vec4 carpetFlow("));
        assertFalse(flow.contains("for ("), "the step has no loop over the mushroom array");
        assertFalse(pall.contains("Mush gMush["), "constant parameters are not copied into each pixel's private memory");
        String mush = Files.readString(core.resolve("../include/mushroom.glsl"));
        String gap = blankComments(body(mush, "float mushGap("));
        assertTrue(gap.contains("mushFootBandAt(m, q.y)") && gap.contains("q.y < m.footMerge"));

    }

    /**
      * The pall's frame is smaller than the full one: at an object's edge against the pall, the background's pall does
      * not land on the object. Accumulation ({@code fx_pall_resolve}) takes the past in the lattice of rays ({@code
      * pallLattice}) and clamps it by the neighbors at the same distance to the world ({@code pallAlike}); the output
      * ({@code fx_pall_composite}: {@code upsample}) at an object narrower than the ray step looks for the nearest ray
      * by distance and computes the remainder over the noise-free field ({@code pallTau}). Without this the lattice in
      * 5 blocks sank into the pall behind it, and along the block outlines ran a fringe of its color (client scenario
      * {@code pall-mine}, {@code INCOMING_PALL=LOW}).
     */
    @Test
    void pallKeepsTheBackgroundOffTheEdges() throws IOException, URISyntaxException {
        Path core = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders/core").toURI());
        String resolve = body(Files.readString(core.resolve("fx_pall_resolve.fsh")), "void main(");
        assertTrue(resolve.contains("pallLattice(") && resolve.contains("pallAlike("), "fx_pall_resolve: the past is not in the lattice of rays, or the limits have no distance");
        String up = body(Files.readString(core.resolve("fx_pall_composite.fsh")), "vec4 upsample(");
        assertTrue(up.contains("pallLattice(") && up.contains("pallAlike(") && up.contains("pallTau("), "fx_pall_composite: upsample without the lattice, similarity or field remainder");
    }

    /**
      * A call to a function from {@code include} — with the number of arguments it is declared with. A shader with a
      * wrong call does not build only in the game (the build and GameTest do not compile shaders), and the pass
      * disappears entirely: after a merge that made {@code pallField} take an argument, a call with the old number in
      * the pall's output to the frame would have removed the whole pall.
     */
    @Test
    void everyCallMatchesItsDeclaration() throws IOException, URISyntaxException {
        Path core = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders/core").toURI()), include = core.resolveSibling("include");
        Pattern decl = Pattern.compile("(?m)^\\w+\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*\\{");
        List<Path> files;
        try (Stream<Path> b = Files.list(core)) {
            files = b.filter(f -> f.toString().endsWith(".fsh") || f.toString().endsWith(".vsh")).sorted().toList();
        }
        Set<String> found = new HashSet<>();
        List<String> bad = new ArrayList<>();
        // the whole program, with the include files in their places: a function's name is unique in the program, not in
        // all files (fx_depth has its own along, the pall has its own)
        for (Path f : files) {
            String text = blankComments(withImports(include, Files.readString(f), new HashSet<>()));
            java.util.Map<String, Integer> arity = new java.util.HashMap<>();
            Matcher d = decl.matcher(text);
            while (d.find()) arity.put(d.group(1), d.group(2).isBlank() ? 0 : d.group(2).split(",").length);
            found.addAll(arity.keySet());
            for (var e : arity.entrySet()) {
                Matcher m = Pattern.compile("(?<![\\w.])" + e.getKey() + "\\s*\\(").matcher(text);
                while (m.find()) {
                    // declaration of the function itself — not a call
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
                    if (n != e.getValue()) bad.add(f.getFileName() + ": " + e.getKey() + " with " + n + " arguments, declared " + e.getValue());
                }
            }
        }
        assertTrue(found.contains("pallField") && found.contains("pallOpen"), "include functions not found: " + found);
        assertTrue(bad.isEmpty(), "calls not matching the declaration: " + bad);
    }

    /**
      * Each shader function is called after its declaration — in the program's text, as vanilla assembles it (files
      * {@code #moj_import} in their places): GLSL does not know a function declared below its call, and the shader does
      * not build — and the per-function parsing of the laws ({@code GlslFunction}) does not see this (the inflow at the
      * base of the stem called {@code mushStemSigma} above its definition: all eight programs with the mushroom did not
      * build).
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
        assertTrue(files > 5, "shaders " + files);
        assertTrue(bad.isEmpty(), "function called above its declaration: " + bad);
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
                    return a(x); // b(x) in a comment
                }
                """));
    }

    /**
      * Names of functions of its own text called above the first declaration (definition or prototype): the top-level
      * declaration is written from the start of the line (as {@link #everyBodyIsClosed} checks).
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

    /** Body of the function declared with {@code head}. */
    private static String body(String source, String head) {
        int at = source.indexOf(head);
        assertTrue(at >= 0, "no " + head);
        int open = source.indexOf('{', at), depth = 1, i = open + 1;
        for (; depth > 0; i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}') depth--;
        }
        return source.substring(open, i);
    }

    @Test
    void footLightTableDimensionsMatchItsShader() throws IOException, URISyntaxException {
        Path core = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders/core").toURI());
        String source = Files.readString(core.resolveSibling("include").resolve("fx_pall.glsl"));
        for (Object[] pair : new Object[][]{{"FOOT_LIGHT_AZ", PallPass.FOOT_LIGHT_AZ}, {"FOOT_LIGHT_R", PallPass.FOOT_LIGHT_R}, {"FOOT_LIGHT_H", PallPass.FOOT_LIGHT_H}}) {
            Matcher m = Pattern.compile("const int " + pair[0] + " = (\\d+);").matcher(source);
            assertTrue(m.find(), pair[0].toString());
            assertEquals(pair[1], Integer.valueOf(m.group(1)));
        }
    }

    /**
      * A shader takes a file only as {@code #moj_import <incoming:name>} from {@code shaders/include}. An import in
      * quotes (relative to the file) the three loaders of the build read differently: NeoForge — as {@code
      * namespace:path} next to the shader, Forgified Fabric API (Fabric Rendering, {@code
      * ShaderProgramImportProcessorMixin}) adds to it the shader's namespace once more ({@code
      * incoming:incoming:fx_pall.fsh} — the program is not created), and Veil on rebuild glues it to the path of the
      * file itself. The base pre-pass ({@code fx_pall_foot}) took the pall's body in quotes and did not load in the
      * build: the light at the base of the stem went without the table (host log 3.8.0).
     */
    @Test
    void everyImportIsAnInclude() throws IOException, URISyntaxException {
        Path root = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders").toURI()), include = root.resolve("include");
        Pattern line = Pattern.compile("(?m)^\\s*#moj_import\\b.*$");
        List<String> bad = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(f -> f.toString().matches(".*\\.(fsh|vsh|glsl)")).sorted().toList()) {
                Matcher m = line.matcher(Files.readString(p));
                while (m.find()) {
                    Matcher i = INCLUDE.matcher(m.group());
                    if (!i.lookingAt()) bad.add(root.relativize(p) + ": " + m.group().trim());
                    else if (i.group(1) != null && !Files.isRegularFile(include.resolve(i.group(2)))) bad.add(root.relativize(p) + ": no include/" + i.group(2));
                }
            }
        }
        assertTrue(bad.isEmpty(), "import not <incoming:…> from include, or without a file: " + bad);
    }

    /**
      * Every {@code core} program as vanilla hands it out (the {@code #moj_import} files in their places) passes
      * parsing and printing by Veil (the glsl-processor of the version that is in the Sable build's Veil). Otherwise
      * Veil writes ERROR “Couldn't load vanilla shader” and leaves the shader without its rebuild. So it was with
      * {@code fx_pall}: “#” in a comment the parser counts as a node label, and with a label the printer takes the hash
      * of each function, and the hash of {@code return;} without a value throws ({@code GlslReturnNode.hashCode}, NPE).
     */
    @Test
    void veilPrintsEveryProgram() throws IOException, URISyntaxException {
        Path core = Path.of(ShaderSourceTest.class.getResource("/assets/incoming/shaders/core").toURI());
        List<String> bad = new ArrayList<>();
        int files = 0;
        try (Stream<Path> list = Files.list(core)) {
            for (Path p : list.filter(f -> f.toString().matches(".*\\.(fsh|vsh)")).sorted().toList()) {
                files++;
                String source = withImports(core.resolveSibling("include"), Files.readString(p), new HashSet<>());
                try {
                    io.github.ocelot.glslprocessor.api.GlslParser.preprocessParse(source, new java.util.HashMap<>()).toSourceString();
                } catch (Exception | Error e) {
                    bad.add(p.getFileName() + ": " + e);
                }
            }
        }
        assertTrue(files > 5, "shaders " + files);
        assertTrue(bad.isEmpty(), "Veil does not parse or print: " + bad);
    }

    /** {@code #moj_import <incoming:name>} — the mod's own file, {@code <name>} — vanilla ({@code light.glsl}). */
    private static final Pattern INCLUDE = Pattern.compile("\\s*#moj_import\\s*<(incoming:)?([\\w./]+)>");

    /** Shader text with the {@code #moj_import <…>} files in their places (each time — one, as in vanilla). */
    private static String withImports(Path include, String source, Set<String> seen) throws IOException {
        Matcher m = Pattern.compile("(?m)^" + INCLUDE.pattern()).matcher(source);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, "");
            if (m.group(1) == null) {
                if (!seen.add("minecraft:" + m.group(2))) continue;
                try (java.io.InputStream in = ShaderSourceTest.class.getResourceAsStream("/assets/minecraft/shaders/include/" + m.group(2))) {
                    assertTrue(in != null, "no vanilla include " + m.group(2));
                    out.append(withImports(include, new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), seen));
                }
                continue;
            }
            Path file = include.resolve(m.group(2)).normalize();
            if (seen.add(file.toString())) out.append(withImports(include, Files.readString(file), seen));
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
      * Every function body is closed before the next top-level declaration (it is written from the start of the line):
      * an unclosed body is parsed neither by the driver nor by Veil, and a pall without its shader disappears entirely
      * (a merge lost a “}” — frames without the mushroom, the walls, and the pall).
     */
    @Test
    void everyBodyIsClosed() throws IOException, URISyntaxException {
        List<String> bad = everyShader(ShaderSourceTest::unclosed);
        assertTrue(bad.isEmpty(), "declaration inside an unclosed body (or a “{” without a pair): " + bad);
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

    /** Lines of top-level declarations inside a body, and the last line if a “{” is left without a pair. */
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

    /** Lines of all the mod's shaders that the check found — “file:line”. */
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
        assertTrue(files > 5, "shaders " + files);
        return bad;
    }

    /** Lines with an increment statement. */
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
                vec4 tex = n > 0.5 ? a : n < -0.5 ? b : c; // (x ? y : z) in a comment
                return ok ? v : vec4(0.0);
                return(c ? a : b) * y;
                """));
    }

    /** Lines where “?” stands inside expression brackets (not a call or a constructor). */
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

    /** Comments and preprocessor lines — as spaces (the lines stay in place). */
    private static String blankComments(String source) {
        StringBuilder b = new StringBuilder(source);
        Matcher m = Pattern.compile("//[^\n]*|/\\*.*?\\*/|(?m)^\\s*#[^\n]*", Pattern.DOTALL).matcher(source);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) if (b.charAt(i) != '\n') b.setCharAt(i, ' ');
        }
        return b.toString();
    }
}
