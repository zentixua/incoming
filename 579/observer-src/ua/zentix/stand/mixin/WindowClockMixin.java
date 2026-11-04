package ua.zentix.stand.mixin;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.zentix.stand.WindowClockView;
import ua.zentix.stand.WindowTelemetry;
/** Numeric observation only. No timer, allocation, reflection, stack or logging per record. */
@Pseudo
@Mixin(targets="ua.zentix.incoming.nuclear.world.WorkClock",remap=false)
abstract class WindowClockMixin implements WindowClockView {
    @Shadow private long usedThisTick, deadline, busyDeadline;
    @Shadow @Final private long unitCost;
    @Shadow private boolean worked, lateStart;
    @Unique private long stand$sum, stand$count, stand$first, stand$late, stand$last, stand$began;
    @Unique private boolean stand$lastFirst, stand$lastLate;
    @ModifyVariable(method="end(J)J",at=@At("HEAD"),argsOnly=true,ordinal=0)
    private long stand$endBegin(long began){if(WindowTelemetry.enabled())stand$began=began;return began;}
    @ModifyVariable(method="record(J)V",at=@At("HEAD"),argsOnly=true,ordinal=0)
    private long stand$record(long nanos) {
        if (!WindowTelemetry.enabled()) return nanos;
        stand$sum += nanos; stand$count++; stand$last=nanos;
        stand$lastFirst=!worked; stand$lastLate=lateStart;
        if (!worked) stand$first++;
        if (lateStart) stand$late++;
        return nanos;
    }
    @Inject(method="accountWindow(JJ)V",at=@At("HEAD"),require=0)
    private void stand$accountBefore(long began,long before,CallbackInfo ci) {
        WindowTelemetry.accountBefore(this,began,before,usedThisTick);
    }
    @Inject(method="accountWindow(JJ)V",at=@At("RETURN"),require=0)
    private void stand$accountAfter(long began,long before,CallbackInfo ci) {
        WindowTelemetry.accountAfter(this,usedThisTick);
    }
    public long stand$used(){return usedThisTick;}
    public long stand$deadline(){return deadline;}
    public long stand$busyDeadline(){return busyDeadline;}
    public boolean stand$worked(){return worked;}
    public boolean stand$late(){return lateStart;}
    public boolean stand$counting(){return unitCost>0;}
    public long stand$stepSum(){return stand$sum;}
    public long stand$stepCount(){return stand$count;}
    public long stand$firstCount(){return stand$first;}
    public long stand$lateCount(){return stand$late;}
    public long stand$lastStep(){return stand$last;}
    public long stand$lastBegan(){return stand$began;}
    public boolean stand$lastFirst(){return stand$lastFirst;}
    public boolean stand$lastLate(){return stand$lastLate;}
}
