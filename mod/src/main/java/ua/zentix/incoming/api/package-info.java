/**
 * Incoming's events for other mods, datapack scripts (KubeJS) and game masters. All are posted on
 * {@code NeoForge.EVENT_BUS} from the server thread; getters return immutable values (primitives, UUIDs, strings, enums,
 * {@code Vec3}, {@code BlockPos}, the dimension key), never live game objects. {@code Request} is posted while the order
 * is made, so a listener can cancel it; every other event is posted at the end of the tick in which it happened, in the
 * order it happened: a listener never runs inside Incoming's own work, and nothing it does can reorder another
 * listener's view.
 * <p>
 * Order lifecycle, the same for the designator, commands, the fixed launcher, nuclear orders waiting for a second key or
 * a warning, and {@code nuke now}:
 * <ol>
 * <li>{@link ua.zentix.incoming.api.StrikeOrderEvent.Request}, cancellable, validation only;</li>
 * <li>exactly one {@link ua.zentix.incoming.api.StrikeOrderEvent.Rejected} or
 * {@link ua.zentix.incoming.api.StrikeOrderEvent.Accepted};</li>
 * <li>per admitted round, {@link ua.zentix.incoming.api.StrikeRoundEvent.Released} when it leaves (if it does) and exactly
 * one {@link ua.zentix.incoming.api.StrikeRoundEvent.Ended}.</li>
 * </ol>
 * A pack that charges its own cost or cooldown checks in {@code Request}, debits in {@code Accepted} and settles in
 * {@code Ended}; Incoming rolls back no foreign debit. Orders saved before these events existed post none.
 */
package ua.zentix.incoming.api;
