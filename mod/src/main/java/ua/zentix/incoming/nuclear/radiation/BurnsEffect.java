package ua.zentix.incoming.nuclear.radiation;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.common.EffectCure;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.registry.ModDamageTypes;

import java.util.Set;

/**
 * Ожоги светом шара ({@link FlashBurns}): эффект длится, пока ожоги заживают, и всё это время здоровье не поднимается
 * выше того, что ожоги оставили. Уровень — десятые доли здоровья, которые ожоги отняли ({@link #lost}); уровень
 * {@link #SHOCK} — ожоговый шок: здоровья 5 %, в конце срока обожжённый умирает. Ни молоко, ни другие средства ожоги не
 * лечат.
 */
public class BurnsEffect extends MobEffect {
    /** Уровень эффекта у обожжённого, которого ждёт шок. */
    public static final int SHOCK = 10;
    /** Сколько здоровья оставляет шок. */
    public static final double SHOCK_LEFT = 0.05;

    public BurnsEffect() {
        super(MobEffectCategory.HARMFUL, 0xB5461E);
        addAttributeModifier(Attributes.MAX_HEALTH, Incoming.id("burns"), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, BurnsEffect::lost);
    }

    /** Множитель здоровья ожогов уровня {@code amplifier} — доля, которую они отняли, со знаком минус. */
    static double lost(int amplifier) {
        return amplifier >= SHOCK ? SHOCK_LEFT - 1 : -Math.max(0, amplifier) / 10.0;
    }

    /** Уровень эффекта для ожогов, которые отняли долю {@code share} здоровья (от 1 — шок). */
    public static int level(double share) {
        return share >= 1 ? SHOCK : (int) Math.max(0, Math.floor(share * 10));
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return amplifier >= SHOCK && duration == 1;
    }

    @Override
    public boolean applyEffectTick(LivingEntity e, int amplifier) {
        e.hurt(ModDamageTypes.source(e.level(), ModDamageTypes.NUCLEAR_THERMAL, null, null), Float.MAX_VALUE);
        return true;
    }

    @Override
    public void fillEffectCures(Set<EffectCure> cures, MobEffectInstance effectInstance) {
        // ожоги заживают только со временем
    }
}
