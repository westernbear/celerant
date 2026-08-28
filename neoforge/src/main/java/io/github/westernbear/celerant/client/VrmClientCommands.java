package io.github.westernbear.celerant.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

public final class VrmClientCommands {

	private static boolean registered;

	private VrmClientCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		if (registered) {
			return;
		}
		registered = true;
		dispatcher.register(Commands.literal("celerant").then(Commands.literal("vrm")
			.then(Commands.literal("load")
				.then(Commands.argument("file", StringArgumentType.string()).executes(context -> {
					VrmRuntime runtime = VrmRuntime.getInstance();
					if (!runtime.load(StringArgumentType.getString(context, "file"), position(context.getSource()))) {
						return error(context.getSource(), "A VRM is already loading");
					}
					return feedback(context.getSource(), "Loading VRM asynchronously");
				})))
			.then(Commands.literal("unload").executes(context -> {
				if (!VrmRuntime.getInstance().unload()) {
					return error(context.getSource(), "No VRM is loaded");
				}
				return feedback(context.getSource(), "VRM unloaded");
			}))
			.then(Commands.literal("here").executes(context -> {
				VrmRuntime.getInstance().place(position(context.getSource()));
				return feedback(context.getSource(), "VRM position set to your current position");
			}))
			.then(Commands.literal("scale")
				.then(Commands.argument("value", FloatArgumentType.floatArg(0.001F, 100.0F))
					.executes(context -> {
						float scale = FloatArgumentType.getFloat(context, "value");
						VrmRuntime.getInstance().setScale(scale);
						return feedback(context.getSource(), "VRM scale set to " + scale);
					})))
			.then(Commands.literal("avatar")
				.then(Commands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
					boolean enabled = BoolArgumentType.getBool(context, "enabled");
					VrmRuntime runtime = VrmRuntime.getInstance();
					if (!runtime.setAvatarEnabled(enabled)) {
						return error(context.getSource(), "Cannot enable avatar: " + runtime.avatarProblem());
					}
					return feedback(context.getSource(), "VRM avatar " + (enabled ? "enabled" : "disabled"));
				})))
			.then(Commands.literal("expression")
				.executes(context -> {
					VrmRuntime runtime = VrmRuntime.getInstance();
					String names = String.join(", ", runtime.expressionNames());
					return feedback(context.getSource(), names.isEmpty() ? "No morph expressions" : names);
				})
				.then(Commands.literal("clear").executes(context -> {
					if (!VrmRuntime.getInstance().clearExpression()) {
						return error(context.getSource(), "No VRM is loaded");
					}
					return feedback(context.getSource(), "VRM expression cleared");
				}))
				.then(Commands.argument("name", StringArgumentType.word())
					.suggests((context, builder) -> SharedSuggestionProvider.suggest(
						VrmRuntime.getInstance().expressionNames(), builder))
					.executes(context -> setExpression(context.getSource(),
						StringArgumentType.getString(context, "name"), 1.0F))
					.then(Commands.argument("weight", FloatArgumentType.floatArg(0.0F, 1.0F))
						.executes(context -> setExpression(context.getSource(),
							StringArgumentType.getString(context, "name"),
							FloatArgumentType.getFloat(context, "weight"))))))
			.then(Commands.literal("remotes").executes(context ->
				feedback(context.getSource(),
					io.github.westernbear.celerant.client.remote.RemoteLocoApplicator.summarize())))
			.then(Commands.literal("info").executes(context ->
				feedback(context.getSource(), VrmRuntime.getInstance().info())))));
	}

	private static Vec3 position(CommandSourceStack source) {
		return source.getPosition();
	}

	private static int setExpression(CommandSourceStack source, String name, float weight) {
		if (!VrmRuntime.getInstance().setExpression(name, weight)) {
			return error(source, "Unknown expression or no VRM loaded: " + name);
		}
		return feedback(source, "VRM expression set to " + name + " at " + weight);
	}

	private static int feedback(CommandSourceStack source, String message) {
		source.sendSuccess(() -> Component.literal("[Celerant] " + message), false);
		return 1;
	}

	private static int error(CommandSourceStack source, String message) {
		source.sendFailure(Component.literal("[Celerant] " + message));
		return 0;
	}
}
