package ua.zentix.incoming.nuclear.radiation;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.registry.ModDataMaps;

/**
 * Radiation shielding a body wears (doc universal-integration C1). Each worn piece stops a share of new exposure: the
 * item's {@link ModDataMaps#RADIATION_SHIELDING} value, otherwise an installed {@link Source} (Mekanism's item capability,
 * {@code compat/mekanism}), otherwise nothing. The pieces add up, capped at 1, as Mekanism combines its own equipment, so
 * a full Mekanism hazmat set (0.25 + 0.4 + 0.2 + 0.15) stops everything. Prompt dose, fallout and black-rain contamination
 * are multiplied by {@link #transmitted}; the accumulated dose, the sickness it started and dust already on the body are
 * not: putting a suit on never lowers what was received.
 * <p>
 * Worn means the armor slots and the body slot (animal armor); an item in hand is carried, not worn.
 */
public final class WornShielding {
    /** Another source of a piece's shielding; {@link Double#NaN} — it does not describe this item. */
    @FunctionalInterface
    public interface Source {
        double shielding(ItemStack stack);
    }

    /** No source installed: only the data map. */
    public static final Source NONE = stack -> Double.NaN;

    private static final EquipmentSlot[] WORN = java.util.Arrays.stream(EquipmentSlot.values()).filter(EquipmentSlot::isArmor)
            .toArray(EquipmentSlot[]::new);

    private static volatile Source source = NONE;

    private WornShielding() {}

    /** Install the other source (once, at mod construction, by a compat module); returns the previous one. */
    public static Source install(Source next) {
        Source before = source;
        source = next;
        return before;
    }

    public static Source source() {
        return source;
    }

    /** Share of new exposure that reaches the body under what it wears, 0..1. */
    public static double transmitted(LivingEntity e) {
        return 1 - of(e);
    }

    /** Worn shielding of a body: its pieces summed, capped at 1. */
    public static double of(LivingEntity e) {
        Source other = source;
        double sum = 0;
        for (EquipmentSlot slot : WORN) {
            ItemStack stack = e.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            Float data = stack.getItemHolder().getData(ModDataMaps.RADIATION_SHIELDING);
            sum += piece(data, data != null ? Double.NaN : other.shielding(stack));
            if (sum >= 1) return 1;
        }
        return sum;
    }

    /**
     * One piece: the data map's value when it lists the item, otherwise the other source's value limited to [0, 1] (it has
     * no load step to reject a broken value; NaN — none).
     */
    static double piece(@Nullable Float data, double other) {
        if (data != null) return data;
        return Double.isNaN(other) ? 0 : Math.clamp(other, 0, 1);
    }
}
