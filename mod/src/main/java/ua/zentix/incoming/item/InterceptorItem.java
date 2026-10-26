package ua.zentix.incoming.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Surface-to-air missile — SAM stock ({@link ua.zentix.incoming.defense.SamBlock}): inserted by hand (right-click the SAM),
 * hopper or Create funnel (block entity item capability). Does nothing on its own — the tooltip says where it goes.
 */
public class InterceptorItem extends Item {
    public InterceptorItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.incoming.interceptor.tip").withStyle(ChatFormatting.GRAY));
    }
}
