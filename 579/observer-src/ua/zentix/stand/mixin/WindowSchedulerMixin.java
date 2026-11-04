package ua.zentix.stand.mixin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import ua.zentix.stand.WindowTelemetry;
@Pseudo
@Mixin(targets="ua.zentix.incoming.work.WorkScheduler",remap=false)
abstract class WindowSchedulerMixin {
    @Inject(method="onLevelTickPre",at=@At("HEAD"))
    private static void stand$earlyBefore(LevelTickEvent.Pre e,CallbackInfo ci){
        if(e.getLevel() instanceof ServerLevel l) WindowTelemetry.phase(l.getServer(),"early",e.hasTime());
    }
    @Inject(method="onLevelTickPre",at=@At("RETURN"))
    private static void stand$earlyAfter(LevelTickEvent.Pre e,CallbackInfo ci){
        if(e.getLevel() instanceof ServerLevel l) WindowTelemetry.phaseEnd(l.getServer());
    }
    @Inject(method="onServerTick",at=@At("HEAD"))
    private static void stand$lateBefore(ServerTickEvent.Post e,CallbackInfo ci){WindowTelemetry.phase(e.getServer(),"late",e.hasTime());}
    @Inject(method="onServerTick",at=@At("RETURN"))
    private static void stand$lateAfter(ServerTickEvent.Post e,CallbackInfo ci){WindowTelemetry.phaseEnd(e.getServer());}
    @Inject(method="impactBudget(JZ)J",at=@At("RETURN"))
    private static void stand$cap(long total,boolean lower,CallbackInfoReturnable<Long> ci){WindowTelemetry.cap(total,lower,ci.getReturnValue());}
    @Inject(method="timing",at=@At("RETURN"))
    private static void stand$timing(MinecraftServer server,CallbackInfoReturnable<Object> ci){WindowTelemetry.timing(server,ci.getReturnValue());}
}
