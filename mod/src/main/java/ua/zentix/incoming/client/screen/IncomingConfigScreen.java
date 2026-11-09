package ua.zentix.incoming.client.screen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.ConfigurationScreen.ConfigurationSectionScreen;
import net.neoforged.neoforge.common.ModConfigSpec;
import ua.zentix.incoming.ServerConfigMigration;

import java.util.List;

/** Standard NeoForge editor with the server's migration decorator unwrapped for value widgets. */
public final class IncomingConfigScreen extends OptionsSubScreen {
    private final ModContainer mod;

    public IncomingConfigScreen(ModContainer mod, Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.literal(mod.getModInfo().getDisplayName()));
        this.mod = mod;
    }

    @Override
    protected void addOptions() {
        for (ModConfig.Type type : ModConfig.Type.values()) {
            for (ModConfig config : ModConfigs.getConfigSet(type)) {
                if (!config.getModId().equals(mod.getModId())) continue;
                ModConfigSpec spec = config.getSpec() instanceof ServerConfigMigration migration
                        ? migration.delegate() : (ModConfigSpec) config.getSpec();
                Component title = Component.translatable("neoforge.configuration.uitext.type." + type.extension(), mod.getModInfo().getDisplayName());
                Button button = Button.builder(title, b -> minecraft.setScreen(new Section(this, config, spec, title)))
                        .width(Button.DEFAULT_WIDTH * 2 + 10).build();
                // Same edit restrictions as NeoForge's ConfigurationScreen, including a world opened to LAN.
                button.active = spec.isLoaded() && (type != ModConfig.Type.SERVER
                        || minecraft.getCurrentServer() == null && (!minecraft.hasSingleplayerServer() || !minecraft.getSingleplayerServer().isPublished()));
                if (!button.active) button.setTooltip(Tooltip.create(!spec.isLoaded() ? ConfigurationScreen.TOOLTIP_CANNOT_EDIT_NOT_LOADED
                        : minecraft.getCurrentServer() != null ? ConfigurationScreen.TOOLTIP_CANNOT_EDIT_THIS_WHILE_ONLINE
                        : ConfigurationScreen.TOOLTIP_CANNOT_EDIT_THIS_WHILE_OPEN_TO_LAN));
                list.addBig(button);
            }
        }
    }

    private static final class Section extends ConfigurationSectionScreen {
        Section(Screen parent, ModConfig config, ModConfigSpec spec, Component title) {
            super(new Context(config.getModId(), parent, config, spec, spec.getValues().entrySet(), spec.getSpec().entrySet().stream().collect(java.util.stream.Collectors.toMap(e -> e.getKey(), e -> e.getValue())),
                    List.of(), (c, k, e) -> e), title);
        }
    }
}
