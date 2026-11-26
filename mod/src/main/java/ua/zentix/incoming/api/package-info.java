/**
 * Incoming's events for other mods, datapack scripts (KubeJS) and game masters. All are posted on
 * {@code NeoForge.EVENT_BUS} from the server thread; getters return immutable values (primitives, UUIDs, strings, enums,
 * {@code Vec3}, {@code BlockPos}, the dimension key), never live game objects. {@code Request}, {@code Rejected} and
 * {@code Accepted} are posted while the order is made: a listener can cancel the request, and the next order already
 * sees the last one accepted. Round, detonation and grid events are posted at the end of the tick in which they
 * happened, in the order they happened, from one queue for the whole server: a listener never runs inside Incoming's own
 * work, and nothing it does can reorder another listener's view.
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
 * {@code Ended}; Incoming rolls back no foreign debit. Rounds saved before these events existed post no round events;
 * their detonations and outages are still reported, with no order or round id.
 * <p>
 * Sides: {@link ua.zentix.incoming.api.SideResolveEvent} asks a provider (a party mod, a script) for one player's side
 * key; it is posted while Incoming needs the answer, on the server thread, and the first answer wins.
 * <p>
 * Claim protection: before Incoming changes blocks outside a real charge's own explosion, it asks claim mods through
 * {@code ExplosionEvent.Detonate} with a {@link ua.zentix.incoming.api.ClaimQuery}, a question and not an explosion.
 */
package ua.zentix.incoming.api;
