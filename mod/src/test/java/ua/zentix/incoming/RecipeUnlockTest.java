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
            for (JsonElement r : a.getAsJsonObject("rewards").getAsJsonArray("recipes")) byReward.put(r.getAsString(), a);
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
            assertEquals(unlock.getAsJsonObject("criteria").size(), unlock.getAsJsonArray("requirements").get(0).getAsJsonArray().size(), id);
        }
    }

    private static Map<String, JsonObject> jsons(String resource) throws Exception {
        URL dir = RecipeUnlockTest.class.getResource(resource);
        assertNotNull(dir, resource);
        Map<String, JsonObject> out = new HashMap<>();
        try (Stream<Path> files = Files.walk(Path.of(dir.toURI()))) {
            for (Path f : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                String name = f.getFileName().toString();
                out.put(name.substring(0, name.length() - ".json".length()), JsonParser.parseString(Files.readString(f)).getAsJsonObject());
            }
        }
        return out;
    }
}
