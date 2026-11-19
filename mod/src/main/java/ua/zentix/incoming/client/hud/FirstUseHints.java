package ua.zentix.incoming.client.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.gui.components.toasts.TutorialToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.screen.RemoteScreen;
import ua.zentix.incoming.registry.ModItems;

/**
 * First-use hints for our items, taught the way the game teaches its own controls: a vanilla {@link TutorialToast}
 * (like the bundle tutorial) the first time the player holds the item, on screen for {@link #SHOW_MS} and closed early
 * once the player does what it teaches. A hint counts as shown when its toast is first drawn (not while F1 hides the
 * HUD or other toasts hold the queue), and is then remembered in {@code incoming-client.toml} ({@code shown_hints}, like
 * vanilla's {@code hideBundleTutorial} in options.txt), so a new world or server does not show it again.
 */
public final class FirstUseHints {
    /** Time on screen, ms: the vanilla bundle tutorial's 160 ticks ({@code BundleTutorial}). */
    static final long SHOW_MS = 8000;

    /**
     * One hint: shown when {@code item} is in either hand; text {@code incoming.hint.<id>.title} and
     * {@code incoming.hint.<id>}, short enough for the toast's single lines (it does not wrap; like vanilla's bundle hint,
     * the text names the default buttons); {@code done} is the screen that means the player has followed it.
     */
    record Hint(String id, Supplier<? extends Item> item, TutorialToast.Icons icon, Predicate<Screen> done) {}

    static final List<Hint> HINTS = List.of(
            new Hint("console", ModItems.DESIGNATOR, TutorialToast.Icons.RIGHT_CLICK, s -> s instanceof RemoteScreen));

    /** The hint toast waiting in the toast queue or on screen; null when none. */
    @Nullable
    private static HintToast toast;

    private FirstUseHints() {}

    /** Client tick while the game runs. */
    public static void tick(Minecraft mc) {
        Player player = mc.player;
        if (player == null || !IncomingConfig.CLIENT_SPEC.isLoaded()) return;
        if (toast != null) {
            // gone from the toast queue and screen: hidden, timed out or cleared (ToastComponent.clear)
            if (mc.getToasts().getToast(HintToast.class, Toast.NO_TOKEN) == null) toast = null;
            else if (mc.screen != null && toast.hint.done().test(mc.screen)) toast.hide();
            return;
        }
        if (mc.screen != null || mc.options.hideGui) return;
        List<? extends String> shown = IncomingConfig.CLIENT.shownHints.get();
        for (Hint hint : HINTS) {
            if (shown.contains(hint.id()) || !holds(player, hint.item().get())) continue;
            toast = new HintToast(hint);
            mc.getToasts().addToast(toast);
            return;
        }
    }

    private static boolean holds(Player player, Item item) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).is(item)) return true;
        }
        return false;
    }

    /** Records {@code id} as shown; a failed write keeps it for this session (the config value is already set). */
    private static void remember(String id) {
        List<String> shown = Ledger.claim(IncomingConfig.CLIENT.shownHints.get(), id);
        if (shown == null) return;
        IncomingConfig.CLIENT.shownHints.set(shown);
        try {
            IncomingConfig.CLIENT.shownHints.save();
        } catch (RuntimeException e) {
            Incoming.LOG.warn("Incoming: could not save shown_hints to incoming-client.toml", e);
        }
    }

    /**
     * Vanilla tutorial toast whose time and "shown" record start when it is first drawn; dismissed while still queued
     * (the player already did what it teaches), it closes without being recorded.
     */
    private static final class HintToast extends TutorialToast {
        final Hint hint;
        private boolean drawn, dismissed;

        HintToast(Hint hint) {
            super(hint.icon(), Component.translatable("incoming.hint." + hint.id() + ".title"),
                    Component.translatable("incoming.hint." + hint.id()), true);
            this.hint = hint;
        }

        @Override
        public Toast.Visibility render(GuiGraphics graphics, ToastComponent toasts, long timeSinceLastVisible) {
            if (!drawn) {
                if (dismissed) return Toast.Visibility.HIDE;
                drawn = true;
                remember(hint.id());
            }
            if (timeSinceLastVisible >= SHOW_MS) hide();
            updateProgress(Math.min(1f, (float) timeSinceLastVisible / SHOW_MS));
            return super.render(graphics, toasts, timeSinceLastVisible);
        }

        @Override
        public void hide() {
            dismissed = true;
            super.hide();
        }
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
