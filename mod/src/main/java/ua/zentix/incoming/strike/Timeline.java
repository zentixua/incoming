package ua.zentix.incoming.strike;

import net.minecraft.server.level.ServerLevel;

/** Something lasting several server ticks (surface/underground explosion timeline). */
public interface Timeline {
    /** One tick. false means finished, remove. */
    boolean tick(ServerLevel level);

    /** Timeline removed, finished or failed: release resources (chunk tickets). */
    default void end(ServerLevel level) {}
}
