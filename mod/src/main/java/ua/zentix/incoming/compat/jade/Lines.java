package ua.zentix.incoming.compat.jade;

import com.mojang.serialization.MapCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.info.Readout;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;

import java.util.List;

/**
 * One Jade provider per target: on the server it writes {@link Readout}'s lines for the viewing player into one data
 * field named after the provider; on the client it adds that field's lines to the tooltip. A viewer without rights gets
 * no owner or mission in the field at all. Each provider is a Jade config toggle ({@code config.jade.plugin_incoming.*}).
 */
abstract class Lines<A extends Accessor<?>> implements IServerDataProvider<A>, IComponentProvider<A> {
    static final Lines<BlockAccessor> LAUNCHER = new Lines<>("launcher") {
        @Override
        List<Component> server(ServerLevel level, ServerPlayer viewer, BlockAccessor a) {
            return a.getBlockEntity() instanceof FixedLauncherBlockEntity be ? Readout.launcher(level, be, viewer) : List.of();
        }
    };
    static final Lines<BlockAccessor> SAM = new Lines<>("sam") {
        @Override
        List<Component> server(ServerLevel level, ServerPlayer viewer, BlockAccessor a) {
            return a.getBlockEntity() instanceof SamBlockEntity be ? Readout.sam(be) : List.of();
        }
    };
    static final Lines<BlockAccessor> SUBSTATION = new Lines<>("substation") {
        @Override
        List<Component> server(ServerLevel level, ServerPlayer viewer, BlockAccessor a) {
            return Readout.substation(level, a.getPosition());
        }
    };
    static final Lines<EntityAccessor> PROJECTILE = new Lines<>("projectile") {
        @Override
        List<Component> server(ServerLevel level, ServerPlayer viewer, EntityAccessor a) {
            return a.getEntity() instanceof StrikeProjectile p ? Readout.projectile(level.getServer(), p, viewer) : List.of();
        }

        /** The weapon is the client's own: the entity's type and its synced nuclear flag. */
        @Override
        void client(ITooltip tooltip, EntityAccessor a) {
            if (a.getEntity() instanceof StrikeProjectile p) tooltip.add(weapon(p));
        }
    };

    final ResourceLocation uid;
    /** The data field: the lines as components, under the provider's own name. */
    final MapCodec<List<Component>> field;

    private Lines(String name) {
        uid = ResourceLocation.fromNamespaceAndPath(Incoming.MOD_ID, name);
        field = ComponentSerialization.CODEC.listOf().fieldOf(Incoming.MOD_ID + "_" + name);
    }

    /** Lines for {@code viewer} (server thread; the target is in a loaded chunk within the viewer's reach). */
    abstract List<Component> server(ServerLevel level, ServerPlayer viewer, A accessor);

    /** Lines the client knows by itself, before the server's. */
    void client(ITooltip tooltip, A accessor) {}

    static Component weapon(StrikeProjectile p) {
        return p.isNuclear() ? Component.translatable("incoming.info.weapon.nuclear", p.weapon().displayName())
                : Component.translatable("incoming.info.weapon", p.weapon().displayName());
    }

    @Override
    public final void appendServerData(CompoundTag data, A accessor) {
        if (!(accessor.getLevel() instanceof ServerLevel level) || !(accessor.getPlayer() instanceof ServerPlayer viewer)) return;
        List<Component> lines = server(level, viewer, accessor);
        if (!lines.isEmpty()) accessor.writeData(field, lines);
    }

    @Override
    public final void appendTooltip(ITooltip tooltip, A accessor, IPluginConfig config) {
        client(tooltip, accessor);
        accessor.readData(field).ifPresent(lines -> lines.forEach(tooltip::add));
    }

    @Override
    public final ResourceLocation getUid() {
        return uid;
    }
}
