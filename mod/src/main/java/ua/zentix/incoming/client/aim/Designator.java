package ua.zentix.incoming.client.aim;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.nuclear.NukeArming;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.item.DesignatorItem;
import ua.zentix.incoming.launcher.LauncherLinks;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.registry.ModDataComponents;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.target.TargetPicker;

import java.util.Locale;
import java.util.Optional;

/**
 * Binocular designator: hold RMB with the remote for zoom, reticle, range and what's under the crosshair (block,
 * mob, player, Create craft, framed); LMB launches, wheel selects weapon. It flies where you look:
 * the client sends its target, the server finds and validates it on its side.
 */
public final class Designator {
    /** Client sight range; the server still applies its own limit (aim_range). */
    public static final double RANGE = 400;

    @Nullable
    private static TargetPicker.Pick pick;
    private static Object lastLocked;

    private Designator() {}

    @Nullable
    public static InteractionHand scopingHand() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !p.isUsingItem() || !(p.getUseItem().getItem() instanceof DesignatorItem)) return null;
        return p.getUsedItemHand();
    }

    public static boolean isScoping() {
        return scopingHand() != null;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !isScoping()) {
            reset();
            return;
        }
        pick = TargetPicker.pick(mc.level, p, p.getEyePosition(), p.getLookAngle(), RANGE);
        // Target acquisition: short cue when an entity or craft appears under the crosshair.
        Object locked = pick == null ? null : pick.entity() != null ? pick.entity() : pick.kind() == TargetPicker.Kind.AIRCRAFT ? pick.label().getString() : null;
        if (locked != null && !locked.equals(lastLocked)) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(ModSounds.DESIGNATOR_LOCK.get(), 1.0f, 0.6f));
        }
        lastLocked = locked;
    }

    /** Forget target: holds an entity of the current world and must not retain it after leaving. */
    public static void reset() {
        pick = null;
        lastLocked = null;
    }

    private static Loadout loadout() {
        InteractionHand hand = scopingHand();
        LocalPlayer p = Minecraft.getInstance().player;
        return hand == null || p == null ? Loadout.DEFAULT : DesignatorItem.loadout(p.getItemInHand(hand));
    }

    /** LMB in binoculars: launch at the crosshair (map route and launch site apply only to map and remote launches). */
    public static void fire() {
        TargetPicker.Pick pk = pick;
        if (pk == null || !isScoping()) return;
        C2S.AimHint hint;
        if (pk.entity() != null) {
            hint = new C2S.AimHint(C2S.AimHint.ENTITY, pk.point(), pk.entity().getId(), Vec3.ZERO);
        } else if (pk.target() instanceof Target.OfSubLevel s) {
            hint = new C2S.AimHint(C2S.AimHint.AIRCRAFT, pk.point(), 0, s.plotPos());
        } else {
            hint = new C2S.AimHint(C2S.AimHint.POINT, pk.point(), 0, Vec3.ZERO);
        }
        Loadout l = loadout().withMode(TargetMode.LOOK);
        C2S.Fire packet = new C2S.Fire(l, Optional.of(hint), Optional.empty(), Waypoints.NONE, Optional.empty());
        if (l.weapon().spec().warhead().always()) {
            NukeArming.toggle(() -> PacketDistributor.sendToServer(packet));
        } else {
            PacketDistributor.sendToServer(packet);
        }
    }

    /** Wheel in binoculars: next/previous weapon (saved in the remote). */
    public static void scroll(double delta) {
        InteractionHand hand = scopingHand();
        LocalPlayer p = Minecraft.getInstance().player;
        if (hand == null || p == null) return;
        ItemStack stack = p.getItemInHand(hand);
        Loadout l = DesignatorItem.loadout(stack);
        Loadout next = l.withWeapon(delta < 0 ? l.weapon().next() : l.weapon().previous());
        stack.set(ModDataComponents.LOADOUT.get(), next);
        PacketDistributor.sendToServer(new C2S.SetLoadout(hand, next));
        p.displayClientMessage(Component.translatable("incoming.weapon.selected", next.weapon().displayName()).withStyle(ChatFormatting.GOLD), true);
    }

    // ---------------------------------------------------------------- zoom and sensitivity

    public static void fov(ComputeFovModifierEvent e) {
        if (isScoping()) e.setNewFovModifier((float) (1.0 / IncomingConfig.CLIENT.zoom.get()));
    }

    /** Zoom slows the mouse by the same factor, as with a spyglass. */
    public static void turn(CalculatePlayerTurnEvent e) {
        if (!isScoping()) return;
        double s = e.getMouseSensitivity() * 0.6 + 0.2;
        double scaled = s / Math.cbrt(IncomingConfig.CLIENT.zoom.get());
        e.setMouseSensitivity(Math.max(0, (scaled - 0.2) / 0.6));
    }

    /** Hide the hand holding the remote while using binoculars. */
    public static void hideHand(RenderHandEvent e) {
        if (isScoping()) e.setCanceled(true);
    }

    // ---------------------------------------------------------------- target frame in the world

    public static void renderWorld(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || pick == null || !isScoping()) return;
        AABB box = null;
        if (pick.entity() != null) {
            box = pick.entity().getBoundingBox().inflate(0.25);
        } else if (pick.kind() == TargetPicker.Kind.AIRCRAFT) {
            SubLevelAccess sub = SubLevels.containing(Minecraft.getInstance().level, ((Target.OfSubLevel) pick.target()).plotPos());
            if (sub != null) box = sub.boundingBox().toMojang().inflate(0.5);
        }
        if (box == null) return;
        Vec3 cam = e.getCamera().getPosition();
        PoseStack pose = e.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, lines, box, 1.0f, 0.25f, 0.2f, 1.0f);
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }

    // ---------------------------------------------------------------- reticle

    public static void renderHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!isScoping() || mc.options.hideGui) return;
        int w = g.guiWidth(), h = g.guiHeight(), cx = w / 2, cy = h / 2;
        Font font = mc.font;
        int r = Math.min(w, h) / 2 - 10;

        // Darkened edges: eyepiece.
        g.fill(0, 0, w, cy - r, 0xB0000000);
        g.fill(0, cy + r, w, h, 0xB0000000);
        g.fill(0, cy - r, cx - r, cy + r, 0xB0000000);
        g.fill(cx + r, cy - r, w, cy + r, 0xB0000000);

        int red = pick != null && (pick.entity() != null || pick.kind() == TargetPicker.Kind.AIRCRAFT) ? 0xFFFF4030 : 0xFFE8E8E8;
        // Gapped crosshair and rangefinder ticks.
        g.fill(cx - 30, cy, cx - 4, cy + 1, red);
        g.fill(cx + 4, cy, cx + 30, cy + 1, red);
        g.fill(cx, cy - 30, cx + 1, cy - 4, red);
        g.fill(cx, cy + 4, cx + 1, cy + 30, red);
        for (int i = 1; i <= 4; i++) g.fill(cx - 3, cy + 6 * i + 4, cx + 4, cy + 6 * i + 5, 0x80E8E8E8);
        g.fill(cx, cy, cx + 1, cy + 1, red);

        Loadout l = loadout();
        LocalPlayer p = mc.player;
        String dist = pick == null || p == null ? "—" : String.format(Locale.ROOT, "%.0f", pick.point().distanceTo(p.getEyePosition()));
        Component what = pick == null ? Component.translatable("incoming.target_not_found") : pick.label();
        Component top = Component.translatable("incoming.hud.target", what, dist).withStyle(ChatFormatting.WHITE);
        g.drawString(font, top, cx - font.width(top) / 2, cy + 40, 0xFFFFFFFF);
        Component weapon = l.count() > 1 || l.spread() > 0
                ? Component.translatable("incoming.hud.loadout.salvo", l.weapon().displayName(), l.count(), l.spread())
                : Component.translatable("incoming.hud.loadout", l.weapon().displayName());
        g.drawString(font, weapon.copy().withStyle(ChatFormatting.GOLD), cx - font.width(weapon) / 2, cy + 52, 0xFFFFFFFF);
        InteractionHand hand = scopingHand();
        int linked = hand == null || p == null ? 0 : LauncherLinks.of(p.getItemInHand(hand)).size();
        if (linked > 0) {
            // LMB assigns missions to linked launchers rather than launching directly.
            Component assign = Component.translatable("incoming.hud.assign", linked).withStyle(ChatFormatting.YELLOW);
            g.drawString(font, assign, cx - font.width(assign) / 2, cy + 64, 0xFFFFFFFF);
        }
        Component hint = Component.translatable("incoming.hud.scope_hint").withStyle(ChatFormatting.GRAY);
        g.drawString(font, hint, cx - font.width(hint) / 2, cy - r + 6, 0xFFFFFFFF);
    }
}
