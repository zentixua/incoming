package ua.zentix.incoming.strike;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.api.StrikeOrderEvent;
import ua.zentix.incoming.api.StrikeOrderEvent.Actor;
import ua.zentix.incoming.api.StrikeOrderEvent.Payment;
import ua.zentix.incoming.target.Target;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * One strike order through admission ({@link Orders#admit}): who, what, where, how it is paid. Immutable except its
 * answer, which {@link Orders} records so each request gets exactly one.
 */
public final class Order {
    private final UUID id = UUID.randomUUID();
    private final ServerLevel level;
    private final Actor actor;
    @Nullable
    private final UUID actorId;
    private final String actorName;
    @Nullable
    private final UUID owner;
    @Nullable
    private final BlockPos launcher;
    private final WeaponType weapon;
    private final int count, spread;
    private final Vec3 target;
    @Nullable
    private final UUID targetEntity;
    private final Loadout.Nuke nuke;
    private final boolean nuclear;
    private final Payment payment;
    @Nullable
    private final String tag;
    /** Where a refusal is told: the ordering player's action bar, a command source, a launcher's status. */
    @Nullable
    private final Consumer<Component> feedback;
    @Nullable
    private StrikeOrderEvent.Reason rejected;
    private int accepted = -1;

    private Order(ServerLevel level, Actor actor, @Nullable UUID actorId, String actorName, @Nullable UUID owner, @Nullable BlockPos launcher,
                  Loadout l, ServerActions.Aim aim, Payment payment, @Nullable String tag, @Nullable Consumer<Component> feedback) {
        this.level = level;
        this.actor = actor;
        this.actorId = actorId;
        this.actorName = actorName;
        this.owner = owner;
        this.launcher = launcher;
        this.weapon = l.weapon();
        this.count = l.count();
        this.spread = l.spread();
        this.target = aim.point();
        this.targetEntity = aim.target().subject() instanceof Target.OfEntity e ? e.uuid() : null;
        this.nuke = l.nuke();
        this.nuclear = l.nuclear();
        this.payment = payment;
        this.tag = tag;
        this.feedback = feedback;
    }

    /** Player's order (designator or their command); {@code l} already clamped. */
    public static Order player(ServerPlayer player, Loadout l, ServerActions.Aim aim, Payment payment) {
        return player(player.serverLevel(), player, l, aim, payment);
    }

    /** Player's order carried out in {@code level} ({@code execute in} another dimension). */
    public static Order player(ServerLevel level, ServerPlayer player, Loadout l, ServerActions.Aim aim, Payment payment) {
        return new Order(level, Actor.PLAYER, player.getUUID(), player.getGameProfile().getName(), player.getUUID(), null, l, aim,
                payment, null, c -> ServerActions.refuse(player, c));
    }

    /** Console, command block, function, game master: unowned, unpaid. */
    public static Order server(ServerLevel level, String who, Loadout l, ServerActions.Aim aim, @Nullable Consumer<Component> feedback) {
        return new Order(level, Actor.SERVER, null, who, null, null, l, aim, Payment.WAIVED, null, feedback);
    }

    /** Fixed launcher at {@code pos} firing its mission for {@code owner} (null: unowned), paid from its magazine. */
    public static Order launcher(ServerLevel level, BlockPos pos, @Nullable UUID owner, Loadout l, ServerActions.Aim aim, @Nullable Consumer<Component> feedback) {
        return new Order(level, Actor.LAUNCHER, null, "launcher " + pos.toShortString(), owner, pos.immutable(), l, aim, Payment.PER_RELEASE, null, feedback);
    }

    /** Same order (not yet requested) carrying group name {@code tag} on all its rounds. */
    public Order tagged(@Nullable String tag) {
        return java.util.Objects.equals(tag, this.tag) ? this : new Order(this, tag);
    }

    private Order(Order o, @Nullable String tag) {
        this.level = o.level;
        this.actor = o.actor;
        this.actorId = o.actorId;
        this.actorName = o.actorName;
        this.owner = o.owner;
        this.launcher = o.launcher;
        this.weapon = o.weapon;
        this.count = o.count;
        this.spread = o.spread;
        this.target = o.target;
        this.targetEntity = o.targetEntity;
        this.nuke = o.nuke;
        this.nuclear = o.nuclear;
        this.payment = o.payment;
        this.tag = tag;
        this.feedback = o.feedback;
    }

    public UUID id() {
        return id;
    }

    public ServerLevel level() {
        return level;
    }

    public ResourceKey<Level> dimension() {
        return level.dimension();
    }

    public Actor actor() {
        return actor;
    }

    @Nullable
    public UUID actorId() {
        return actorId;
    }

    public String actorName() {
        return actorName;
    }

    @Nullable
    public UUID owner() {
        return owner;
    }

    @Nullable
    public BlockPos launcher() {
        return launcher;
    }

    public WeaponType weapon() {
        return weapon;
    }

    public int count() {
        return count;
    }

    public int spread() {
        return spread;
    }

    public Vec3 target() {
        return target;
    }

    @Nullable
    public UUID targetEntity() {
        return targetEntity;
    }

    public Loadout.Nuke nuke() {
        return nuke;
    }

    public boolean nuclear() {
        return nuclear;
    }

    public Payment payment() {
        return payment;
    }

    @Nullable
    public String tag() {
        return tag;
    }

    @Nullable
    Consumer<Component> feedback() {
        return feedback;
    }

    /** Round {@code index} (0-based) of this order. */
    public RoundRef round(int index) {
        return RoundRef.of(id, index, tag);
    }

    /** Answered: rejected or accepted. */
    boolean answered() {
        return rejected != null || accepted >= 0;
    }

    /** Recorded and paid: post {@link StrikeOrderEvent.Accepted} for {@code rounds} rounds. Once. */
    public void accept(int rounds) {
        if (answered()) throw new IllegalStateException("Order " + id + " already answered");
        accepted = rounds;
        Orders.publish(new StrikeOrderEvent.Accepted(this, rounds));
    }

    /** Not admitted: post {@link StrikeOrderEvent.Rejected}. Once; nothing was kept from the payer. */
    public void reject(StrikeOrderEvent.Reason reason) {
        if (answered()) throw new IllegalStateException("Order " + id + " already answered");
        rejected = reason;
        Orders.publish(new StrikeOrderEvent.Rejected(this, reason));
    }
}
