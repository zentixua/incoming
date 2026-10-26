package ua.zentix.incoming.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.function.Predicate;

/**
 * Block munition stock (SAM, fixed launcher): slots accepting {@code valid} items. Externally exposes {@link #loader}
 * through {@link Capabilities.ItemHandler#BLOCK} on any side (hopper, Create funnel, chute and mechanical arm via
 * funnel): insertion only; hopper below cannot extract. Broken block drops stock ({@link #dropAll}); comparator reads
 * fullness ({@link #comparator}).
 * <p>
 * Spare slots follow open slots and are hidden externally: internal returns go there ({@link #stow}) — opened-pack
 * remainder when a hopper keeps open slots full, otherwise it would drop on the ground.
 */
public class Magazine extends ItemStackHandler {
    private final int open;
    private final Predicate<ItemStack> valid;
    private final Runnable changed;
    private final IItemHandler loader = new Loader();

    /**
     * @param valid   accepted items
     * @param changed action on every change (usually {@code BlockEntity::setChanged}: save and update comparator)
     */
    public Magazine(int slots, Predicate<ItemStack> valid, Runnable changed) {
        this(slots, 0, valid, changed);
    }

    /** Stock with {@code slots} open slots and {@code spare} spare slots ({@link #stow}). */
    public Magazine(int slots, int spare, Predicate<ItemStack> valid, Runnable changed) {
        super(slots + spare);
        this.open = slots;
        this.valid = valid;
        this.changed = changed;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return valid.test(stack);
    }

    @Override
    protected void onContentsChanged(int slot) {
        changed.run();
    }

    /** External view: insertion only. */
    public IItemHandler loader() {
        return loader;
    }

    /** Insert a stack into open slots (matching stacks first); returns remainder. */
    public ItemStack load(ItemStack stack) {
        return ItemHandlerHelper.insertItemStacked(loader, stack, false);
    }

    /** Stow an internal return (opened-pack remainder, refund): open slots then spare slots; returns remainder. */
    public ItemStack stow(ItemStack stack) {
        return ItemHandlerHelper.insertItemStacked(this, stack, false);
    }

    /** Items in stock. */
    public int count() {
        int n = 0;
        for (int i = 0; i < getSlots(); i++) n += getStackInSlot(i).getCount();
        return n;
    }

    /** Comparator signal from open-slot fullness, as a chest. */
    public int comparator() {
        return ItemHandlerHelper.calcRedstoneFromInventory(loader);
    }

    /** Block broken or exploded: drop stock at its position and clear slots. */
    public void dropAll(Level level, BlockPos pos) {
        for (int i = 0; i < getSlots(); i++) {
            ItemStack s = getStackInSlot(i);
            if (!s.isEmpty()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), s.copy());
            setStackInSlot(i, ItemStack.EMPTY);
        }
    }

    /** External stock exposes open slots: contents visible, insertion allowed, extraction denied. */
    private final class Loader implements IItemHandler {
        @Override
        public int getSlots() {
            return open;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return Magazine.this.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return Magazine.this.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return Magazine.this.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return Magazine.this.isItemValid(slot, stack);
        }
    }
}
