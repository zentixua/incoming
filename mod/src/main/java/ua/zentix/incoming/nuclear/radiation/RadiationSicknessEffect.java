package ua.zentix.incoming.nuclear.radiation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.EffectCure;

import java.util.Set;

/**
 * Radiation sickness; {@link RadiationTicker} determines behavior from dose and time. For a player, the effect is a UI
 * stage marker (level = stage), updated by the player ticker. For a mob, the effect drives the course itself: once a
 * second on the mob tick ({@link RadiationTicker#tickMob}) — only exposed mobs' sickness ticks, without scanning all mobs.
 * Milk and other remedies do not cure it.
 */
public class RadiationSicknessEffect extends MobEffect {
    public RadiationSicknessEffect() {
        super(MobEffectCategory.HARMFUL, 0x6B8E23);
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        // once a second; an infinite effect (mob) supplies the mob's own tick counter here
        return duration % 20 == 0;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        if (entity instanceof Player || !(entity.level() instanceof ServerLevel level)) return true;
        return RadiationTicker.tickMob(level, entity);
    }

    @Override
    public void fillEffectCures(Set<EffectCure> cures, MobEffectInstance effectInstance) {
        // no cure
    }
}
