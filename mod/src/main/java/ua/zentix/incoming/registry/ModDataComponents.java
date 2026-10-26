package ua.zentix.incoming.registry;

import com.mojang.serialization.Codec;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.launcher.LauncherLinks;
import ua.zentix.incoming.strike.Loadout;

import java.util.List;

public final class ModDataComponents {
    public static final DeferredRegister.DataComponents REGISTER = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Incoming.MOD_ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Loadout>> LOADOUT = REGISTER.registerComponentType("loadout",
            b -> b.persistent(Loadout.CODEC).networkSynchronized(Loadout.STREAM_CODEC));
    /**
     * Rounds remaining in an opened pack (munition with several rounds per item — Grad pack, {@code strike.Munitions});
     * full packs have no component and stack together.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> ROUNDS = REGISTER.registerComponentType("rounds",
            b -> b.persistent(Codec.intRange(1, Integer.MAX_VALUE)).networkSynchronized(ByteBufCodecs.VAR_INT));

    /**
     * Fixed launchers linked to a designator ({@code launcher.LauncherLinks}): “Fire” assigns them a mission instead of
     * launching directly.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<List<GlobalPos>>> LAUNCHERS = REGISTER.registerComponentType("launchers",
            b -> b.persistent(GlobalPos.CODEC.sizeLimitedListOf(LauncherLinks.MAX))
                    .networkSynchronized(GlobalPos.STREAM_CODEC.apply(ByteBufCodecs.list(LauncherLinks.MAX))));

    private ModDataComponents() {}
}
