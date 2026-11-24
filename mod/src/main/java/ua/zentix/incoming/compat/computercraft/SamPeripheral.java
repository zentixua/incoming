package ua.zentix.incoming.compat.computercraft;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.NotAttachedException;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.info.Automation;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SAM as a computer peripheral ({@code incoming_sam}): stock and the latest sweep's tracking state, and the event
 * {@code incoming_alert} (attachment name, hostile count, nearest hostile distance) to the computers attached to this
 * SAM on every sweep that tracks a hostile target; no other computer receives it. The peripheral watches its SAM
 * ({@link SamBlockEntity#watch}) while at least one computer is attached. CC:Tweaked attaches and detaches from either
 * the server or a computer thread: both are serialized here, the computer set is concurrent.
 */
final class SamPeripheral implements IPeripheral, SamBlockEntity.Watcher {
    private final SamBlockEntity be;
    private final Set<IComputerAccess> computers = ConcurrentHashMap.newKeySet();

    SamPeripheral(SamBlockEntity be) {
        this.be = be;
    }

    @Override
    public String getType() {
        return Automation.SAM_TYPE;
    }

    @Override
    public Object getTarget() {
        return be;
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof SamPeripheral o && o.be == be;
    }

    @Override
    public synchronized void attach(IComputerAccess computer) {
        computers.add(computer);
        be.watch(this);
    }

    @Override
    public synchronized void detach(IComputerAccess computer) {
        computers.remove(computer);
        if (computers.isEmpty()) be.unwatch(this);
    }

    /**
     * A sweep of this SAM sees a hostile track (server thread): every attached computer gets {@code incoming_alert}. A
     * computer shutting down detaches from its own thread, possibly between the set read and the call; its access then
     * throws {@link NotAttachedException} and is skipped: the sweep must never fail inside the SAM's tick. No lock is
     * held here (the computer's detach holds its own and calls ours: the reverse order would deadlock).
     */
    @Override
    public void tracking(int hostile, double nearest) {
        Object[] tail = Automation.alert(hostile, nearest);
        for (IComputerAccess c : computers) {
            try {
                c.queueEvent(Automation.ALERT_EVENT, c.getAttachmentName(), tail[0], tail[1]);
            } catch (NotAttachedException e) {
                computers.remove(c);
            }
        }
    }

    /** {@code { ready, rails, stored }}. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getStock() throws LuaException {
        present();
        return Automation.samStock(be);
    }

    /** {@code { tracking, hostile, tracks, covered }} of the latest sweep. */
    @LuaFunction(mainThread = true)
    public final Map<String, Object> getTracking() throws LuaException {
        present();
        return Automation.tracking(be);
    }

    /** The latest sweep sees a hostile target (the redstone alarm is on). */
    @LuaFunction(mainThread = true)
    public final boolean isTracking() throws LuaException {
        present();
        return be.hostile() > 0;
    }

    private void present() throws LuaException {
        if (be.isRemoved()) throw new LuaException("the SAM is gone");
    }
}
