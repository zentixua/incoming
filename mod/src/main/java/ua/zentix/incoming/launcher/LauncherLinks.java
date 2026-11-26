package ua.zentix.incoming.launcher;

import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.registry.ModDataComponents;
import ua.zentix.incoming.strike.Authority;
import ua.zentix.incoming.target.Sides;

import java.util.ArrayList;
import java.util.List;

/**
 * Designator and fixed launchers: right-click a launcher with a designator to link ({@link
 * ModDataComponents#LAUNCHERS} component stores launcher positions); repeat to unlink. While linked launchers
 * exist, “Fire” assigns them a mission instead of launching directly: the same crosshair/map target, weapon,
 * count, spread, and route ({@link Mission}). Can link own or team launchers ({@link Sides}); only an operator
 * can link an unowned one (placed by command).
 */
public final class LauncherLinks {
    /** Maximum launchers linked to one designator. */
    public static final int MAX = 16;

    private LauncherLinks() {}

    /** Launchers linked to {@code designator}. */
    public static List<GlobalPos> of(ItemStack designator) {
        return designator.getOrDefault(ModDataComponents.LAUNCHERS.get(), List.of());
    }

    /**
     * Why the player may not command the launcher, or null: they need the right to link launchers ({@link Authority},
     * {@code incoming.launcher.link} after the designator's own) and a launcher owned by them, their team, or unowned
     * (operators). Without a permission mod every refusal reads "not your launcher".
     */
    @Nullable
    public static MutableComponent refusal(ServerPlayer player, FixedLauncherBlockEntity be) {
        Authority.Denial denied = Authority.check(Authority.Action.LINK, Authority.Actor.of(player));
        if (denied != null && !denied.byDefault()) return denied.line();
        boolean owned = be.owner() == null ? player.hasPermissions(2) : Sides.friendly(player.server, be.owner(), player.getUUID());
        return denied == null && owned ? null : Component.translatable("incoming.fixed_launcher.not_yours");
    }

    /** Link launcher {@code be} to {@code designator} or unlink it; line describes the result. */
    public static Component toggle(ServerPlayer player, ItemStack designator, FixedLauncherBlockEntity be) {
        GlobalPos at = GlobalPos.of(player.level().dimension(), be.getBlockPos());
        List<GlobalPos> links = new ArrayList<>(of(designator));
        if (links.remove(at)) {
            set(designator, links);
            return Component.translatable("incoming.fixed_launcher.unlinked", links.size()).withStyle(ChatFormatting.GRAY);
        }
        MutableComponent refused = refusal(player, be);
        if (refused != null) return refused.withStyle(ChatFormatting.RED);
        if (links.size() >= MAX) return Component.translatable("incoming.fixed_launcher.links_full", MAX).withStyle(ChatFormatting.RED);
        links.add(at);
        set(designator, links);
        return Component.translatable("incoming.fixed_launcher.linked", links.size()).withStyle(ChatFormatting.GOLD);
    }

    /** Unlink launchers in {@code gone} from {@code designator}. */
    public static void forget(ItemStack designator, List<GlobalPos> gone) {
        List<GlobalPos> links = new ArrayList<>(of(designator));
        if (links.removeAll(gone)) set(designator, links);
    }

    private static void set(ItemStack designator, List<GlobalPos> links) {
        if (links.isEmpty()) designator.remove(ModDataComponents.LAUNCHERS.get());
        else designator.set(ModDataComponents.LAUNCHERS.get(), List.copyOf(links));
    }
}
