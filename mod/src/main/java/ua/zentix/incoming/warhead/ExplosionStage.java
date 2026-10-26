package ua.zentix.incoming.warhead;

/**
 * Warhead explosion stage ({@link StagedExplosion}) for timing: where work-unit time goes. Host “rays and damage”
 * unit took up to 150 ms; splitting it requires knowing which part costs that.
 */
public enum ExplosionStage {
    /** Before rays: {@code new Explosion}, {@code ExplosionEvent.Start} (mod handlers). */
    START("Start"),
    /** Entire vanilla {@code Explosion.explode} (Sable craft nearby): rays, entities, {@code Detonate}, damage. */
    VANILLA("vanilla explosion"),
    /** {@code gameEvent(EXPLODE)}: vibration listeners (sculk, mods). */
    GAME_EVENT("game event"),
    /** Block rays. */
    RAYS("rays"),
    /** Collect entities in reach cube. */
    ENTITIES("entity search"),
    /** {@code ExplosionEvent.Detonate}: mod handlers (ours too: blackout, test recording). */
    DETONATE("Detonate"),
    /** Damage/knockback: {@code getSeenPercent} entity rays, {@code hurt}, death, loot, handlers. */
    DAMAGE("damage"),
    /** Separate ray-selected positions into craft plots/world, read world blocks (discard air without fire). */
    SPLIT("split"),
    /** Remove craft blocks (Sable plots) together after damage, checking craft/block identity. */
    CRAFTS("aircraft blocks"),
    /** World block snapshot for portions, sorted from center. */
    SNAPSHOT("snapshot");

    public final String label;

    ExplosionStage(String label) {
        this.label = label;
    }
}
