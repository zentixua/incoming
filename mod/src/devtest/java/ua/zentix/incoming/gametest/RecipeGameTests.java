package ua.zentix.incoming.gametest;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.GameType;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Recipes with common tags (roadmap C2) and the Geiger counter recipe (C3) on the GameTest server, which has Create:
 * every loaded recipe of the mod resolves each ingredient to items and crafts its output with the agreed count
 * ({@link RecipeContract}); the common tags hold what the contract says; a foreign item that a datapack tags
 * {@code c:ingots/iron} crafts and unlocks a recipe; the Geiger counter is crafted from its ingredients and its recipe
 * unlocks for a survival player who picks one up. The path without Create: {@code RecipeTagsTest}.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RecipeGameTests {
    private RecipeGameTests() {}

    /** Every recipe loads, resolves and crafts; the tags hold the contract's items; the Geiger counter unlocks. */
    @GameTest(template = "range", batch = "recipe_tags")
    public static void recipesResolveAndCraft(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean create = ModList.get().isLoaded("create");
        for (var e : RecipeContract.TAGS.entrySet()) {
            Set<String> items = tagItems(e.getKey());
            Incoming.LOG.info("RECIPETAGS #{} with Create {}: {}", e.getKey(), create, items);
            h.assertTrue(items.equals(e.getValue()), "#" + e.getKey() + " holds " + items + ", the contract says " + e.getValue());
        }
        Map<String, List<ResourceLocation>> byOutput = new TreeMap<>();
        for (RecipeHolder<CraftingRecipe> r : level.getServer().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if (!r.id().getNamespace().equals(Incoming.MOD_ID)) continue;
            h.assertTrue(r.id().getPath().endsWith("_vanilla") != create, r.id() + " loaded with Create " + create);
            ItemStack out = craft(h, r, i -> i.getItems()[0]);
            byOutput.computeIfAbsent(BuiltInRegistries.ITEM.getKey(out.getItem()).toString(), k -> new ArrayList<>()).add(r.id());
            Integer count = RecipeContract.COUNTS.get(BuiltInRegistries.ITEM.getKey(out.getItem()).toString());
            h.assertTrue(count != null && count == out.getCount(), r.id() + " makes " + out + ", the contract says " + count);
        }
        h.assertTrue(byOutput.keySet().equals(RecipeContract.COUNTS.keySet()), "craftable " + byOutput.keySet());
        for (var e : byOutput.entrySet()) h.assertTrue(e.getValue().size() == 1, e.getKey() + ": recipes " + e.getValue());

        // the Geiger counter in survival: picking up one ingredient shows the recipe, and the ingredients make one counter
        ResourceLocation geiger = Incoming.id(create ? "geiger_counter" : "geiger_counter_vanilla");
        ServerPlayer p = ProgressGameTests.Credited.online(h, "recipe_geiger", 0, new BlockPos(32, 2, 32));
        p.setGameMode(GameType.SURVIVAL);
        h.assertFalse(p.getRecipeBook().contains(geiger), "Geiger counter recipe known before any ingredient");
        pickUp(p, new ItemStack(Items.COPPER_INGOT));
        h.assertTrue(p.getRecipeBook().contains(geiger), "Geiger counter recipe not unlocked by a copper ingot");
        h.succeed();
    }

    /**
     * A datapack tags a foreign item (a brick) {@code c:ingots/iron}: the designator crafts with bricks for its iron, and
     * holding a brick unlocks its recipe. Without the pack the same grid crafts nothing.
     */
    @GameTest(template = "range", batch = "recipe_tags_pack", timeoutTicks = 2400)
    public static void foreignIronCrafts(GameTestHelper h) {
        ResourceLocation designator = Incoming.id("strike_designator");
        Function<Ingredient, ItemStack> bricks = i -> i.test(new ItemStack(Items.IRON_INGOT)) ? new ItemStack(Items.BRICK) : i.getItems()[0];
        DataPackFixture.run(h, "incoming_test_foreign_iron",
                Map.of("data/c/tags/item/ingots/iron.json", "{\"values\": [\"minecraft:brick\"]}"),
                () -> h.assertTrue(match(h, designator, bricks).isEmpty(), "bricks craft the designator without the tag"),
                () -> {
                    h.assertTrue(tagItems("c:ingots/iron").equals(Set.of("minecraft:iron_ingot", "minecraft:brick")),
                            "#c:ingots/iron with the pack: " + tagItems("c:ingots/iron"));
                    Optional<RecipeHolder<CraftingRecipe>> got = match(h, designator, bricks);
                    h.assertTrue(got.isPresent() && got.get().id().equals(designator), "bricks tagged as iron do not craft the designator: " + got.map(RecipeHolder::id));
                    ItemStack out = got.get().value().assemble(input(recipe(h, designator), bricks), h.getLevel().registryAccess());
                    h.assertTrue(out.is(BuiltInRegistries.ITEM.get(designator)) && out.getCount() == 1, "designator from bricks: " + out);
                    ServerPlayer p = ProgressGameTests.Credited.online(h, "recipe_brick", 0, new BlockPos(32, 2, 32));
                    p.setGameMode(GameType.SURVIVAL);
                    pickUp(p, new ItemStack(Items.BRICK));
                    h.assertTrue(p.getRecipeBook().contains(designator), "a brick tagged as iron does not unlock the designator recipe");
                },
                () -> h.assertTrue(match(h, designator, bricks).isEmpty(), "bricks still craft the designator after the pack is gone"));
    }

    // ---------------------------------------------------------------- helpers

    /** Items of item tag {@code id} as the server has bound it. */
    static Set<String> tagItems(String id) {
        Set<String> out = new TreeSet<>();
        BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, ResourceLocation.parse(id)))
                .forEach(i -> out.add(BuiltInRegistries.ITEM.getKey(i.value()).toString()));
        return out;
    }

    /** The player picks {@code stack} up: into the inventory, with the trigger the inventory menu fires on change. */
    static void pickUp(ServerPlayer p, ItemStack stack) {
        ItemStack copy = stack.copy();
        p.getInventory().add(stack);
        CriteriaTriggers.INVENTORY_CHANGED.trigger(p, p.getInventory(), copy);
    }

    private static ShapedRecipe recipe(GameTestHelper h, ResourceLocation id) {
        var r = h.getLevel().getServer().getRecipeManager().byKey(id);
        if (r.isEmpty() || !(r.get().value() instanceof ShapedRecipe shaped)) throw new GameTestAssertException(id + ": no shaped recipe");
        return shaped;
    }

    /** The recipe's grid filled by {@code pick} for each ingredient. */
    private static CraftingInput input(ShapedRecipe shaped, Function<Ingredient, ItemStack> pick) {
        List<ItemStack> grid = new ArrayList<>();
        for (Ingredient i : shaped.getIngredients()) grid.add(i.isEmpty() ? ItemStack.EMPTY : pick.apply(i).copy());
        return CraftingInput.of(shaped.getWidth(), shaped.getHeight(), grid);
    }

    private static Optional<RecipeHolder<CraftingRecipe>> match(GameTestHelper h, ResourceLocation id, Function<Ingredient, ItemStack> pick) {
        return h.getLevel().getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input(recipe(h, id), pick), h.getLevel());
    }

    /** Crafts {@code r} from {@code pick}'s items on a crafting table: the table finds this recipe and makes its output. */
    private static ItemStack craft(GameTestHelper h, RecipeHolder<CraftingRecipe> r, Function<Ingredient, ItemStack> pick) {
        if (!(r.value() instanceof ShapedRecipe shaped)) throw new GameTestAssertException(r.id() + ": not shaped");
        for (Ingredient i : shaped.getIngredients()) {
            if (!i.isEmpty() && i.hasNoItems()) throw new GameTestAssertException(r.id() + ": an ingredient resolves to no item");
        }
        CraftingInput in = input(shaped, pick);
        var found = h.getLevel().getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, in, h.getLevel());
        h.assertTrue(found.isPresent() && found.get().id().equals(r.id()), r.id() + ": the crafting table finds " + found.map(RecipeHolder::id));
        return found.get().value().assemble(in, h.getLevel().registryAccess());
    }
}
