package ua.zentix.incoming.strike;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.wrapper.PlayerInvWrapper;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.registry.ModDataComponents;
import ua.zentix.incoming.registry.ModItems;

import java.util.Collection;
import java.util.UUID;

/**
 * Inventory munitions. A player order under player rules ({@code rules} in {@link ServerActions#strike}:
 * designator for everyone, non-operator command) pays immediately in full, single launch or entire salvo: one
 * munition item per projectile (spec, {@link WeaponSpec#munition}; Grad pack pays rounds from pack), plus {@link
 * #warhead()} item for a carrier nuclear warhead. Shortage means no launch, with above-hotbar required/available
 * counts; never truncate order. Failed launches and unlaunched canceled-salvo rounds refund online owner (full
 * inventory: at feet; dead before respawn: death site); offline gets no refund (log line). Creative, operator
 * command, console, command block do not pay.
 */
public final class Munitions {
    private Munitions() {}

    /** Carrier nuclear warhead for cruise missile or B-2 bomb: one item type for all carriers. */
    public static Item warhead() {
        return ModItems.NUCLEAR_WARHEAD.get();
    }

    /**
     * Order bill.
     *
     * @param weapon weapon
     * @param shots projectile count (rounds, not packs)
     * @param warheads carrier nuclear warhead count
     */
    public record Bill(WeaponType weapon, int shots, int warheads) {
        /** Bill for {@code l}: munition per projectile, extra item for each carrier nuclear warhead. */
        public static Bill of(Loadout l) {
            boolean carrier = l.nuclear() && !l.weapon().spec().warhead().always();
            return new Bill(l.weapon(), l.count(), carrier ? l.count() : 0);
        }
    }

    /** Rounds per {@code item}: spec count for packs, one otherwise. */
    public static int perItem(Item item) {
        for (WeaponType w : WeaponType.values()) {
            WeaponSpec.Munition m = w.spec().munition();
            if (m.item().get() == item) return m.rounds();
        }
        return 1;
    }

    /** Rounds left in stack {@code s}'s item: component for opened pack, full count otherwise. */
    public static int left(ItemStack s, int perItem) {
        Integer n = s.get(ModDataComponents.ROUNDS.get());
        return n == null ? perItem : Math.clamp(n, 1, perItem);
    }

    /**
     * Opened pack with {@code left} of {@code perItem} rounds (full pack has no component, stacks with whole
     * packs).
     */
    public static ItemStack pack(Item item, int perItem, int left) {
        ItemStack s = new ItemStack(item);
        setLeft(s, perItem, left);
        return s;
    }

    private static void setLeft(ItemStack s, int perItem, int left) {
        if (left >= perItem) s.remove(ModDataComponents.ROUNDS.get());
        else s.set(ModDataComponents.ROUNDS.get(), left);
    }

    /**
     * Store paying for orders and receiving refunds: player inventory ({@link #of(ServerPlayer)}) or fixed
     * launcher inventory.
     */
    public interface Store {
        /** Storage slots. */
        IItemHandlerModifiable items();

        /** Insert a stack with no room in ordered slots (opened pack remainder, refund). */
        void put(ItemStack s);

        /** Items placed in slots survive (not for dead player without {@code keepInventory}). */
        default boolean keeps() {
            return true;
        }
    }

    /** Player inventory: insert, or at feet if full; dead player: drop at death site. */
    public static Store of(ServerPlayer player) {
        IItemHandlerModifiable items = new PlayerInvWrapper(player.getInventory());
        return new Store() {
            @Override
            public IItemHandlerModifiable items() {
                return items;
            }

            @Override
            public void put(ItemStack s) {
                if (keeps()) player.getInventory().placeItemBackInInventory(s);
                // Around the site like death loot; no ItemTossEvent, since this is not a player toss.
                else player.drop(s, true, false);
            }

            @Override
            public boolean keeps() {
                return Munitions.keeps(player);
            }
        };
    }

    /** Rounds of {@code item} in storage (sum across packs). */
    public static int held(IItemHandler items, Item item, int perItem) {
        long n = 0;
        for (int i = 0; i < items.getSlots(); i++) {
            ItemStack s = items.getStackInSlot(i);
            if (s.is(item)) n += (long) s.getCount() * left(s, perItem);
        }
        return (int) Math.min(n, Integer.MAX_VALUE);
    }

    /**
     * Shortage in {@code items} for {@code bill}: required/available line; null if sufficient.
     */
    @Nullable
    public static MutableComponent shortage(IItemHandler items, Bill bill) {
        WeaponSpec.Munition m = bill.weapon().spec().munition();
        MutableComponent rounds = shortage(items, m.item().get(), m.rounds(), bill.shots());
        return rounds != null ? rounds : shortage(items, warhead(), 1, bill.warheads());
    }

    @Nullable
    private static MutableComponent shortage(IItemHandler items, Item item, int perItem, int need) {
        if (need <= 0) return null;
        int have = held(items, item, perItem);
        if (have >= need) return null;
        return perItem > 1 ? Component.translatable("incoming.munitions.short.rounds", item.getDescription(), need, have, perItem)
                : Component.translatable("incoming.munitions.short", item.getDescription(), need, have);
    }

    /**
     * Pay bill from storage, all or nothing: missing munition or nuclear warhead deducts nothing.
     *
     * @return null if paid; otherwise shortage ({@link #shortage})
     */
    @Nullable
    public static MutableComponent pay(Store store, Bill bill) {
        MutableComponent missing = shortage(store.items(), bill);
        if (missing != null) return missing;
        WeaponSpec.Munition m = bill.weapon().spec().munition();
        take(store, m.item().get(), m.rounds(), bill.shots());
        take(store, warhead(), 1, bill.warheads());
        return null;
    }

    /**
     * Pay from player inventory ({@link #pay(Store, Bill)}); shortage shows required/available above hotbar.
     *
     * @return true if paid
     */
    public static boolean pay(ServerPlayer player, Bill bill) {
        MutableComponent missing = pay(of(player), bill);
        if (missing != null) player.displayClientMessage(missing.withStyle(ChatFormatting.RED), true);
        return missing == null;
    }

    /**
     * Take {@code rounds}: opened packs first, then single whole packs, then stacks (avoid extra opened packs:
     * opening a stacked pack creates a separate item needing space).
     */
    private static void take(Store store, Item item, int perItem, int rounds) {
        IItemHandlerModifiable items = store.items();
        for (int pass = 0; pass < 3; pass++) {
            boolean opened = pass == 0;
            for (int i = 0; i < items.getSlots() && rounds > 0; i++) {
                ItemStack s = items.getStackInSlot(i);
                if (!s.is(item) || s.has(ModDataComponents.ROUNDS.get()) != opened || pass == 1 && s.getCount() > 1) continue;
                s = s.copy();
                int left = left(s, perItem);
                ItemStack opening = ItemStack.EMPTY;
                while (rounds > 0 && !s.isEmpty()) {
                    if (rounds >= left) {
                        s.shrink(1);
                        rounds -= left;
                    } else if (s.getCount() == 1) {
                        setLeft(s, perItem, left - rounds);
                        rounds = 0;
                    } else {
                        // Open one pack from whole-pack stack: remainder becomes separate item.
                        s.shrink(1);
                        opening = pack(item, perItem, left - rounds);
                        rounds = 0;
                    }
                }
                items.setStackInSlot(i, s);
                if (!opening.isEmpty()) store.put(opening);
            }
        }
        if (rounds > 0) throw new IllegalStateException("took fewer rounds than checked: short by " + rounds);
    }

    /** Refund payment to {@code store}. */
    public static void refund(Store store, Bill bill) {
        WeaponSpec.Munition m = bill.weapon().spec().munition();
        give(store, m.item().get(), m.rounds(), bill.shots());
        give(store, warhead(), 1, bill.warheads());
    }

    /** Refund player: inventory, at feet if full, death site if dead; chat message to them. */
    private static void refund(ServerPlayer player, Bill bill) {
        WeaponSpec.Munition m = bill.weapon().spec().munition();
        refunded(player, m.item().get(), m.rounds(), bill.shots());
        refunded(player, warhead(), 1, bill.warheads());
        refund(of(player), bill);
        if (!keeps(player)) player.sendSystemMessage(Component.translatable("incoming.munitions.refunded.dead").withStyle(ChatFormatting.GRAY));
    }

    /** Player refund line for {@code rounds} rounds of {@code item}. */
    private static void refunded(ServerPlayer player, Item item, int perItem, int rounds) {
        if (rounds <= 0) return;
        player.sendSystemMessage((perItem > 1 ? Component.translatable("incoming.munitions.refunded.rounds", item.getDescription(), rounds)
                : Component.translatable("incoming.munitions.refunded", item.getDescription(), rounds)).withStyle(ChatFormatting.GRAY));
    }

    /**
     * Player inventory survives respawn if alive or {@code keepInventory}. Dead players on death screen remain
     * listed, but inventory without {@code keepInventory} is not transferred on respawn ({@code
     * ServerPlayer.restoreFrom}): inserted items would vanish.
     */
    private static boolean keeps(ServerPlayer player) {
        return player.isAlive() || player.serverLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
    }

    /**
     * Refund {@code owner} if among {@code online} (server players; GameTest passes its own), otherwise log:
     * offline owner gets no refund.
     *
     * @param why refund reason for log
     */
    public static void refund(Collection<? extends ServerPlayer> online, @Nullable UUID owner, Bill bill, String why) {
        if (owner == null || bill.shots() <= 0 && bill.warheads() <= 0) return;
        for (ServerPlayer p : online) {
            if (p.getUUID().equals(owner)) {
                refund(p, bill);
                Incoming.LOG.info("Munitions refund ({}): {} ×{}{} — {}", why, bill.weapon().getSerializedName(), bill.shots(),
                        bill.warheads() > 0 ? ", nuclear warheads ×" + bill.warheads() : "", p.getGameProfile().getName());
                return;
            }
        }
        Incoming.LOG.info("Munitions refund ({}): {} ×{}{} not returned — owner {} is offline", why, bill.weapon().getSerializedName(),
                bill.shots(), bill.warheads() > 0 ? ", nuclear warheads ×" + bill.warheads() : "", owner);
    }

    /** Give {@code rounds}: first refill opened packs (canceled salvo restores whole pack), then give items. */
    private static void give(Store store, Item item, int perItem, int rounds) {
        if (rounds <= 0) return;
        IItemHandlerModifiable items = store.items();
        if (perItem > 1 && store.keeps()) {
            for (int i = 0; i < items.getSlots() && rounds > 0; i++) {
                ItemStack s = items.getStackInSlot(i);
                if (!s.is(item) || s.getCount() != 1 || !s.has(ModDataComponents.ROUNDS.get())) continue;
                int left = left(s, perItem), add = Math.min(perItem - left, rounds);
                s = s.copy();
                setLeft(s, perItem, left + add);
                items.setStackInSlot(i, s);
                rounds -= add;
            }
        }
        int whole = rounds / perItem;
        int max = new ItemStack(item).getMaxStackSize();
        while (whole > 0) {
            int n = Math.min(whole, max);
            store.put(new ItemStack(item, n));
            whole -= n;
        }
        if (rounds % perItem > 0) store.put(pack(item, perItem, rounds % perItem));
    }
}
