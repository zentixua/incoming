package ua.zentix.incoming.compat.mekanism;

import mekanism.api.radiation.capability.IRadiationShielding;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.ItemCapability;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.nuclear.radiation.WornShielding;

/**
 * Mekanism's radiation shielding of worn items (hazmat suit, MekaSuit modules) as Incoming's worn shielding: its item
 * capability {@code mekanism:radiation_shielding} through Mekanism's public API ({@link IRadiationShielding}). A capability
 * is identified by name and type, so this lookup returns the instance Mekanism registers its items under. Installed from
 * {@code Incoming} only when Mekanism is loaded: no other class names Mekanism's API, and without Mekanism this class is
 * never loaded. The {@code incoming:radiation_shielding} data map wins for an item it lists ({@link WornShielding}).
 */
public final class MekanismShielding {
    static final ResourceLocation NAME = ResourceLocation.fromNamespaceAndPath("mekanism", "radiation_shielding");
    static final ItemCapability<IRadiationShielding, Void> CAPABILITY = ItemCapability.createVoid(NAME, IRadiationShielding.class);

    private MekanismShielding() {}

    public static void install() {
        WornShielding.install(MekanismShielding::of);
    }

    static double of(ItemStack stack) {
        return value(stack.getCapability(CAPABILITY));
    }

    /** The piece's shielding share as Mekanism gives it; NaN — the item has none. */
    static double value(@Nullable IRadiationShielding shielding) {
        return shielding == null ? Double.NaN : shielding.getRadiationShielding();
    }
}
