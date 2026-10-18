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
 * Ожоги светом шара ({@link FlashBurns}): эффект длится, пока ожоги заживают, и всё это время здоровье не поднимается
 * выше того, что ожоги оставили. Уровень — ступень потери здоровья ({@link BurnInjury#level}, десятые доли);
 * уровень {@link #SHOCK} — ожоговый шок: здоровья {@link BurnInjury#SHOCK_LEFT}, в конце срока обожжённый умирает
 * (урон {@code burn_shock}: ни броня, ни огнестойкость, ни сопротивление, ни чары его не убавляют). Ни молоко, ни
 * другие средства ожоги не лечат; тотем бессмертия спасает, как от любой смерти.
 */
public class BurnsEffect extends MobEffect {
    /** Уровень эффекта у обожжённого, которого ждёт шок. */
    public static final int SHOCK = BurnInjury.SHOCK_LEVEL;
    /** Модификатор предела здоровья. */
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
        // ожоги заживают только со временем
    }
}
