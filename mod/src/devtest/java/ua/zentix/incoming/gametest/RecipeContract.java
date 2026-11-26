package ua.zentix.incoming.gametest;

import java.util.Map;
import java.util.Set;

/**
 * What the recipes promise pack makers (roadmap C2, C3), shared by the GameTest ({@link RecipeGameTests}) and the
 * vanilla-only unit test ({@code RecipeTagsTest}): the common tags recipes may use and what each resolves to in the
 * default datapack, with and without Create, and the output count of every craftable item.
 */
public final class RecipeContract {
    /** Common tag → the items it holds in the default datapack (NeoForge 21.1; Create 6.0.10 adds none of them). */
    public static final Map<String, Set<String>> TAGS = Map.of(
            "c:ingots/iron", Set.of("minecraft:iron_ingot"),
            "c:ingots/copper", Set.of("minecraft:copper_ingot"),
            "c:gunpowders", Set.of("minecraft:gunpowder"),
            "c:storage_blocks/iron", Set.of("minecraft:iron_block"));

    /** Output count of every craftable item; tags change no count. */
    public static final Map<String, Integer> COUNTS = Map.ofEntries(
            Map.entry("incoming:shahed", 1), Map.entry("incoming:lancet", 1), Map.entry("incoming:cruise_missile", 1),
            Map.entry("incoming:grad_rockets", 1), Map.entry("incoming:bunker_buster", 1), Map.entry("incoming:nuclear_warhead", 1),
            Map.entry("incoming:icbm", 1), Map.entry("incoming:interceptor", 2), Map.entry("incoming:sam", 1),
            Map.entry("incoming:fixed_launcher", 1), Map.entry("incoming:strike_designator", 1), Map.entry("incoming:substation", 1),
            Map.entry("incoming:geiger_counter", 1));

    private RecipeContract() {}
}
