package ua.zentix.incoming.client.fx.particle;

/**
 * Effects particle pool slots ({@link FxPool}). Each particle belongs to a group; a full group rejects new particles:
 * important effects (blast clouds, flashes) do not share slots with long-lived details (ground dust, trails, fragment
 * tails), and long-lived first-impact clouds are not evicted by later ones. The pool counts particles denied slots
 * per group ({@link FxPool#refused}).
 */
public enum FxBudget {
    /** Blast clouds and fire: fireball cooling to smoke, residual burning — visible from behind structures too. */
    CLOUD(5120),
    /** Ground billows: blast dust roll, launcher exhaust and cloud, shock rings, collapse and shock-wave dust. */
    GROUND(3584),
    /** Projectile trail volume billows (trail itself is a path-point ribbon, using no slots). */
    TRAIL(5120),
    /** Smoky fragment and debris tails. */
    DEBRIS(2560),
    /**
     * Spray behind the nuclear front over water: crest droplets and fallback mist billows. Separate group: the front arc
     * near a camera over a lake spawns hundreds of spray particles per tick and would take nearby land ground-dust slots.
     */
    SPRAY(2048),
    /** Flashes. */
    FLASH(512),
    /** Sparks. */
    SPARK(15360),
    /** Ash, paper and small debris falling around the camera after a nuclear wave (like weather). */
    ASH(3072);

    final int limit;

    FxBudget(int limit) {
        this.limit = limit;
    }

    /** Maximum simultaneous particles per group. */
    public int limit() {
        return limit;
    }

    /** Total slots across all groups — pool size. */
    static int total() {
        int sum = 0;
        for (FxBudget b : values()) sum += b.limit;
        return sum;
    }
}
