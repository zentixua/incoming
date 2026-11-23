package ua.zentix.incoming.progress;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Incoming's statistics (vanilla custom stats: the statistics screen's General tab, the player's stats file, scoreboard
 * criteria {@code minecraft.custom:incoming.<path>}). Counted by {@link Progress}.
 */
public final class ModStats {
    public static final DeferredRegister<ResourceLocation> REGISTER = DeferredRegister.create(Registries.CUSTOM_STAT, Incoming.MOD_ID);
    private static final List<ResourceLocation> ALL = new ArrayList<>();

    /** Rounds of every weapon that left (one per {@code StrikeRoundEvent.Released}). */
    public static final ResourceLocation ROUNDS_LAUNCHED = stat("rounds_launched");
    /** The same per weapon: {@code rounds_launched.<weapon id>}. */
    private static final Map<WeaponType, ResourceLocation> LAUNCHED_BY_WEAPON = byWeapon();
    /** Hostile rounds the player shot down: by their SAM or their own weapon. */
    public static final ResourceLocation INTERCEPTIONS = stat("interceptions");
    /** Nuclear detonations the player was within reach of ({@link Progress#witnessed}). */
    public static final ResourceLocation NUCLEAR_DETONATIONS_WITNESSED = stat("nuclear_detonations_witnessed");

    private ModStats() {}

    private static ResourceLocation stat(String path) {
        ResourceLocation id = Incoming.id(path);
        REGISTER.register(path, () -> id);
        ALL.add(id);
        return id;
    }

    private static Map<WeaponType, ResourceLocation> byWeapon() {
        Map<WeaponType, ResourceLocation> m = new EnumMap<>(WeaponType.class);
        for (WeaponType w : WeaponType.values()) m.put(w, stat("rounds_launched." + w.getSerializedName()));
        return Collections.unmodifiableMap(m);
    }

    public static ResourceLocation launched(WeaponType weapon) {
        return LAUNCHED_BY_WEAPON.get(weapon);
    }

    /** Every Incoming statistic, in registration order. */
    public static List<ResourceLocation> all() {
        return Collections.unmodifiableList(ALL);
    }

    /**
     * Create the stat objects (common setup, both sides), as vanilla's own {@code Stats.makeCustomStat} does: the statistics
     * screen lists only created stats (a scoreboard criterion creates its stat on first use).
     */
    public static void create() {
        for (ResourceLocation id : ALL) Stats.CUSTOM.get(id, StatFormatter.DEFAULT);
    }
}
