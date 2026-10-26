package ua.zentix.incoming.client.screen;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.aim.Designator;
import ua.zentix.incoming.client.map.MapPlayers;
import ua.zentix.incoming.client.map.MapTarget;
import ua.zentix.incoming.client.map.TerrainTiles;
import ua.zentix.incoming.client.nuclear.NukeArming;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.item.DesignatorItem;
import ua.zentix.incoming.launcher.LauncherLinks;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.nuclear.model.Yield;
import ua.zentix.incoming.registry.ModDataComponents;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.TargetPicker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * Remote screen (replaces chat menu): weapon, count, spread and target: where I look, around me, player from list,
 * nearby craft, or map location ({@link MapScreen}); nuclear weapon adds yield and burst, launch after 3 s arming.
 * Settings saved in remote; server validates launch.
 */
public class RemoteScreen extends Screen {
    private static final int W = 320;
    /** Text line: Minecraft font height. */
    private static final int LINE = 9;
    /**
     * Window height without weapon description and target hint. Text wraps to window width rather than overflowing:
     * description gets as many lines as the menu's longest (window stays stable when switching weapons), hint gets two.
     */
    private static final int BASE_H = 204, HINT_LINES = 2;
    private static final int PAGE = 8;
    /** Settings for those without remote in hand (screen opened by command). */
    private static Loadout detached = Loadout.DEFAULT;

    @Nullable
    private final InteractionHand hand;
    private Loadout loadout;
    @Nullable
    private UUID aircraft;
    private int page;
    private final List<Choice> choices = new ArrayList<>();
    /** Weapon description lines and window height: computed in {@link #init()} from font. */
    private int descLines = 1, h = BASE_H + (1 + HINT_LINES) * LINE;

    private record Choice(Component label, String player, @Nullable UUID aircraft) {}

    /** Query server for players every this many ticks while player mode is open. */
    private static final int PLAYERS_PERIOD = 10;
    private int ticks;
    /** Players currently listed. */
    private List<String> shownPlayers = List.of();

    public RemoteScreen() {
        super(Component.translatable("incoming.remote.title"));
        LocalPlayer p = Minecraft.getInstance().player;
        InteractionHand h = null;
        if (p != null) {
            for (InteractionHand x : InteractionHand.values()) {
                if (p.getItemInHand(x).getItem() instanceof DesignatorItem) {
                    h = x;
                    break;
                }
            }
        }
        this.hand = h;
        this.loadout = h != null && p != null ? DesignatorItem.loadout(p.getItemInHand(h)) : detached;
    }

    @Override
    protected void init() {
        List<WeaponType> menu = WeaponType.menu();
        descLines = Math.max(1, menu.stream().mapToInt(w -> font.split(w.description(), W - 8).size()).max().orElse(1));
        h = BASE_H + (descLines + HINT_LINES) * LINE;
        int x0 = (width - W) / 2, y0 = (height - h) / 2;
        int y = y0 + 22;

        // Weapon.
        List<Cell> weapons = new ArrayList<>();
        for (WeaponType w : menu) {
            weapons.add(new Cell(Button.builder(label(w.displayName(), loadout.weapon() == w), btn -> set(loadout.withWeapon(w)))
                    .bounds(0, y, 0, 20).tooltip(Tooltip.create(w.description())).build()));
        }
        row(weapons, x0);
        // Selected weapon description below buttons (render).
        y += 22 + descLines * LINE + 3;

        // Count and spread; nuclear weapon uses yield and burst.
        int sw = (W - 12) / 2;
        Loadout.Nuke n = loadout.nuke();
        if (loadout.nuclear()) {
            // ICBM: yield and burst; nuclear missile and B-2: warhead switch, yield, and burst for missile.
            WeaponSpec.Warhead warhead = loadout.weapon().spec().warhead();
            List<Cell> nuke = new ArrayList<>();
            if (!warhead.always()) nuke.add(new Cell(warheadButton(n, 0, y, 0)));
            // Yield: any integer 1 to MAX_YIELD, as in command and Loadout.Nuke (server caps at max_yield);
            // preset names at their values; row width from longest label.
            List<Component> yields = new ArrayList<>();
            for (int kt = 1; kt <= Loadout.Nuke.MAX_YIELD; kt++) yields.add(yieldLabel(kt));
            nuke.add(new Cell(new IntSlider(0, y, 0, 20, RemoteScreen::yieldLabel, 1, Loadout.Nuke.MAX_YIELD, n.yieldKt(),
                    v -> commit(loadout.withNuke(loadout.nuke().withYield(v)))), yields));
            if (warhead.chooseBurst()) {
                Component air = Component.translatable("incoming.remote.burst.air"), ground = Component.translatable("incoming.remote.burst.ground");
                nuke.add(new Cell(Button.builder(n.airBurst() ? air : ground,
                        b -> set(loadout.withNuke(loadout.nuke().withAirBurst(!loadout.nuke().airBurst())))).bounds(0, y, 0, 20)
                        .tooltip(Tooltip.create(Component.translatable("incoming.remote.burst.tooltip"))).build(), List.of(air, ground)));
            }
            row(nuke, x0);
        } else if (Loadout.carriesNuke(loadout.weapon())) {
            int cw = (W - 16) / 3;
            addRenderableWidget(new IntSlider(x0 + 4, y, cw, 20, "incoming.remote.count", 1, 30, loadout.count(), v -> commit(loadout.withCount(v))));
            addRenderableWidget(new IntSlider(x0 + 8 + cw, y, cw, 20, "incoming.remote.spread", 0, 150, loadout.spread(), v -> commit(loadout.withSpread(v))));
            addRenderableWidget(warheadButton(n, x0 + 12 + 2 * cw, y, cw));
        } else {
            addRenderableWidget(new IntSlider(x0 + 4, y, sw, 20, "incoming.remote.count", 1, 30, loadout.count(), v -> commit(loadout.withCount(v))));
            addRenderableWidget(new IntSlider(x0 + 8 + sw, y, sw, 20, "incoming.remote.spread", 0, 150, loadout.spread(), v -> commit(loadout.withSpread(v))));
        }
        y += 26;

        // Target mode.
        List<Cell> modes = new ArrayList<>();
        for (TargetMode m : TargetMode.values()) {
            modes.add(new Cell(Button.builder(label(m.displayName(), loadout.mode() == m), b -> {
                page = 0;
                set(loadout.withMode(m));
            }).bounds(0, y, 0, 20).build()));
        }
        row(modes, x0);
        y += 24;

        // Map location: open targeting map.
        if (loadout.mode() == TargetMode.MAP) {
            addRenderableWidget(Button.builder(Component.translatable("incoming.remote.open_map").withStyle(ChatFormatting.YELLOW), b -> openMap())
                    .bounds(x0 + 4, y, W - 8, 20).build());
        }

        // List: players or nearby craft.
        choices.clear();
        if (loadout.mode() == TargetMode.PLAYER) collectPlayers();
        if (loadout.mode() == TargetMode.AIRCRAFT) collectAircraft();
        int cw = (W - 12) / 2;
        int pages = Math.max(1, (choices.size() + PAGE - 1) / PAGE);
        page = Mth.clamp(page, 0, pages - 1);
        for (int i = 0; i < PAGE && page * PAGE + i < choices.size(); i++) {
            Choice c = choices.get(page * PAGE + i);
            boolean on = c.aircraft != null ? c.aircraft.equals(aircraft) : c.player.equals(loadout.player());
            int x = x0 + 4 + (i % 2) * (cw + 4);
            addRenderableWidget(Button.builder(label(c.label, on), b -> {
                if (c.aircraft != null) aircraft = c.aircraft;
                set(c.aircraft != null ? loadout : loadout.withPlayer(c.player));
            }).bounds(x, y + (i / 2) * 20, cw, 18).build());
        }
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
                page = (page + pages - 1) % pages;
                rebuildWidgets();
            }).bounds(x0 + W - 48, y0 + 4, 20, 14).build());
            addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
                page = (page + 1) % pages;
                rebuildWidgets();
            }).bounds(x0 + W - 24, y0 + 4, 20, 14).build());
        }

        // Fire, abort, settings.
        int by = y0 + h - 26;
        // Remote with linked launchers assigns them missions rather than launching itself (launcher.LauncherLinks).
        int linked = linked();
        Component fireLabel = linked > 0 ? Component.translatable("incoming.remote.assign", linked) : Component.translatable("incoming.remote.fire");
        // Map route and launch site apply to On the map target, as when firing from map itself.
        boolean map = loadout.mode() == TargetMode.MAP;
        addRenderableWidget(Button.builder(fireLabel.copy().withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                b -> fire(map ? MapTarget.waypoints(minecraft.level) : Waypoints.NONE, map ? launchFrom() : Optional.empty()))
                .bounds(x0 + 4, by, 128, 22).build());
        addRenderableWidget(Button.builder(Component.translatable("incoming.remote.clear"), b -> {
            PacketDistributor.sendToServer(new C2S.Clear());
            onClose();
        }).bounds(x0 + 136, by, 70, 22).tooltip(Tooltip.create(Component.translatable("incoming.remote.clear.tooltip"))).build());
        addRenderableWidget(Button.builder(Component.translatable("incoming.remote.settings"), b -> openConfig())
                .bounds(x0 + 210, by, 106, 22).build());
    }

    /** Fixed launchers linked to held remote ({@link LauncherLinks}): remote assigns them missions. */
    int linked() {
        LocalPlayer p = Minecraft.getInstance().player;
        return hand != null && p != null ? LauncherLinks.of(p.getItemInHand(hand)).size() : 0;
    }

    /**
     * Map launch site for this remote's strike: selected by operator or host ({@link MapScreen#mayPickLaunchSite}),
     * if remote is not assigning linked launchers (which launch from their own sites) and weapon launches or approaches
     * from it (ICBM uses its own pad: an old distant selection cannot block launch). Empty means near player;
     * map draws path and computes its length from the same location.
     */
    Optional<Vec3> launchFrom() {
        if (linked() > 0 || !MapScreen.mayPickLaunchSite() || loadout.weapon().spec().launch() == WeaponSpec.Launch.ICBM) return Optional.empty();
        return MapTarget.launchPoint(Minecraft.getInstance().level);
    }

    /** Hostile players seen now or recently by friendlies ({@link MapPlayers#targets}): remote cannot strike others. */
    private void collectPlayers() {
        shownPlayers = MapPlayers.targets(Minecraft.getInstance().level);
        for (String n : shownPlayers) choices.add(new Choice(Component.literal(n), n, null));
    }

    /** In player mode, update list from server replies while screen remains open. */
    @Override
    public void tick() {
        if (loadout.mode() != TargetMode.PLAYER) return;
        if (ticks++ % PLAYERS_PERIOD == 0) MapPlayers.request();
        if (!MapPlayers.targets(Minecraft.getInstance().level).equals(shownPlayers)) rebuildWidgets();
    }

    private void collectAircraft() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        List<SubLevelAccess> near = SubLevels.near(mc.level, mc.player.position(), 600);
        near.sort(Comparator.comparingDouble(s -> SubLevels.center(s).distanceTo(mc.player.position())));
        for (SubLevelAccess s : near) {
            int d = (int) SubLevels.center(s).distanceTo(mc.player.position());
            choices.add(new Choice(Component.translatable("incoming.remote.aircraft_entry", SubLevels.describe(s), d), "", s.getUniqueId()));
        }
    }

    /** Conventional or nuclear warhead on cruise missile and B-2 (server checks permissions and carrier_nukes). */
    private Button warheadButton(Loadout.Nuke n, int x, int y, int w) {
        Component text = n.onCarrier()
                ? Component.translatable("incoming.remote.warhead.nuclear").withStyle(ChatFormatting.GOLD)
                : Component.translatable("incoming.remote.warhead.conventional");
        // Yield slider beside it changes remote without rebuilding screen: use state at click time, not n.
        return Button.builder(text, b -> set(loadout.withNuke(loadout.nuke().withOnCarrier(!loadout.nuke().onCarrier())))).bounds(x, y, w, 20)
                .tooltip(Tooltip.create(Component.translatable("incoming.remote.warhead.tooltip"))).build();
    }

    /** "15 kt (Hiroshima)"; no name for a yield outside presets. */
    private static Component yieldLabel(int kt) {
        for (Yield y : Yield.values()) {
            if ((int) y.kt() == kt) return Component.translatable("incoming.remote.yield", kt, Component.translatable(y.key()));
        }
        return Component.translatable("incoming.remote.yield", kt, Component.empty());
    }

    /** Selection yellow with underline, not bold: bold is wider and would expand the {@link #row}. */
    private static Component label(Component text, boolean selected) {
        return selected ? text.copy().withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE) : text.copy().withStyle(ChatFormatting.GRAY);
    }

    /** {@link #row} button and all its possible labels: width from longest, so row stays stable when switching. */
    private record Cell(AbstractWidget button, List<Component> texts) {
        Cell(AbstractWidget button) {
            this(button, List.of(button.getMessage()));
        }
    }

    /**
     * Full-window button row: label width with margins per button, plus equal shares of remaining space; otherwise
     * long labels ("Where I look", "Burst: air") would scroll in equal-width buttons. Still do not fit: equal widths.
     */
    private void row(List<Cell> cells, int x0) {
        int n = cells.size(), free = W - 8 - 4 * (n - 1);
        int[] need = new int[n];
        int sum = 0;
        for (int i = 0; i < n; i++) sum += need[i] = cells.get(i).texts().stream().mapToInt(font::width).max().orElse(0) + 8;
        int spare = free - sum, x = x0 + 4;
        for (int i = 0; i < n; i++) {
            // Divide remaining space cumulatively: row ends exactly at window edge.
            int w = spare >= 0 ? need[i] + spare * (i + 1) / n - spare * i / n : free * (i + 1) / n - free * i / n;
            AbstractWidget b = cells.get(i).button();
            b.setX(x);
            b.setWidth(w);
            addRenderableWidget(b);
            x += w + 4;
        }
    }

    private void set(Loadout l) {
        loadout = l;
        save();
        rebuildWidgets();
    }

    /** Slider changes only its own value: save without rebuilding screen (focus stays on slider). */
    private void commit(Loadout l) {
        loadout = l;
        save();
    }

    private void save() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (hand != null && p != null) {
            ItemStack stack = p.getItemInHand(hand);
            if (stack.getItem() instanceof DesignatorItem) {
                stack.set(ModDataComponents.LOADOUT.get(), loadout);
                PacketDistributor.sendToServer(new C2S.SetLoadout(hand, loadout));
            }
        } else {
            detached = loadout;
        }
    }

    /** Remote settings used for launch. */
    Loadout loadout() {
        return loadout;
    }

    private void openMap() {
        Minecraft.getInstance().setScreen(new MapScreen(this));
    }

    /** Map site selected: remote target is On the map (site itself remembered by {@link MapTarget}). */
    void aimAtPlace() {
        if (loadout.mode() == TargetMode.MAP) return;
        loadout = loadout.withMode(TargetMode.MAP);
        save();
    }

    /** Map player selected: remote targets that player (server strike rules: {@code ServerActions.sighted}). */
    void aimAtPlayer(String name) {
        loadout = loadout.withMode(TargetMode.PLAYER).withPlayer(name);
        save();
    }

    /**
     * Fire at remote target; from targeting map, at its selected site or player ({@link #aimAtPlace},
     * {@link #aimAtPlayer}), via {@code via} waypoints if weapon uses them and from launch site {@code from}
     * ({@link #launchFrom}). Map also opens by key without showing remote, so use {@code Minecraft.getInstance()}
     * here rather than the screen field.
     */
    void fire(Waypoints via, Optional<Vec3> from) {
        if (loadout.mode() == TargetMode.AIRCRAFT && aircraft == null) return;
        if (loadout.mode() == TargetMode.PLAYER && loadout.player().isEmpty()) return;
        if (!loadout.weapon().spec().route().waypoints()) via = Waypoints.NONE;
        Optional<C2S.AimHint> aim;
        if (loadout.mode() == TargetMode.MAP) {
            Optional<MapTarget.Place> place = MapTarget.get(Minecraft.getInstance().level);
            LocalPlayer p = Minecraft.getInstance().player;
            // Route beyond weapon range (from launch site, otherwise player): no launch; map shows the problem.
            if (place.isEmpty() || p == null
                    || !via.isEmpty() && !via.within(loadout.weapon(), from.orElse(p.position()), new Vec3(place.get().x(), 0, place.get().z()))) {
                openMap();
                return;
            }
            // Map ground height there is server's estimate until its target chunk is ready.
            aim = place.map(t -> C2S.AimHint.ground(t.x(), t.z(), TerrainTiles.height(Mth.floor(t.x()), Mth.floor(t.z()))));
        } else {
            aim = nukePoint();
        }
        C2S.Fire packet = new C2S.Fire(loadout, aim, Optional.ofNullable(aircraft), via, from);
        Minecraft.getInstance().setScreen(null);
        if (loadout.nuclear()) {
            NukeArming.toggle(() -> PacketDistributor.sendToServer(packet));
        } else {
            PacketDistributor.sendToServer(packet);
        }
    }

    /**
     * ICBM strikes the point selected at click time: player may look away during 3 s arming. Fix the point immediately
     * for where I look and around me modes; player and craft are targets whose positions the server resolves.
     */
    private Optional<C2S.AimHint> nukePoint() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (!loadout.weapon().spec().warhead().always() || p == null || Minecraft.getInstance().level == null) return Optional.empty();
        return switch (loadout.mode()) {
            case LOOK -> {
                var pick = TargetPicker.pick(Minecraft.getInstance().level, p, p.getEyePosition(), p.getLookAngle(), Designator.RANGE);
                yield pick == null ? Optional.empty() : Optional.of(new C2S.AimHint(C2S.AimHint.POINT, pick.point(), 0, Vec3.ZERO));
            }
            case AROUND_ME -> Optional.of(new C2S.AimHint(C2S.AimHint.POINT, p.position(), 0, Vec3.ZERO));
            default -> Optional.empty();
        };
    }

    private void openConfig() {
        ModList.get().getModContainerById(Incoming.MOD_ID).ifPresent(c -> minecraft.setScreen(new ConfigurationScreen(c, this)));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int y0 = (height - h) / 2;
        g.drawCenteredString(font, Component.translatable("incoming.remote.title").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), width / 2, y0 + 7, 0xFFFFFFFF);
        lines(g, loadout.weapon().description().copy().withStyle(ChatFormatting.GRAY), y0 + 44, false);
        Component hint = switch (loadout.mode()) {
            case LOOK -> Component.translatable("incoming.remote.hint.look");
            case AROUND_ME -> Component.translatable("incoming.remote.hint.around_me");
            case PLAYER -> choices.isEmpty() ? Component.translatable("incoming.remote.hint.no_players") : movingHint("player");
            case AIRCRAFT -> choices.isEmpty() ? Component.translatable("incoming.remote.hint.no_aircraft") : movingHint("aircraft");
            case MAP -> {
                int points = loadout.weapon().spec().route().waypoints() ? MapTarget.route(minecraft.level).size() : 0;
                Component place = MapTarget.get(minecraft.level)
                        .<Component>map(p -> points > 0 ? Component.translatable("incoming.remote.hint.map_route", Mth.floor(p.x()), Mth.floor(p.z()), points)
                                : Component.translatable("incoming.remote.hint.map", Mth.floor(p.x()), Mth.floor(p.z())))
                        .orElse(Component.translatable("incoming.remote.hint.no_map"));
                // Map launch site (the launch will use it).
                yield launchFrom().<Component>map(f -> place.copy().append(Component.translatable("incoming.remote.hint.launch", Mth.floor(f.x), Mth.floor(f.z))))
                        .orElse(place);
            }
        };
        // Hint above launch buttons: last line at bottom, extra lines grow upward.
        lines(g, hint.copy().withStyle(ChatFormatting.DARK_GRAY), y0 + h - 28, true);
    }

    /** Text centred in window, wrapped to width: downward from {@code y}, or, if {@code up}, upward ending at {@code y}. */
    private void lines(GuiGraphics g, Component text, int y, boolean up) {
        List<FormattedCharSequence> split = font.split(text, W - 8);
        int top = up ? y - split.size() * LINE : y;
        for (int i = 0; i < split.size(); i++) {
            g.drawCenteredString(font, split.get(i), width / 2, top + i * LINE, 0xFFFFFFFF);
        }
    }

    /** Moving-target hint ({@code player}, {@code aircraft}): whether selected weapon tracks it ({@link WeaponSpec.Tracking}). */
    private Component movingHint(String what) {
        return loadout.weapon().spec().tracking() == WeaponSpec.Tracking.CAMERA
                ? Component.translatable("incoming.remote.hint." + what + ".camera", Component.keybind(Incoming.CAMERA_KEY))
                : Component.translatable("incoming.remote.hint." + what + ".point");
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x0 = (width - W) / 2, y0 = (height - h) / 2;
        g.fill(x0, y0, x0 + W, y0 + h, 0xE0101014);
        g.renderOutline(x0, y0, W, h, 0xFF8A1A1A);
        g.fill(x0 + 1, y0 + 18, x0 + W - 1, y0 + 19, 0xFF8A1A1A);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Integer slider with label "Count: 5". Save value to remote when released by mouse or moved by arrow keys;
     * while dragging, update label only.
     */
    private static final class IntSlider extends AbstractSliderButton {
        private final IntFunction<Component> label;
        private final int min, max;
        private final IntConsumer onCommit;
        private int committed;

        IntSlider(int x, int y, int w, int h, String key, int min, int max, int value, IntConsumer onCommit) {
            this(x, y, w, h, v -> Component.translatable(key, v), min, max, value, onCommit);
        }

        IntSlider(int x, int y, int w, int h, IntFunction<Component> label, int min, int max, int value, IntConsumer onCommit) {
            super(x, y, w, h, Component.empty(), (double) (Mth.clamp(value, min, max) - min) / (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.onCommit = onCommit;
            this.committed = intValue();
            updateMessage();
        }

        int intValue() {
            return min + (int) Math.round(value * (max - min));
        }

        @Override
        protected void updateMessage() {
            setMessage(label.apply(intValue()));
        }

        @Override
        protected void applyValue() {}

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            commit();
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
            if (handled) commit();
            return handled;
        }

        private void commit() {
            int v = intValue();
            if (v == committed) return;
            committed = v;
            onCommit.accept(v);
        }
    }
}
