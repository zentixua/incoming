package ua.zentix.incoming.compat.computercraft;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.info.Automation;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;

import java.util.Map;

/**
 * Fixed launcher as a computer peripheral ({@code incoming_launcher}): status, stock, and firing its existing mission
 * through the redstone signal's own entry ({@link Automation#fire}). Every method runs on the main server thread. No
 * method sets or clears a mission: that stays an operator command or a designator action.
 */
final class LauncherPeripheral implements IPeripheral {
    private final FixedLauncherBlockEntity be;

    LauncherPeripheral(FixedLauncherBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return Automation.LAUNCHER_TYPE;
    }

    @Override
    public Object getTarget() {
        return be;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof LauncherPeripheral o && o.be == be;
    }

    /** {@code { weapon | nil, ready, busy, reason, message }} ({@link Automation#status}): the mission's count and target are not reported. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStatus() throws LuaException {
        return Automation.status(level(), be);
    }

    /** Rounds held per weapon: {@code { drone = n, missile = n, rocket = n, loiter = n }}. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStock() throws LuaException {
        level();
        return Automation.stock(be);
    }

    /** {@code true}, or {@code false, reason, message}: exactly what a redstone signal would get. */
    @LuaFunction(mainThread = true)
    public final MethodResult fire() throws LuaException {
        return MethodResult.of(Automation.fire(level(), be));
    }

    private ServerLevel level() throws LuaException {
        if (be.isRemoved() || !(be.getLevel() instanceof ServerLevel level)) throw new LuaException("the launcher is gone");
        return level;
    }
}
