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
import ua.zentix.incoming.registry.ModDamageTypes;

import java.util.Set;

/**
 * Ожоги светом шара ({@link FlashBurns}): эффект длится, пока ожоги заживают, и всё это время здоровье не поднимается
 * выше того, что ожоги оставили. Уровень — десятые доли здоровья, которые ожоги отняли ({@link #lost}); уровень
 * {@link #SHOCK} — ожоговый шок: здоровья 5 %, в конце срока обожжённый умирает (урон {@code burn_shock}: ни броня,
 * ни огнестойкость, ни сопротивление, ни чары его не убавляют). Ни молоко, ни другие средства ожоги не лечат; тотем
 * бессмертия спасает, как от любой смерти.
 */
public class BurnsEffect extends MobEffect {
    /** Уровень эффекта у обожжённого, которого ждёт шок. */
    public static final int SHOCK = 10;
    /** Сколько здоровья оставляет шок. */
    public static final double SHOCK_LEFT = 0.05;
    /** Модификатор предела здоровья. */
    static final ResourceLocation ID = Incoming.id("burns");

    public BurnsEffect() {
        super(MobEffectCategory.HARMFUL, 0xB5461E);
        addAttributeModifier(Attributes.MAX_HEALTH, ID, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, BurnsEffect::lost);
    }

    /** Множитель здоровья ожогов уровня {@code amplifier} — доля, которую они отняли, со знаком минус. */
    static double lost(int amplifier) {
        return amplifier >= SHOCK ? SHOCK_LEFT - 1 : -Math.max(0, amplifier) / 10.0;
    }

    /**
     * Уровень эффекта для ожогов, которые отняли бы долю {@code share} здоровья (от 1 — шок): десятые доли с округлением
     * вверх, от 0,9 до шока — 0,9. Рана — ровно до предела уровня ({@link FlashBurns}): те же ожоги второй раз ничего не
     * отнимают.
     */
    public static int level(double share) {
        return share >= 1 ? SHOCK : (int) Math.min(SHOCK - 1, Math.max(0, Math.ceil(share * 10 - 1e-9)));
    }

    /**
     * Рана новых ожогов уровня {@code amplifier} поверх прежних уровня {@code before} (−1 — их нет): доля здоровья без
     * ожогов, которую отнимает прибавка предела; новые не тяжелее прежних — ноль.
     */
    static double wound(int before, int amplifier) {
        return amplifier <= before ? 0 : lost(before) - lost(amplifier);
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
