package ua.zentix.incoming.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.aim.Designator;
import ua.zentix.incoming.strike.NuclearKeys;

/**
 * Keys (Controls → Incoming). Launch defaults to LMB, but only while looking through the remote's binoculars:
 * vanilla attack is disabled then, so there is no conflict. Default keys are free in the host's pack (All of
 * Create Aeronautics, 217 mods): V already has four actions there (Supplementaries quiver, Backpacked backpacks,
 * Create springs), and NeoForge fires all of them at once, which would open the backpack screen.
 */
public final class Keys {
    public static final String CATEGORY = "key.categories.incoming";

    private static final IKeyConflictContext SCOPING = new IKeyConflictContext() {
        @Override
        public boolean isActive() {
            return Designator.isScoping();
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return other == this;
        }
    };

    public static final KeyMapping FIRE = new KeyMapping("key.incoming.fire", SCOPING, InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_LEFT, CATEGORY);
    /** Projectile camera: nearest by time, next, back to self. */
    public static final KeyMapping CAMERA = new KeyMapping(Incoming.CAMERA_KEY, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, CATEGORY);
    public static final KeyMapping MENU = new KeyMapping("key.incoming.menu", KeyConflictContext.IN_GAME, InputConstants.UNKNOWN, CATEGORY);
    /** Targeting map (the same as On the map in the remote); defaults to comma (Xaero uses M in the pack). */
    public static final KeyMapping MAP = new KeyMapping("key.incoming.map", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_COMMA, CATEGORY);
    /**
     * Second key: confirm a nearby player's nuclear launch ({@code strike.NuclearKeys}); defaults to period, beside
     * the map key (comma), free in the host's pack (Iris uses K, O and R).
     */
    public static final KeyMapping SECOND_KEY = new KeyMapping(NuclearKeys.KEY, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PERIOD,
            CATEGORY);

    private Keys() {}
}
