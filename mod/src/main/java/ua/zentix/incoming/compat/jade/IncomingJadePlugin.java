package ua.zentix.incoming.compat.jade;

import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.defense.SamBlock;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.grid.SubstationBlock;
import ua.zentix.incoming.launcher.FixedLauncherBlock;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;

/**
 * Jade overlay lines for the fixed launcher, the SAM, the substation and strike projectiles ({@link Lines}). Jade finds
 * this class by its {@link WailaPlugin} annotation while it loads plugins; nothing in Incoming names it, so without
 * Jade it is never loaded. Server data providers send what the client does not have ({@code info/Readout}: owner and
 * mission only to the owner's side and operators); the client side only displays the lines.
 */
@WailaPlugin(Incoming.MOD_ID)
public final class IncomingJadePlugin implements IWailaPlugin {
    @Override
    public void register(IWailaCommonRegistration r) {
        r.registerBlockDataProvider(Lines.LAUNCHER, FixedLauncherBlockEntity.class);
        r.registerBlockDataProvider(Lines.SAM, SamBlockEntity.class);
        r.registerBlockDataProvider(Lines.SUBSTATION, SubstationBlock.class);
        r.registerEntityDataProvider(Lines.PROJECTILE, StrikeProjectile.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration r) {
        r.registerBlockComponent(Lines.LAUNCHER, FixedLauncherBlock.class);
        r.registerBlockComponent(Lines.SAM, SamBlock.class);
        r.registerBlockComponent(Lines.SUBSTATION, SubstationBlock.class);
        r.registerEntityComponent(Lines.PROJECTILE, StrikeProjectile.class);
    }
}
