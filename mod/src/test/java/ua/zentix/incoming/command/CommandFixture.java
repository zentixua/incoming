package ua.zentix.incoming.command;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import ua.zentix.incoming.IncomingConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;

/** The registered {@code /incoming} tree over an in-memory server config, without a server. */
final class CommandFixture {
    private CommandFixture() {}

    /** Once per test class: vanilla registries. */
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    /**
     * The default server config, attached in memory. ILoadedConfig is sealed and its only implementation is
     * package-private: attach an instance without starting the loader, writing a config file or changing any production
     * permission predicate. Undo with {@link #unloadConfig}.
     */
    static void loadConfig() throws ReflectiveOperationException {
        CommentedConfig config = CommentedConfig.inMemory();
        IncomingConfig.SERVER_SPEC.correct(config);
        var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig")
                .getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
        constructor.setAccessible(true);
        IConfigSpec.ILoadedConfig loaded = (IConfigSpec.ILoadedConfig) constructor.newInstance(config, null, null);
        IncomingConfig.SERVER_SPEC.acceptConfig(loaded);
    }

    static void unloadConfig() {
        IncomingConfig.SERVER_SPEC.acceptConfig(null);
    }

    /** A dispatcher holding only {@link IncomingCommand}'s tree. */
    static CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> d = new CommandDispatcher<>();
        IncomingCommand.register(new RegisterCommandsEvent(d, Commands.CommandSelection.ALL, mock(CommandBuildContext.class)));
        return d;
    }
}
