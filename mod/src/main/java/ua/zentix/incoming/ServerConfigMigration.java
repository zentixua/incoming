package ua.zentix.incoming;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableCommentedConfig;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;

/** NeoForge's spec correction hook runs before load events and before unknown keys are removed. */
public record ServerConfigMigration(ModConfigSpec delegate) implements IConfigSpec {
    private static final String OLD = "world.siren", CURRENT = "world.early_warning";
    /**
     * Substation repair time was real minutes; it is game hours now (on the world clock): 15 minutes were 18 000 ticks,
     * 18 hours under a vanilla day, so the value keeps its meaning there.
     */
    private static final String OLD_RESTORE = "grid.restore_minutes", RESTORE = "grid.restore_hours";
    /**
     * The GAME wave default rose from 0.25 to 0.35 (Artem, 19.11). Only a file whose preset is not CUSTOM moves: there the
     * value is inert, so 0.25 is the old default left in the file, not a choice in effect; under CUSTOM 0.25 is the wave
     * the world runs and stays. And only once: the key's comment must come from an older jar; the spec's correction then
     * rewrites it, so a 0.25 a player sets afterwards stays too.
     */
    private static final String WAVE = "nuclear.wave_speed", PRESET = "scale.scale_preset";

    @Override
    public boolean isEmpty() { return delegate.isEmpty(); }

    @Override
    public void validateSpec(ModConfig config) { delegate.validateSpec(config); }

    @Override
    public boolean isCorrect(UnmodifiableCommentedConfig config) {
        return !config.contains(OLD) && !config.contains(OLD_RESTORE) && !oldWaveDefault(config) && delegate.isCorrect(config);
    }

    private boolean oldWaveDefault(UnmodifiableCommentedConfig config) {
        if (!(config.get(WAVE) instanceof Number n) || n.doubleValue() != IncomingConfig.OLD_GAME_WAVE_SPEED) return false;
        Object preset = config.get(PRESET);
        if (preset != null && IncomingConfig.ScalePreset.CUSTOM.name().equals(preset.toString())) return false;
        String written = config.getComment(WAVE), current = delegate.getSpec().<ModConfigSpec.ValueSpec>get(WAVE).getComment();
        // line ends normalized as the spec's own comment check does
        return written == null || !written.replace("\r\n", "\n").equals(current.replace("\r\n", "\n"));
    }

    @Override
    public void correct(CommentedConfig config) {
        Object old = config.get(OLD);
        if (!config.contains(CURRENT) && old instanceof Boolean) config.set(CURRENT, old);
        if (!config.contains(RESTORE) && config.get(OLD_RESTORE) instanceof Number minutes) {
            config.set(RESTORE, minutes.doubleValue() * 1200 / 1000);
        }
        if (oldWaveDefault(config)) config.set(WAVE, IncomingConfig.GAME_WAVE_SPEED);
        delegate.correct(config);
    }

    @Override
    public void acceptConfig(@Nullable ILoadedConfig config) {
        if (config != null && !isCorrect(config.config())) {
            correct(config.config());
            config.save();
        }
        delegate.acceptConfig(config);
    }
}
