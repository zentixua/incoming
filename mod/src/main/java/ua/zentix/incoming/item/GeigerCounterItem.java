package ua.zentix.incoming.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Geiger counter: while held, the client clicks at a rate set by dose rate and displays it on screen
 * (client.nuclear.Geiger). The server computes the rate and sends it in a {@code Radiation} packet.
 */
public class GeigerCounterItem extends Item {
    public GeigerCounterItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.incoming.geiger_counter.tip").withStyle(ChatFormatting.GRAY));
    }
}
