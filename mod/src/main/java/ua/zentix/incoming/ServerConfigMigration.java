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

    @Override
    public boolean isEmpty() { return delegate.isEmpty(); }

    @Override
    public void validateSpec(ModConfig config) { delegate.validateSpec(config); }

    @Override
    public boolean isCorrect(UnmodifiableCommentedConfig config) {
        return !config.contains(OLD) && delegate.isCorrect(config);
    }

    @Override
    public void correct(CommentedConfig config) {
        Object old = config.get(OLD);
        if (!config.contains(CURRENT) && old instanceof Boolean) config.set(CURRENT, old);
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
