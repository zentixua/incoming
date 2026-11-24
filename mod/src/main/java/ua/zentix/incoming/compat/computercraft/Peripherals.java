package ua.zentix.incoming.compat.computercraft;

import dan200.computercraft.api.peripheral.PeripheralCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import ua.zentix.incoming.registry.ModBlockEntities;

/**
 * CC:Tweaked peripherals of the fixed launcher ({@link LauncherPeripheral}) and the SAM ({@link SamPeripheral}), on
 * CC:Tweaked's block capability ({@link PeripheralCapability}) from every side. The listener is added from
 * {@code Incoming} only when CC:Tweaked is loaded: no other class names its API, so without it this package is never
 * loaded.
 */
public final class Peripherals {
    private Peripherals() {}

    public static void register(RegisterCapabilitiesEvent e) {
        e.registerBlockEntity(PeripheralCapability.get(), ModBlockEntities.FIXED_LAUNCHER.get(), (be, side) -> new LauncherPeripheral(be));
        e.registerBlockEntity(PeripheralCapability.get(), ModBlockEntities.SAM.get(), (be, side) -> new SamPeripheral(be));
    }
}
