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

    @Override
    public boolean isEmpty() { return delegate.isEmpty(); }

    @Override
    public void validateSpec(ModConfig config) { delegate.validateSpec(config); }

    @Override
    public boolean isCorrect(UnmodifiableCommentedConfig config) {
        return !config.contains(OLD) && !config.contains(OLD_RESTORE) && delegate.isCorrect(config);
    }

    @Override
    public void correct(CommentedConfig config) {
        Object old = config.get(OLD);
        if (!config.contains(CURRENT) && old instanceof Boolean) config.set(CURRENT, old);
        if (!config.contains(RESTORE) && config.get(OLD_RESTORE) instanceof Number minutes) {
            config.set(RESTORE, minutes.doubleValue() * 1200 / 1000);
        }
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
