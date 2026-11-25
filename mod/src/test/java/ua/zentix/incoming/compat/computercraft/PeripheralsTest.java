package ua.zentix.incoming.compat.computercraft;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.info.Automation;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The CC:Tweaked adapters against CC:Tweaked's API (CC:Tweaked is not in the GameTest runtime; what the calls do is
 * {@code AutomationGameTests}): the Lua methods are exactly the listed ones, all on the main thread, none sets a mission;
 * {@code incoming_alert} goes to the computers attached to this SAM only, with its payload, and the SAM is watched while
 * at least one computer is attached.
 */
class PeripheralsTest {
    @org.junit.jupiter.api.BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        // as PackDocsTest: NeoForge's feature flags read the mod list while blocks initialize
        if (net.neoforged.fml.loading.LoadingModList.get() == null) {
            net.neoforged.fml.loading.LoadingModList.of(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of());
        }
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void launcherMethodsAreStatusStockFireOnly() {
        assertEquals(Set.of("getStatus", "getStock", "fire"), lua(LauncherPeripheral.class));
        assertEquals(Set.of("getStock", "getTracking", "isTracking"), lua(SamPeripheral.class));
    }

    @Test
    void peripheralTypesAndIdentity() {
        FixedLauncherBlockEntity a = mock(FixedLauncherBlockEntity.class), b = mock(FixedLauncherBlockEntity.class);
        LauncherPeripheral p = new LauncherPeripheral(a);
        assertEquals("incoming_launcher", p.getType());
        assertTrue(p.equals(new LauncherPeripheral(a)));
        assertFalse(p.equals(new LauncherPeripheral(b)));
        SamBlockEntity s = mock(SamBlockEntity.class);
        assertEquals("incoming_sam", new SamPeripheral(s).getType());
        assertTrue(new SamPeripheral(s).equals(new SamPeripheral(s)));
        assertFalse(new SamPeripheral(s).equals(p));
    }

    @Test
    void alertOnlyToAttachedComputers() {
        SamBlockEntity sam = mock(SamBlockEntity.class);
        SamPeripheral p = new SamPeripheral(sam);
        IComputerAccess left = computer("left"), modem = computer("incoming_sam_0"), gone = computer("right");
        p.attach(left);
        p.attach(modem);
        p.attach(gone);
        p.detach(gone);
        verify(sam, never()).unwatch(p);
        p.tracking(2, 412.6);
        verify(left).queueEvent(Automation.ALERT_EVENT, "left", 2, 413);
        verify(modem).queueEvent(Automation.ALERT_EVENT, "incoming_sam_0", 2, 413);
        verify(gone, never()).queueEvent(anyString(), any(Object[].class));
        p.detach(left);
        p.detach(modem);
        verify(sam).unwatch(p);
        verify(sam, times(3)).watch(p);
    }

    @Test
    void anotherSamsComputersHearNothing() {
        SamPeripheral tracking = new SamPeripheral(mock(SamBlockEntity.class)), quiet = new SamPeripheral(mock(SamBlockEntity.class));
        IComputerAccess mine = computer("top"), other = computer("top");
        tracking.attach(mine);
        quiet.attach(other);
        tracking.tracking(1, 90);
        verify(mine).queueEvent(Automation.ALERT_EVENT, "top", 1, 90);
        verify(other, never()).queueEvent(anyString(), any(Object[].class));
    }

    private static IComputerAccess computer(String name) {
        IComputerAccess c = mock(IComputerAccess.class);
        when(c.getAttachmentName()).thenReturn(name);
        return c;
    }

    /** Lua-callable methods of {@code type}; each must be public, final and main-thread. */
    private static Set<String> lua(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).filter(m -> m.isAnnotationPresent(LuaFunction.class)).peek(PeripheralsTest::checked)
                .map(Method::getName).collect(Collectors.toSet());
    }

    private static void checked(Method m) {
        assertTrue(Modifier.isPublic(m.getModifiers()) && Modifier.isFinal(m.getModifiers()), m + " is not public final");
        assertTrue(m.getAnnotation(LuaFunction.class).mainThread(), m + " does not run on the main thread");
        assertEquals(0, m.getParameterCount(), m + " takes arguments");
    }
}
