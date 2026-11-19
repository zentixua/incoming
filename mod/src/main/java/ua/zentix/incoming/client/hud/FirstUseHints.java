package ua.zentix.incoming.client.hud;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * (like the bundle tutorial) the first time the player holds the item, closed early once the player does what it
 * teaches. Its time counts like vanilla's tutorial timer, in unpaused game ticks, but only ticks on which the toast was
 * actually drawn (not while F1 hides the HUD or other toasts hold the queue). A hint is remembered in
 * {@code incoming-client.toml} ({@code shown_hints}, like vanilla's {@code hideBundleTutorial} in options.txt) once it
 * has been on screen for {@link #SEEN_TICKS}, or as soon as the player does what it teaches, so a new world or server
 * does not show it again.
 */
public final class FirstUseHints {
    /** Time on screen, ticks: the vanilla bundle tutorial's ({@code BundleTutorial}). */
    static final int SHOW_TICKS = 160;
    /** Drawn ticks after which the hint counts as seen: past the toast's 0.6 s slide-in, long enough to read the title. */
    static final int SEEN_TICKS = 30;

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
    /** Hints another mod refused to show (NeoForge {@code ToastAddEvent} cancelled): not offered again this session. */
    private static final Set<String> refused = new HashSet<>();

    private FirstUseHints() {}

    /** Every client tick with a level, paused or not. */
    public static void tick(Minecraft mc) {
        Player player = mc.player;
        if (player == null || !IncomingConfig.CLIENT_SPEC.isLoaded()) return;
        Screen screen = mc.screen;
        if (screen != null) {
            for (Hint hint : HINTS) {
                if (!hint.done().test(screen)) continue;
                remember(hint.id());
                if (toast != null && toast.hint == hint) toast.hide();
            }
        }
        if (toast != null) {
            // gone from the toast queue and screen: timed out, hidden or cleared (ToastComponent.clear on leaving)
            if (mc.getToasts().getToast(HintToast.class, Toast.NO_TOKEN) == null) toast = null;
            else if (!mc.isPaused()) toast.tick();
            return;
        }
        if (screen != null || mc.options.hideGui) return;
        List<? extends String> shown = IncomingConfig.CLIENT.shownHints.get();
        for (Hint hint : HINTS) {
            if (shown.contains(hint.id()) || refused.contains(hint.id()) || !holds(player, hint.item().get())) continue;
            HintToast t = new HintToast(hint);
            mc.getToasts().addToast(t);
            if (mc.getToasts().getToast(HintToast.class, Toast.NO_TOKEN) == t) toast = t;
            else refused.add(hint.id());
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

    /** Vanilla tutorial toast that counts the ticks on which it was drawn. */
    private static final class HintToast extends TutorialToast {
        final Hint hint;
        private boolean drawn;
        private int ticks;

        HintToast(Hint hint) {
            super(hint.icon(), Component.translatable("incoming.hint." + hint.id() + ".title"),
                    Component.translatable("incoming.hint." + hint.id()), true);
            this.hint = hint;
        }

        /** One unpaused game tick: counts it if the toast was drawn since the last one. */
        void tick() {
            if (!drawn) return;
            drawn = false;
            ticks++;
            if (ticks == SEEN_TICKS) remember(hint.id());
            updateProgress((float) ticks / SHOW_TICKS);
            if (ticks >= SHOW_TICKS) hide();
        }

        @Override
        public Toast.Visibility render(GuiGraphics graphics, ToastComponent toasts, long timeSinceLastVisible) {
            drawn = true;
            return super.render(graphics, toasts, timeSinceLastVisible);
        }
    }

    /** Which hints are recorded (pure, for tests: does not load the item registry). */
    static final class Ledger {
        private Ledger() {}

        /** Shown ids after recording {@code id}, or null if it was already recorded. */
        @Nullable
        static List<String> claim(List<? extends String> shown, String id) {
            if (shown.contains(id)) return null;
            List<String> next = new ArrayList<>(shown);
            next.add(id);
            return next;
        }

    }
}
