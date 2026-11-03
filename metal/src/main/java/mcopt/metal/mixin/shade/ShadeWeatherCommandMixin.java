package mcopt.metal.mixin.shade;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.Locale;
import mcopt.metal.shade.ShadeSky;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.WeatherCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * mcopt's sky types on /weather (ShadeSky): /weather cumulus|cumulonimbus|cirrus|cirrostratus|cloudless [duration] holds that sky,
 * /weather fog [duration] brings in fog, and vanilla's clear, rain and thunder hand the sky back to the model. Single player: the
 * command runs on the integrated server, which shares ShadeSky with the client.
 */
@Mixin(WeatherCommand.class)
abstract class ShadeWeatherCommandMixin {
	/** As vanilla's default weather duration: 5 minutes. */
	private static final int MCOPT_DEFAULT = 6000;

	@Inject(method = "register", at = @At("TAIL"))
	private static void mcopt$skyTypes(CommandDispatcher<CommandSourceStack> dispatcher, CallbackInfo ci) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("weather").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
		for (ShadeSky.Type t : ShadeSky.Type.values()) {
			String name = t == ShadeSky.Type.CLEAR ? "cloudless" : t.name().toLowerCase(Locale.ROOT);
			root.then(Commands.literal(name).executes(c -> mcopt$sky(c, t, MCOPT_DEFAULT))
				.then(Commands.argument("duration", TimeArgument.time(1)).executes(c -> mcopt$sky(c, t, IntegerArgumentType.getInteger(c, "duration")))));
		}
		root.then(Commands.literal("fog").executes(c -> mcopt$fog(c, MCOPT_DEFAULT))
			.then(Commands.argument("duration", TimeArgument.time(1)).executes(c -> mcopt$fog(c, IntegerArgumentType.getInteger(c, "duration")))));
		dispatcher.register(root);
	}

	private static int mcopt$sky(CommandContext<CommandSourceStack> c, ShadeSky.Type t, int ticks) {
		ShadeSky.force(t, c.getSource().getLevel().getGameTime() + ticks);
		String name = t == ShadeSky.Type.CLEAR ? "cloudless" : t.name().toLowerCase(Locale.ROOT);
		c.getSource().sendSuccess(() -> Component.literal("Set the sky to " + name), true);
		return ticks;
	}

	private static int mcopt$fog(CommandContext<CommandSourceStack> c, int ticks) {
		ShadeSky.forceFog(c.getSource().getLevel().getGameTime() + ticks);
		c.getSource().sendSuccess(() -> Component.literal("Brought in fog"), true);
		return ticks;
	}

	@Inject(method = {"setClear", "setRain", "setThunder"}, at = @At("HEAD"))
	private static void mcopt$release(CommandSourceStack source, int ticks, CallbackInfoReturnable<Integer> cir) {
		ShadeSky.release(source.getLevel().getGameTime());
	}
}
