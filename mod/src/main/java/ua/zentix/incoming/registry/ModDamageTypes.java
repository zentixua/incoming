package ua.zentix.incoming.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;

import java.util.Map;

/**
 * Damage types (data/incoming/damage_type): own death messages, included in vanilla tags (#minecraft:is_explosion …).
 * <p>
 * Vanilla builds death messages from source entities ({@code DamageSource.getLocalizedDeathMessage}): no entities —
 * base key without attacker, or “.player” naming whoever last hurt the victim (“… whilst fighting”); with an entity —
 * base key with attacker as second argument (or “.item” if holding a renamed item). Projectile hits always have an entity
 * (projectile, debris), named by the base key. Nuclear damage may have no entities (burns, radiation sickness, console
 * detonation) or a launching player — one base key cannot name both: like vanilla {@code explosion} and
 * {@code player_explosion}, each such type has an attacker-naming pair ({@code player_…}), selected by {@link #source}.
 */
public final class ModDamageTypes {
    public static final ResourceKey<DamageType> STRIKE = key("strike");
    public static final ResourceKey<DamageType> SHOCKWAVE = key("shockwave");
    public static final ResourceKey<DamageType> KINETIC = key("kinetic");
    public static final ResourceKey<DamageType> NUCLEAR_BLAST = key("nuclear_blast");
    public static final ResourceKey<DamageType> NUCLEAR_THERMAL = key("nuclear_thermal");
    public static final ResourceKey<DamageType> RADIATION = key("radiation");
    /** Death from burn shock at burn expiry ({@link ua.zentix.incoming.nuclear.radiation.BurnsEffect}). */
    public static final ResourceKey<DamageType> BURN_SHOCK = key("burn_shock");
    public static final ResourceKey<DamageType> DEBRIS = key("debris");
    /** Warhead casing fragments ({@code warhead.FragmentStrike}). */
    public static final ResourceKey<DamageType> FRAGMENT = key("fragment");
    public static final ResourceKey<DamageType> PLAYER_NUCLEAR_BLAST = key("player_nuclear_blast");
    public static final ResourceKey<DamageType> PLAYER_NUCLEAR_THERMAL = key("player_nuclear_thermal");
    public static final ResourceKey<DamageType> PLAYER_RADIATION = key("player_radiation");

    /** Type without attacker → attacker-naming pair (death message names them). */
    private static final Map<ResourceKey<DamageType>, ResourceKey<DamageType>> WITH_OWNER = Map.of(
            NUCLEAR_BLAST, PLAYER_NUCLEAR_BLAST,
            NUCLEAR_THERMAL, PLAYER_NUCLEAR_THERMAL,
            RADIATION, PLAYER_RADIATION);

    private ModDamageTypes() {}

    private static ResourceKey<DamageType> key(String name) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, Incoming.id(name));
    }

    /** @param type type without attacker; if {@code owner} exists and the type has an attacker-naming pair, use it */
    public static DamageSource source(Level level, ResourceKey<DamageType> type, @Nullable Entity direct, @Nullable Entity owner) {
        ResourceKey<DamageType> t = owner == null ? type : WITH_OWNER.getOrDefault(type, type);
        return new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(t), direct, owner);
    }
}
