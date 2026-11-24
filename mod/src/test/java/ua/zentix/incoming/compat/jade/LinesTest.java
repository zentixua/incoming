package ua.zentix.incoming.compat.jade;

import com.mojang.serialization.MapCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.WailaPlugin;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.defense.SamBlock;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.grid.SubstationBlock;
import ua.zentix.incoming.launcher.FixedLauncherBlock;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Jade adapter against Jade's API (Jade is not in the GameTest runtime; what the lines say and who gets them is
 * {@code ReadoutGameTests}): the plugin registers each provider for its block, block entity or entity on both sides, each
 * provider writes and reads one field of its own, the field carries the lines unchanged, and a request without a server
 * player and level writes nothing.
 */
class LinesTest {
    private static final List<Lines<?>> ALL = List.of(Lines.LAUNCHER, Lines.SAM, Lines.SUBSTATION, Lines.PROJECTILE);

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        // as PackDocsTest: NeoForge's feature flags read the mod list while blocks initialize
        if (net.neoforged.fml.loading.LoadingModList.get() == null) {
            net.neoforged.fml.loading.LoadingModList.of(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of());
        }
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void pluginRegistersEveryProviderOnBothSides() {
        assertEquals(Incoming.MOD_ID, IncomingJadePlugin.class.getAnnotation(WailaPlugin.class).value());
        IWailaCommonRegistration common = mock(IWailaCommonRegistration.class);
        IWailaClientRegistration client = mock(IWailaClientRegistration.class);
        IncomingJadePlugin plugin = new IncomingJadePlugin();
        plugin.register(common);
        plugin.registerClient(client);
        verify(common).registerBlockDataProvider(Lines.LAUNCHER, FixedLauncherBlockEntity.class);
        verify(common).registerBlockDataProvider(Lines.SAM, SamBlockEntity.class);
        verify(common).registerBlockDataProvider(Lines.SUBSTATION, SubstationBlock.class);
        verify(common).registerEntityDataProvider(Lines.PROJECTILE, StrikeProjectile.class);
        verify(client).registerBlockComponent(Lines.LAUNCHER, FixedLauncherBlock.class);
        verify(client).registerBlockComponent(Lines.SAM, SamBlock.class);
        verify(client).registerBlockComponent(Lines.SUBSTATION, SubstationBlock.class);
        verify(client).registerEntityComponent(Lines.PROJECTILE, StrikeProjectile.class);
    }

    @Test
    void eachProviderHasItsOwnIdAndField() {
        Set<String> ids = ALL.stream().map(l -> l.getUid().toString()).collect(Collectors.toSet());
        assertEquals(Set.of("incoming:launcher", "incoming:sam", "incoming:substation", "incoming:projectile"), ids);
        // Jade's config screen names each toggle by this key: both language files carry it (LangTest checks parity)
        assertEquals(4, ALL.stream().map(l -> l.field.toString()).distinct().count());
    }

    @Test
    void fieldCarriesTheLinesUnchanged() {
        List<Component> lines = List.of(Component.translatable("incoming.info.owner", Component.literal("ZentixUA")),
                Component.translatable("incoming.info.cannot", Component.translatable("incoming.fixed_launcher.busy")));
        CompoundTag tag = new CompoundTag();
        Tag written = Lines.LAUNCHER.field.encoder().encodeStart(NbtOps.INSTANCE, lines).getOrThrow();
        tag.merge((CompoundTag) written);
        // a literal argument comes back as its plain string: the same line on screen
        assertEquals(lines.stream().map(Component::getString).toList(), decode(Lines.LAUNCHER.field, tag).stream().map(Component::getString).toList());
        assertEquals(lines.get(1), decode(Lines.LAUNCHER.field, tag).get(1));
    }

    @Test
    void tooltipShowsTheServerLinesInOrder() {
        List<Component> lines = List.of(Component.literal("a"), Component.literal("b"));
        BlockAccessor a = mock(BlockAccessor.class);
        when(a.readData(Lines.SAM.field)).thenReturn(Optional.of(lines));
        ITooltip tooltip = mock(ITooltip.class);
        Lines.SAM.appendTooltip(tooltip, a, null);
        var order = org.mockito.Mockito.inOrder(tooltip);
        order.verify(tooltip).add(lines.get(0));
        order.verify(tooltip).add(lines.get(1));
    }

    @Test
    void noServerViewerNoData() {
        BlockAccessor a = mock(BlockAccessor.class);
        when(a.getLevel()).thenReturn(mock(Level.class));
        Stream.of(Lines.LAUNCHER, Lines.SAM, Lines.SUBSTATION).forEach(l -> l.appendServerData(new CompoundTag(), a));
        verify(a, never()).writeData(any(), any());
    }

    private static List<Component> decode(MapCodec<List<Component>> field, CompoundTag tag) {
        return field.decoder().parse(NbtOps.INSTANCE, tag).getOrThrow();
    }
}
