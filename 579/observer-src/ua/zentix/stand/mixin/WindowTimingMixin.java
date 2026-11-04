package ua.zentix.stand.mixin;
import org.spongepowered.asm.mixin.*;
import ua.zentix.stand.WindowTimingView;
@Pseudo
@Mixin(targets="ua.zentix.incoming.work.ImpactTiming",remap=false)
abstract class WindowTimingMixin implements WindowTimingView {
    @Shadow long earlyUsed, earlyBudget;
    @Shadow boolean active;
    public long stand$earlyUsed(){return earlyUsed;}
    public long stand$earlyBudget(){return earlyBudget;}
    public boolean stand$active(){return active;}
}
