package mcopt.metal.mixin.server;

import java.util.function.BooleanSupplier;
import mcopt.metal.server.ServerBench;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** -Dmcopt.server.bench=N: times every server tick and builds the benchmark scene (ServerBench). */
@Mixin(MinecraftServer.class)
abstract class ServerBenchMixin {
	@Inject(method = "tickServer", at = @At("HEAD"))
	private void mcopt$benchStart(BooleanSupplier haveTime, CallbackInfo ci) {
		ServerBench.start((MinecraftServer) (Object) this);
	}

	@Inject(method = "tickServer", at = @At("RETURN"))
	private void mcopt$benchEnd(BooleanSupplier haveTime, CallbackInfo ci) {
		ServerBench.end((MinecraftServer) (Object) this);
	}
}
