package ua.zentix.incoming.progress;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.MinMaxBounds;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.strike.WeaponType;

import java.util.Optional;

/**
 * Advancement criterion triggers ({@code incoming:<name>} in an advancement's {@code criteria}), fired by {@link Progress}
 * for the credited player. Every condition besides {@code player} is optional: absent matches all.
 */
public final class ModTriggers {
    public static final DeferredRegister<CriterionTrigger<?>> REGISTER = DeferredRegister.create(Registries.TRIGGER_TYPE, Incoming.MOD_ID);

    /** One of the player's rounds left; {@code weapon}. */
    public static final DeferredHolder<CriterionTrigger<?>, Weapon> ROUND_RELEASED = REGISTER.register("round_released", Weapon::new);
    /** One of the player's rounds they steered through its projectile camera went off; {@code weapon}. */
    public static final DeferredHolder<CriterionTrigger<?>, Weapon> CAMERA_GUIDED_HIT = REGISTER.register("camera_guided_hit", Weapon::new);
    /** The player shot down a hostile round; {@code weapon} (the round's), {@code by_sam}. */
    public static final DeferredHolder<CriterionTrigger<?>, Intercepted> ROUND_INTERCEPTED = REGISTER.register("round_intercepted", Intercepted::new);
    /** The player lived through a nuclear blast wave; {@code overpressure_psi} at their position, {@code yield_kt}. */
    public static final DeferredHolder<CriterionTrigger<?>, Survived> NUCLEAR_SURVIVED = REGISTER.register("nuclear_survived", Survived::new);
    /** Power came back to the whole outage the player stands in; {@code nuclear}. */
    public static final DeferredHolder<CriterionTrigger<?>, Restored> BLACKOUT_RESTORED = REGISTER.register("blackout_restored", Restored::new);

    private ModTriggers() {}

    public static final class Weapon extends SimpleCriterionTrigger<Weapon.Instance> {
        @Override
        public Codec<Instance> codec() {
            return Instance.CODEC;
        }

        public void trigger(ServerPlayer player, WeaponType weapon) {
            trigger(player, i -> i.weapon.isEmpty() || i.weapon.get() == weapon);
        }

        public record Instance(Optional<ContextAwarePredicate> player, Optional<WeaponType> weapon) implements SimpleCriterionTrigger.SimpleInstance {
            static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                    EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                    WeaponType.CODEC.optionalFieldOf("weapon").forGetter(Instance::weapon)
            ).apply(i, Instance::new));
        }
    }

    public static final class Intercepted extends SimpleCriterionTrigger<Intercepted.Instance> {
        @Override
        public Codec<Instance> codec() {
            return Instance.CODEC;
        }

        public void trigger(ServerPlayer player, WeaponType weapon, boolean bySam) {
            trigger(player, i -> (i.weapon.isEmpty() || i.weapon.get() == weapon) && (i.bySam.isEmpty() || i.bySam.get() == bySam));
        }

        public record Instance(Optional<ContextAwarePredicate> player, Optional<WeaponType> weapon, Optional<Boolean> bySam)
                implements SimpleCriterionTrigger.SimpleInstance {
            static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                    EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                    WeaponType.CODEC.optionalFieldOf("weapon").forGetter(Instance::weapon),
                    Codec.BOOL.optionalFieldOf("by_sam").forGetter(Instance::bySam)
            ).apply(i, Instance::new));
        }
    }

    public static final class Survived extends SimpleCriterionTrigger<Survived.Instance> {
        @Override
        public Codec<Instance> codec() {
            return Instance.CODEC;
        }

        public void trigger(ServerPlayer player, double psi, double yieldKt) {
            trigger(player, i -> i.overpressurePsi.matches(psi) && i.yieldKt.matches(yieldKt));
        }

        public record Instance(Optional<ContextAwarePredicate> player, MinMaxBounds.Doubles overpressurePsi, MinMaxBounds.Doubles yieldKt)
                implements SimpleCriterionTrigger.SimpleInstance {
            static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                    EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                    MinMaxBounds.Doubles.CODEC.optionalFieldOf("overpressure_psi", MinMaxBounds.Doubles.ANY).forGetter(Instance::overpressurePsi),
                    MinMaxBounds.Doubles.CODEC.optionalFieldOf("yield_kt", MinMaxBounds.Doubles.ANY).forGetter(Instance::yieldKt)
            ).apply(i, Instance::new));
        }
    }

    public static final class Restored extends SimpleCriterionTrigger<Restored.Instance> {
        @Override
        public Codec<Instance> codec() {
            return Instance.CODEC;
        }

        public void trigger(ServerPlayer player, boolean nuclear) {
            trigger(player, i -> i.nuclear.isEmpty() || i.nuclear.get() == nuclear);
        }

        public record Instance(Optional<ContextAwarePredicate> player, Optional<Boolean> nuclear) implements SimpleCriterionTrigger.SimpleInstance {
            static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                    EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                    Codec.BOOL.optionalFieldOf("nuclear").forGetter(Instance::nuclear)
            ).apply(i, Instance::new));
        }
    }
}
