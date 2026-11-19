package ua.zentix.incoming.client.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.TutorialToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.screen.RemoteScreen;
import ua.zentix.incoming.registry.ModItems;

/**
 * First-use hints for our items, taught the way the game teaches its own controls: a vanilla {@link TutorialToast}
 * (like the bundle tutorial) shown the first time the player holds the item, for {@link #SHOW_TICKS}, and closed early
 * once the player does what it teaches. Each hint is shown once per client, remembered in
 * {@code incoming-client.toml} ({@code shown_hints}, like vanilla's {@code hideBundleTutorial} in options.txt), so a
 * new world or server does not show it again.
 */
public final class FirstUseHints {
    /** Vanilla bundle tutorial's time on screen, ticks ({@code BundleTutorial}). */
    static final int SHOW_TICKS = 160;

    /**
     * One hint: shown when {@code item} is in either hand; text {@code incoming.hint.<id>.title} and
     * {@code incoming.hint.<id>}, short enough for the toast's single lines (it does not wrap; like vanilla's bundle hint, the
     * text names the default buttons);
     * {@code done} is the screen that means the player has followed it.
     */
    record Hint(String id, java.util.function.Supplier<? extends Item> item, TutorialToast.Icons icon,
                Predicate<Screen> done) {}

    static final List<Hint> HINTS = List.of(
            new Hint("console", ModItems.DESIGNATOR, TutorialToast.Icons.RIGHT_CLICK, s -> s instanceof RemoteScreen));

    @Nullable
    private static TutorialToast toast;
    @Nullable
    private static Hint active;

    private FirstUseHints() {}

    /** Client tick while the game runs. */
    public static void tick(Minecraft mc) {
        Player player = mc.player;
        if (player == null) return;
        if (toast != null && active != null && mc.screen != null && active.done().test(mc.screen)) {
            mc.getTutorial().removeTimedToast(toast);
            toast = null;
            active = null;
        }
        if (mc.screen != null) return;
        for (Hint hint : HINTS) {
            if (!holds(player, hint.item().get())) continue;
            List<String> shown = Ledger.claim(IncomingConfig.CLIENT.shownHints.get(), hint.id());
            if (shown == null) continue;
            IncomingConfig.CLIENT.shownHints.set(shown);
            IncomingConfig.CLIENT.shownHints.save();
            toast = new TutorialToast(hint.icon(), Component.translatable("incoming.hint." + hint.id() + ".title"),
                    Component.translatable("incoming.hint." + hint.id()), true);
            active = hint;
            mc.getTutorial().addTimedToast(toast, SHOW_TICKS);
            return;
        }
    }

    private static boolean holds(Player player, Item item) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).is(item)) return true;
        }
        return false;
    }

    /** Which hints this client has shown (pure, for tests: does not load the item registry). */
    static final class Ledger {
        private Ledger() {}

        /** Shown ids after showing {@code id}, or null if it was already shown. */
        @Nullable
        static List<String> claim(List<? extends String> shown, String id) {
            if (shown.contains(id)) return null;
            List<String> next = new ArrayList<>(shown);
            next.add(id);
            return next;
        }
    }
}
