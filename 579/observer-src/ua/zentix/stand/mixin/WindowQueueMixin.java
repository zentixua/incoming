package ua.zentix.stand.mixin;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ua.zentix.stand.WindowTelemetry;
@Pseudo
@Mixin(targets="ua.zentix.incoming.work.UnitQueue",remap=false)
abstract class WindowQueueMixin {
    @Inject(method="work(Lnet/minecraft/server/level/ServerLevel;JLua/zentix/incoming/nuclear/world/WorkClock;)V",at=@At("HEAD"))
    private void stand$before(ServerLevel level,long now,@Coerce Object clock,CallbackInfo ci){WindowTelemetry.begin(level,clock);}
    @Inject(method="work(Lnet/minecraft/server/level/ServerLevel;JLua/zentix/incoming/nuclear/world/WorkClock;)V",at=@At("RETURN"))
    private void stand$after(ServerLevel level,long now,@Coerce Object clock,CallbackInfo ci){WindowTelemetry.end(clock);}
}
