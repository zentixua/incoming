package ua.zentix.incoming.nuclear.radiation;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.common.EffectCure;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.model.BurnInjury;
import ua.zentix.incoming.registry.ModDamageTypes;

import java.util.Set;

/**
 * Fireball light burns ({@link FlashBurns}): effect lasts while burns heal, capping health at what they left throughout.
 * Level is the health-loss step ({@link BurnInjury#level}, tenths); {@link #SHOCK} means burn shock: health
 * {@link BurnInjury#SHOCK_LEFT}, death at expiry ({@code burn_shock} damage: unaffected by armor, fire resistance,
 * resistance or enchantments). Milk and other remedies do not heal burns; a totem of undying saves as from any death.
 */
public class BurnsEffect extends MobEffect {
    /** Effect level for a burned subject facing shock. */
    public static final int SHOCK = BurnInjury.SHOCK_LEVEL;
    /** Health cap modifier. */
    static final ResourceLocation ID = Incoming.id("burns");

    public BurnsEffect() {
        super(MobEffectCategory.HARMFUL, 0xB5461E);
        addAttributeModifier(Attributes.MAX_HEALTH, ID, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, amplifier -> -BurnInjury.lost(amplifier));
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return amplifier >= SHOCK && duration == 1;
    }

    @Override
    public boolean applyEffectTick(LivingEntity e, int amplifier) {
        e.hurt(ModDamageTypes.source(e.level(), ModDamageTypes.BURN_SHOCK, null, null), Float.MAX_VALUE);
        return true;
    }

    @Override
    public void fillEffectCures(Set<EffectCure> cures, MobEffectInstance effectInstance) {
        // burns heal only with time
    }
}
