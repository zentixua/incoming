package ua.zentix.incoming.compat.create;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateRegistries;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.NumericSingleLineDisplaySource;
import com.simibubi.create.content.redstone.displayLink.source.SingleLineDisplaySource;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.info.Automation;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.launcher.LauncherOrders;
import ua.zentix.incoming.registry.ModBlockEntities;

/**
 * Create Display Link sources of the SAM and the fixed launcher: a Display Link attached to one of them writes its line
 * to a display board, sign, lectern or nixie tube. Each value is the one a computer reads ({@link Automation}: {@code tracking}, {@code stock}, {@code ready}), so a
 * board, a computer and the Jade overlay never disagree. Create's public registry ({@link CreateRegistries#DISPLAY_SOURCE})
 * and its block-entity attachment ({@link DisplaySource#BY_BLOCK_ENTITY}); the single-line base classes give the label
 * and the flap-board layout Create's own sources have. {@link #install} is called from {@code Incoming} only when Create
 * is loaded: no other class names Create's API, so without it this package is never loaded.
 */
public final class DisplaySources {
    private DisplaySources() {}

    private static final DeferredRegister<DisplaySource> REGISTER = DeferredRegister.create(CreateRegistries.DISPLAY_SOURCE, Incoming.MOD_ID);

    /** SAM: {@code ALERT} while its latest sweep tracks a hostile target (its redstone alarm), {@code Clear} otherwise. */
    public static final DeferredHolder<DisplaySource, SamAlert> SAM_ALERT = REGISTER.register("sam_alert", SamAlert::new);
    /** Fixed launcher: rounds held, every accepted weapon together (a Grad pack counts its rockets). */
    public static final DeferredHolder<DisplaySource, LauncherMunitions> LAUNCHER_MUNITIONS =
            REGISTER.register("launcher_munitions", LauncherMunitions::new);
    /** Fixed launcher: {@code Ready} when a redstone signal would fire it now, {@code Not ready} otherwise. */
    public static final DeferredHolder<DisplaySource, LauncherReady> LAUNCHER_READY = REGISTER.register("launcher_ready", LauncherReady::new);

    public static void install(IEventBus modBus) {
        REGISTER.register(modBus);
        modBus.addListener(DisplaySources::attach);
    }

    /** Create's attachment registry is a plain map: filled once, on the main thread, after both registries froze. */
    private static void attach(FMLCommonSetupEvent e) {
        e.enqueueWork(() -> {
            DisplaySource.BY_BLOCK_ENTITY.add(ModBlockEntities.SAM.get(), SAM_ALERT.get());
            DisplaySource.BY_BLOCK_ENTITY.add(ModBlockEntities.FIXED_LAUNCHER.get(), LAUNCHER_MUNITIONS.get());
            DisplaySource.BY_BLOCK_ENTITY.add(ModBlockEntities.FIXED_LAUNCHER.get(), LAUNCHER_READY.get());
        });
    }

    /** The source block entity when it is ours and on the server (Create reads sources only there), else {@code null}. */
    private static <T extends BlockEntity> T source(DisplayLinkContext context, Class<T> type) {
        if (!(context.level() instanceof ServerLevel)) return null;
        BlockEntity be = context.getSourceBlockEntity();
        return type.isInstance(be) ? type.cast(be) : null;
    }

    public static final class SamAlert extends SingleLineDisplaySource {
        /** Once per radar sweep, not Create's default 5 s: the board keeps up with the alarm (half a second). */
        @Override
        public int getPassiveRefreshTicks() {
            return SamBlockEntity.SWEEP;
        }

        @Override
        protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
            SamBlockEntity sam = source(context, SamBlockEntity.class);
            if (sam == null) return EMPTY_LINE;
            // the alarm's condition, the computer's "tracking"
            return Component.translatable(sam.hostile() > 0 ? "incoming.display_source.sam_alert.on" : "incoming.display_source.sam_alert.off");
        }

        @Override
        protected boolean allowsLabeling(DisplayLinkContext context) {
            return true;
        }
    }

    public static final class LauncherMunitions extends NumericSingleLineDisplaySource {
        @Override
        protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
            FixedLauncherBlockEntity launcher = source(context, FixedLauncherBlockEntity.class);
            if (launcher == null) return EMPTY_LINE;
            int rounds = 0;
            for (Object held : Automation.stock(launcher).values()) rounds += ((Number) held).intValue();
            return Component.literal(Integer.toString(rounds));
        }

        @Override
        protected boolean allowsLabeling(DisplayLinkContext context) {
            return true;
        }
    }

    public static final class LauncherReady extends SingleLineDisplaySource {
        @Override
        protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
            FixedLauncherBlockEntity launcher = source(context, FixedLauncherBlockEntity.class);
            if (launcher == null) return EMPTY_LINE;
            // the redstone path's own checks, the computer's "ready"
            return Component.translatable(LauncherOrders.problem((ServerLevel) context.level(), launcher) == null ? "incoming.display_source.launcher_ready.on" : "incoming.display_source.launcher_ready.off");
        }

        @Override
        protected boolean allowsLabeling(DisplayLinkContext context) {
            return true;
        }
    }
}
