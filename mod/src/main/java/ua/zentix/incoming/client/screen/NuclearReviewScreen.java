package ua.zentix.incoming.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import ua.zentix.incoming.client.hud.HudDraw;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.model.ImpactPreview;
import ua.zentix.incoming.strike.NuclearReview;
import ua.zentix.incoming.util.Wind;

import java.util.Locale;

/** Single final review, with continuous mouse or focused keyboard hold. Closing never launches. */
public final class NuclearReviewScreen extends Screen {
    private final S2C.NuclearReview review;
    private final ImpactPreview preview;
    private S2C.NuclearPreparation preparation;
    private net.minecraft.world.phys.Vec3 target;
    private int ticks;
    private boolean requested;
    private Button hold;
    private int held;
    private int scroll;
    private boolean holding, sent, inputReady;
    private int input; // 0 mouse, otherwise GLFW key

    public NuclearReviewScreen(S2C.NuclearReview review) {
        super(Component.translatable("incoming.nuke.review.title"));
        this.review = review;
        target = review.target();
        var l = review.order().loadout();
        preview = ImpactPreview.of(l.nuke().yieldKt(), l.weapon().spec().warhead().chooseBurst() && l.nuke().airBurst(), review.visibility(), review.scale());
    }

    @Override protected void init() {
        holding = false;
        held = 0;
        inputReady = false;
        int w = Math.min(300, width - 24);
        hold = addRenderableWidget(Button.builder(Component.translatable("incoming.nuke.review.hold"), b -> prepare())
                .bounds((width - w) / 2, height - 54, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds((width - w) / 2, height - 28, w, 20).build());
        setInitialFocus(hold);
    }

    private void action(int action) {
        PacketDistributor.sendToServer(new C2S.NuclearAction(review.id(), action));
    }

    private void prepare() {
        if (requested || sent) return;
        requested = true;
        inputReady = false;
        action(C2S.NuclearAction.PREPARE);
    }

    public void update(S2C.NuclearPreparation p) {
        if (!review.id().equals(p.id())) return;
        boolean wasReady = ready();
        requested = true;
        preparation = p;
        p.target().ifPresent(at -> target = at);
        if (wasReady != ready()) {
            release();
            inputReady = false; // readiness never turns a held preparation button into a launch
        }
    }

    private boolean ready() { return preparation != null && preparation.stage() == S2C.NuclearPreparation.READY; }

    private void begin(int key) {
        if (!ready() || !inputReady || holding || sent) return;
        holding = true;
        input = key;
        held = 0;
        action(C2S.NuclearAction.BEGIN);
    }

    private void release() {
        if (holding && !sent) action(C2S.NuclearAction.RELEASE);
        holding = false;
        held = 0;
    }

    @Override public void tick() {
        if (++ticks % 20 == 0) action(C2S.NuclearAction.POLL);
        long window = minecraft.getWindow().getWindow();
        boolean mouse = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean enter = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ENTER) == GLFW.GLFW_PRESS;
        boolean space = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS;
        if (!mouse && !enter && !space) inputReady = true;
        if (!holding) return;
        boolean down = input == 0 ? mouse : GLFW.glfwGetKey(window, input) == GLFW.GLFW_PRESS;
        if (!minecraft.isWindowActive() || !down || input == 0 && !hold.isHovered() || input != 0 && !hold.isFocused()) {
            release();
            return;
        }
        if (++held >= NuclearReview.HOLD_TICKS + 2 && (held - NuclearReview.HOLD_TICKS - 2) % 10 == 0)
            action(C2S.NuclearAction.COMMIT);
    }

    /** Only the server can finish a reviewed launch; a lagging server never loses the confirmation silently. */
    public void complete(java.util.UUID id) {
        if (!review.id().equals(id)) return;
        abort();
    }

    /** Server-wide abort also closes a held review when its individual completion packet has already expired. */
    public void abort() {
        sent = true;
        holding = false;
        held = 0;
        minecraft.setScreen(null);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && hold.isMouseOver(x, y)) { setFocused(hold); if (!requested) prepare(); else begin(0); return true; }
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        if (button == 0) release();
        return super.mouseReleased(x, y, button);
    }

    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        scroll = Math.max(0, Math.min(160, scroll - (int) (sy * 18)));
        return true;
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_PAGE_DOWN || key == GLFW.GLFW_KEY_PAGE_UP) {
            scroll = Math.max(0, Math.min(160, scroll + (key == GLFW.GLFW_KEY_PAGE_DOWN ? 40 : -40)));
            release();
            return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_SPACE) && hold.isFocused()) { if (!requested) prepare(); else begin(key); return true; }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public boolean keyReleased(int key, int scan, int modifiers) {
        if (holding && input == key) release();
        return super.keyReleased(key, scan, modifiers);
    }

    @Override public void removed() {
        release();
        if (!sent) action(C2S.NuclearAction.DISCARD);
    }

    @Override public Component getNarrationMessage() {
        var l = review.order().loadout();
        Component text = title.copy().append(". ").append(l.weapon().displayName()).append(". " + ImpactPreview.yieldText(l.nuke().yieldKt()))
                .append(Component.translatable("incoming.nuke.review.target", target.toString()));
        for (var c : preview.contours()) text = text.copy().append(". ").append(Component.translatable("incoming.nuke.preview." + c.key(), ImpactPreview.distance(c.radius())));
        return text;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int x = Math.max(12, (width - 380) / 2), y = 18;
        g.drawString(font, title, x, y, 0xFFFF8070);
        g.enableScissor(0, 36, width, height - 94);
        y = 40 - scroll;
        var l = review.order().loadout();
        g.drawString(font, l.weapon().displayName().copy().append(" · " + ImpactPreview.yieldText(l.nuke().yieldKt())), x, y, 0xFFFFFFFF);
        y += 14;
        g.drawString(font, Component.translatable(!l.weapon().spec().warhead().chooseBurst() ? "incoming.nuke.preview.buried" : preview.air() ? "incoming.nuke.preview.air" : "incoming.nuke.preview.ground",
                ImpactPreview.distance(preview.height())), x, y, 0xFFFFFFFF);
        y += 14;
        g.drawString(font, Component.translatable("incoming.nuke.review.target", String.format(Locale.ROOT, "%.0f / %.0f / %.0f",
                target.x, target.y, target.z)), x, y, 0xFFFFFFFF);
        y += 14;
        double distance = minecraft.player == null ? 0 : minecraft.player.position().distanceTo(target);
        g.drawString(font, Component.translatable("incoming.nuke.review.distance", ImpactPreview.distance(distance)), x, y, 0xFFFFFF80);
        y += 16;
        for (var c : preview.contours()) {
            g.drawString(font, Component.translatable("incoming.nuke.preview." + c.key(), ImpactPreview.distance(c.radius())), x, y, c.color());
            y += 12;
        }
        y += 4;
        for (var line : font.split(Component.translatable("incoming.nuke.preview.reference", ImpactPreview.distance(preview.visibility())), width - 2 * x)) {
            g.drawString(font, line, x, y, 0xFFA0B0A0); y += 10;
        }
        // North-up arrow points downwind, matching the production Wind vector (not a fallout contour).
        if (minecraft.level != null) {
            Wind wind = Wind.at(minecraft.level.getGameTime());
            int ax = width - x - 24, ay = 64;
            HudDraw.haloLine(g, ax, ay, ax + (float) wind.x() * 18, ay + (float) wind.z() * 18, 2, 0xFF80E0FF);
            float tx = ax + (float) wind.x() * 18, ty = ay + (float) wind.z() * 18;
            for (int sign : new int[] {-1, 1}) HudDraw.haloLine(g, tx, ty,
                    tx - (float) wind.x() * 7 + sign * (float) wind.z() * 4,
                    ty - (float) wind.z() * 7 - sign * (float) wind.x() * 4, 2, 0xFF80E0FF);
            g.drawString(font, Component.translatable("incoming.nuke.preview.wind"), ax - 24, ay + 25, 0xFF80E0FF);
        }
        g.disableScissor();
        Component status = Component.translatable("incoming.nuke.prepare.inspect");
        if (preparation != null) {
            String key = switch (preparation.stage()) {
                case S2C.NuclearPreparation.RESOLVING -> "resolving";
                case S2C.NuclearPreparation.PLANNING, S2C.NuclearPreparation.STORING -> "progress";
                case S2C.NuclearPreparation.READY -> "ready";
                case S2C.NuclearPreparation.PAUSED -> "paused";
                default -> "failed";
            };
            status = Component.translatable("incoming.nuke.prepare." + key, preparation.ready(), preparation.total());
            if (preparation.stage() == S2C.NuclearPreparation.FAILED || preparation.stage() == S2C.NuclearPreparation.ABSENT) {
                String reason = switch (preparation.reason()) {
                    case 3 -> "io";
                    case 4 -> "incompatible";
                    case 5 -> "expired";
                    case 6 -> "limit";
                    default -> null;
                };
                if (reason != null) status = Component.translatable("incoming.nuke.prepare.reason." + reason);
            }
        } else if (requested) status = Component.translatable("incoming.nuke.prepare.resolving");
        g.drawCenteredString(font, status, width / 2, height - 86, ready() ? 0xFF90E090 : 0xFFFFFFA0);
        if (preparation != null && preparation.total() > 0) {
            int w = Math.min(300, width - 24), left = (width - w) / 2;
            g.fill(left, height - 72, left + w, height - 69, 0xFF404040);
            g.fill(left, height - 72, left + (int) (w * Math.min(1, (double) preparation.ready() / preparation.total())), height - 69, 0xFF90B0D0);
        }
        hold.active = !requested || ready();
        hold.setMessage(Component.translatable(ready() ? "incoming.nuke.review.hold" : "incoming.nuke.prepare.button"));
        if (holding) g.fill(hold.getX(), hold.getY() + 18, hold.getX() + hold.getWidth() * Math.min(held, 60) / 60, hold.getY() + 20, 0xFFFF6050);
    }
}
