package ua.zentix.incoming.item;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ua.zentix.incoming.net.ClientHooks;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Real item-use implementations with isolated players, worlds and client hooks; no client or registry event loop. */
class DesignatorItemTest {
    private DesignatorItem item;
    private ClientHooks previousHooks;
    private ClientHooks hooks;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void isolate() {
        // Use methods do not depend on registration. Run their real bodies without constructing a registered item.
        item = mock(DesignatorItem.class, CALLS_REAL_METHODS);
        previousHooks = ClientHooks.get();
        hooks = mock(ClientHooks.class);
        ClientHooks.set(hooks);
    }

    @AfterEach
    void restoreHooks() {
        ClientHooks.set(previousHooks);
    }

    private static Level level(boolean client) throws ReflectiveOperationException {
        Level level = mock(Level.class);
        // The API reads this public final flag directly, so method stubbing cannot select the client-side branch.
        var side = Level.class.getField("isClientSide");
        side.setAccessible(true);
        side.setBoolean(level, client);
        return level;
    }

    @ParameterizedTest
    @CsvSource({"false, MAIN_HAND", "false, OFF_HAND", "true, MAIN_HAND", "true, OFF_HAND"})
    void normalUseStartsTheScopeAndConsumesTheSelectedHand(boolean client, InteractionHand hand) throws ReflectiveOperationException {
        Level level = level(client);
        Player player = mock(Player.class);
        ItemStack stack = mock(ItemStack.class);
        when(player.getItemInHand(hand)).thenReturn(stack);
        var result = item.use(level, player, hand);
        assertEquals(InteractionResult.CONSUME, result.getResult());
        assertSame(stack, result.getObject());
        verify(player).playSound(SoundEvents.SPYGLASS_USE, 1.0f, 1.0f);
        verify(player).startUsingItem(hand);
        verifyNoInteractions(hooks, stack, level);
    }

    @ParameterizedTest
    @CsvSource({"false, MAIN_HAND", "false, OFF_HAND", "true, MAIN_HAND", "true, OFF_HAND"})
    void crouchingOpensTheRemoteOnlyOnTheClientWithoutStartingTheScope(boolean client, InteractionHand hand) throws ReflectiveOperationException {
        Level level = level(client);
        Player player = mock(Player.class);
        ItemStack stack = mock(ItemStack.class);
        when(player.getItemInHand(hand)).thenReturn(stack);
        when(player.isShiftKeyDown()).thenReturn(true);
        var result = item.use(level, player, hand);
        assertEquals(InteractionResult.SUCCESS, result.getResult());
        assertSame(stack, result.getObject());
        verify(player, never()).startUsingItem(hand);
        verify(player, never()).playSound(SoundEvents.SPYGLASS_USE, 1.0f, 1.0f);
        if (client) verify(hooks).openRemote();
        verifyNoMoreInteractions(hooks);
        verifyNoInteractions(stack, level);
    }

    @Test
    void useAnimationDurationAndReleaseMatchTheSpyglassContract() {
        ItemStack stack = mock(ItemStack.class);
        LivingEntity user = mock(LivingEntity.class);
        Level level = mock(Level.class);
        assertEquals(72000, item.getUseDuration(stack, user));
        assertEquals(UseAnim.SPYGLASS, item.getUseAnimation(stack));
        item.releaseUsing(stack, level, user, 100);
        verify(user).playSound(SoundEvents.SPYGLASS_STOP_USING, 1.0f, 1.0f);
        verifyNoMoreInteractions(user);
        verifyNoInteractions(stack, level, hooks);
    }
}
