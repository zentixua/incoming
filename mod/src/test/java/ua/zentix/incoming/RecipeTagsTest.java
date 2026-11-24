package ua.zentix.incoming;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.gametest.RecipeContract;

import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Recipes against the default datapack without a game server, the path the GameTest server (which always has Create)
 * cannot take: which recipes load with and without Create, what each common {@code c:} tag ingredient resolves to
 * through the tag files on the classpath (NeoForge's {@code data/c/tags/item}), and that every item a loaded recipe
 * names exists. Counts ({@link RecipeContract}) and patterns are pinned, so tags change no recipe's shape or yield.
 */
class RecipeTagsTest {
    private static final Map<String, Set<String>> TAGS = RecipeContract.TAGS;
    private static final Map<String, Integer> COUNTS = RecipeContract.COUNTS;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    void commonTagsResolveToTheVanillaMaterial() throws Exception {
        for (var e : TAGS.entrySet()) assertEquals(e.getValue(), resolve(e.getKey(), new TreeSet<>()), "#" + e.getKey());
    }

    @Test
    void everyLoadedRecipeResolvesWithAndWithoutCreate() throws Exception {
        Map<String, JsonObject> recipes = recipes();
        assertFalse(recipes.isEmpty(), "no recipes");
        for (boolean create : new boolean[]{false, true}) {
            Map<String, List<String>> byOutput = new TreeMap<>();
            for (var e : recipes.entrySet()) {
                JsonObject r = e.getValue();
                if (!loads(r, create)) continue;
                JsonObject result = r.getAsJsonObject("result");
                byOutput.computeIfAbsent(result.get("id").getAsString(), k -> new ArrayList<>()).add(e.getKey());
                for (var k : r.getAsJsonObject("key").entrySet()) {
                    JsonObject in = k.getValue().getAsJsonObject();
                    String where = e.getKey() + " " + k.getKey() + (create ? " (with Create)" : " (without Create)");
                    if (in.has("tag")) {
                        String tag = in.get("tag").getAsString();
                        assertTrue(TAGS.containsKey(tag), where + ": tag #" + tag + " is not one of the agreed common tags");
                        Set<String> items = resolve(tag, new TreeSet<>());
                        assertFalse(items.isEmpty(), where + ": #" + tag + " resolves to nothing");
                        for (String i : items) assertTrue(exists(i), where + ": #" + tag + " names missing " + i);
                    } else {
                        String item = in.get("item").getAsString();
                        // Create's items are checked by the GameTest server, which has Create
                        if (item.startsWith("create:")) {
                            assertTrue(create, where + ": a recipe loaded without Create names " + item);
                            continue;
                        }
                        assertTrue(exists(item), where + ": missing item " + item);
                        assertFalse(TAGS.values().stream().anyMatch(s -> s.contains(item)), where + ": " + item + " should be its common tag");
                    }
                }
            }
            // each item has exactly one recipe in each setup, and all of them are craftable
            assertEquals(COUNTS.keySet(), byOutput.keySet(), "craftable items " + (create ? "with" : "without") + " Create");
            for (var e : byOutput.entrySet()) assertEquals(1, e.getValue().size(), e.getKey() + ": recipes " + e.getValue());
        }
    }

    @Test
    void countsAndPatternsStay() throws Exception {
        Map<String, JsonObject> recipes = recipes();
        for (var e : recipes.entrySet()) {
            JsonObject result = e.getValue().getAsJsonObject("result");
            Integer count = COUNTS.get(result.get("id").getAsString());
            assertNotNull(count, e.getKey() + ": unknown output");
            assertEquals(count.intValue(), result.get("count").getAsInt(), e.getKey() + ": count");
            if (!e.getKey().endsWith("_vanilla")) continue;
            // a vanilla fallback is its Create twin with the Create items replaced: same pattern, same tag ingredients
            JsonObject twin = recipes.get(e.getKey().substring(0, e.getKey().length() - "_vanilla".length()));
            assertNotNull(twin, e.getKey() + ": no Create twin");
            assertEquals(twin.get("pattern"), e.getValue().get("pattern"), e.getKey() + ": pattern differs from its twin");
            for (var k : twin.getAsJsonObject("key").entrySet()) {
                JsonObject a = k.getValue().getAsJsonObject(), b = e.getValue().getAsJsonObject("key").getAsJsonObject(k.getKey());
                assertNotNull(b, e.getKey() + ": key " + k.getKey() + " missing");
                if (a.has("tag") || !a.get("item").getAsString().startsWith("create:")) assertEquals(a, b, e.getKey() + " " + k.getKey());
            }
        }
    }

    /** Whether the recipe's {@code neoforge:conditions} hold with or without Create (the only condition used). */
    private static boolean loads(JsonObject recipe, boolean create) {
        if (!recipe.has("neoforge:conditions")) return true;
        boolean all = true;
        for (JsonElement c : recipe.getAsJsonArray("neoforge:conditions")) all &= holds(c.getAsJsonObject(), create);
        return all;
    }

    private static boolean holds(JsonObject c, boolean create) {
        return switch (c.get("type").getAsString()) {
            case "neoforge:not" -> !holds(c.getAsJsonObject("value"), create);
            case "neoforge:mod_loaded" -> {
                assertEquals("create", c.get("modid").getAsString(), "only Create conditions");
                yield create;
            }
            default -> throw new AssertionError("unexpected condition " + c);
        };
    }

    /** An item id exists: vanilla by the registry, ours by its name in the English lang file. */
    private static boolean exists(String id) throws Exception {
        ResourceLocation rl = ResourceLocation.parse(id);
        if (rl.getNamespace().equals(Incoming.MOD_ID)) return lang().containsKey("item." + Incoming.MOD_ID + "." + rl.getPath());
        return rl.getNamespace().equals("minecraft") && BuiltInRegistries.ITEM.containsKey(rl);
    }

    private static Map<String, JsonElement> lang;

    private static Map<String, JsonElement> lang() throws Exception {
        if (lang == null) {
            try (var in = RecipeTagsTest.class.getResourceAsStream("/assets/incoming/lang/en_us.json")) {
                assertNotNull(in, "en_us.json");
                lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().asMap();
            }
        }
        return lang;
    }

    /**
     * Items of an item tag as the default datapack defines it: every {@code data/<ns>/tags/item/<path>.json} on the
     * classpath (NeoForge, Minecraft, this mod), merged, {@code #tag} entries followed, optional entries skipped when
     * absent; {@code replace} is not used by any of these files.
     */
    private static Set<String> resolve(String tag, Set<String> seen) throws Exception {
        assertTrue(seen.add(tag), "tag cycle at #" + tag);
        ResourceLocation rl = ResourceLocation.parse(tag);
        List<URL> files = Collections.list(RecipeTagsTest.class.getClassLoader().getResources("data/" + rl.getNamespace() + "/tags/item/" + rl.getPath() + ".json"));
        Set<String> out = new TreeSet<>();
        for (URL f : files) {
            JsonObject json;
            try (var in = f.openStream()) {
                json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            for (JsonElement v : json.getAsJsonArray("values")) {
                boolean required = !v.isJsonObject() || !v.getAsJsonObject().has("required") || v.getAsJsonObject().get("required").getAsBoolean();
                String id = v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString();
                if (id.startsWith("#")) {
                    Set<String> sub = resolve(id.substring(1), new TreeSet<>(seen));
                    if (sub.isEmpty() && required) fail("#" + tag + ": required #" + id + " is empty");
                    out.addAll(sub);
                } else if (BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(id))) {
                    out.add(id);
                } else if (required) {
                    fail("#" + tag + ": required " + id + " is no item");
                }
            }
        }
        return out;
    }

    private static Map<String, JsonObject> recipes() throws Exception {
        URL dir = RecipeTagsTest.class.getResource("/data/incoming/recipe");
        assertNotNull(dir, "recipes");
        Map<String, JsonObject> out = new HashMap<>();
        try (Stream<Path> files = Files.list(Path.of(dir.toURI()))) {
            for (Path f : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                String name = f.getFileName().toString();
                out.put(name.substring(0, name.length() - ".json".length()), JsonParser.parseString(Files.readString(f)).getAsJsonObject());
            }
        }
        return out;
    }
}
