package ua.zentix.incoming;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every recipe of the mod appears in the vanilla recipe book: the book shows only unlocked recipes, and vanilla unlocks
 * them by a hidden advancement under {@code recipes/}. Each advancement
 * rewards its recipe, unlocks when the player holds any of its item ingredients (or the designator, for everything it
 * launches or links), and carries the recipe's own load conditions (Create or vanilla variant), so it never names a
 * recipe that is not loaded.
 */
class RecipeUnlockTest {
    @Test
    void everyRecipeHasAnUnlockAdvancement() throws Exception {
        Map<String, JsonObject> recipes = jsons("/data/incoming/recipe");
        Map<String, JsonObject> unlocks = jsons("/data/incoming/advancement/recipes");
        assertFalse(recipes.isEmpty(), "no recipes");
        Map<String, JsonObject> byReward = new HashMap<>();
        for (JsonObject a : unlocks.values()) {
            for (JsonElement r : a.getAsJsonObject("rewards").getAsJsonArray("recipes")) {
                // every reward names a recipe that exists
                assertTrue(recipes.containsKey(r.getAsString().substring("incoming:".length())), "reward " + r + " names no recipe");
                byReward.put(r.getAsString(), a);
            }
        }
        for (var e : recipes.entrySet()) {
            String id = "incoming:" + e.getKey();
            JsonObject recipe = e.getValue(), unlock = byReward.get(id);
            assertNotNull(unlock, id + ": no advancement under recipes/ unlocks it");
            assertEquals("minecraft:recipes/root", unlock.get("parent").getAsString(), id);
            assertEquals(recipe.get("neoforge:conditions"), unlock.get("neoforge:conditions"), id + ": load conditions differ from the recipe's");
            Set<String> triggers = new HashSet<>();
            for (var c : unlock.getAsJsonObject("criteria").entrySet()) {
                JsonObject cond = c.getValue().getAsJsonObject();
                if (cond.get("trigger").getAsString().equals("minecraft:inventory_changed")) {
                    assertEquals(1, cond.getAsJsonObject("conditions").getAsJsonArray("items").size(), id + " " + c.getKey() + ": one item predicate");
                    triggers.add(cond.getAsJsonObject("conditions").getAsJsonArray("items").get(0).getAsJsonObject().get("items").getAsString());
                }
            }
            for (JsonElement in : recipe.getAsJsonObject("key").asMap().values()) {
                JsonObject i = in.getAsJsonObject();
                if (i.has("item")) assertTrue(triggers.contains(i.get("item").getAsString()), id + ": not unlocked by " + i.get("item"));
            }
            if (!e.getKey().equals("strike_designator")) assertTrue(triggers.contains("incoming:strike_designator"), id + ": not unlocked by the designator");
            // one requirement group: any criterion unlocks
            assertEquals(1, unlock.getAsJsonArray("requirements").size(), id);
            Set<String> group = new HashSet<>();
            for (JsonElement r : unlock.getAsJsonArray("requirements").get(0).getAsJsonArray()) group.add(r.getAsString());
            assertEquals(unlock.getAsJsonObject("criteria").keySet(), group, id + ": the requirement group names every criterion");
        }
    }

    private static Map<String, JsonObject> jsons(String resource) throws Exception {
        URL dir = RecipeUnlockTest.class.getResource(resource);
        assertNotNull(dir, resource);
        Map<String, JsonObject> out = new HashMap<>();
        Path root = Path.of(dir.toURI());
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                // the resource id: path under the root, '/'-separated, without ".json"
                String name = root.relativize(f).toString().replace('\\', '/');
                out.put(name.substring(0, name.length() - ".json".length()), JsonParser.parseString(Files.readString(f)).getAsJsonObject());
            }
        }
        return out;
    }
}
