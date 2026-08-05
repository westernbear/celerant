package io.github.westernbear.celerant.client;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

public final class VrmClientCommands {

	private static boolean registered;

	private VrmClientCommands() {
	}

	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
			ClientCommands.literal("celerant").then(ClientCommands.literal("vrm")
				.then(ClientCommands.literal("load")
					.then(ClientCommands.argument("file", StringArgumentType.string()).executes(context -> {
						VrmRuntime runtime = VrmRuntime.getInstance();
						if (!runtime.load(StringArgumentType.getString(context, "file"), context.getSource().getPosition())) {
							return error(context.getSource(), "A VRM is already loading");
						}
						return feedback(context.getSource(), "Loading VRM asynchronously");
					})))
				.then(ClientCommands.literal("unload").executes(context -> {
					if (!VrmRuntime.getInstance().unload()) {
						return error(context.getSource(), "No VRM is loaded");
					}
					return feedback(context.getSource(), "VRM unloaded");
				}))
				.then(ClientCommands.literal("here").executes(context -> {
					VrmRuntime.getInstance().place(context.getSource().getPosition());
					return feedback(context.getSource(), "VRM position set to your current position");
				}))
				.then(ClientCommands.literal("scale")
					.then(ClientCommands.argument("value", FloatArgumentType.floatArg(0.001F, 100.0F))
						.executes(context -> {
							float scale = FloatArgumentType.getFloat(context, "value");
							VrmRuntime.getInstance().setScale(scale);
							return feedback(context.getSource(), "VRM scale set to " + scale);
						})))
				.then(ClientCommands.literal("expression")
					.executes(context -> {
						VrmRuntime runtime = VrmRuntime.getInstance();
						String names = String.join(", ", runtime.expressionNames());
						return feedback(context.getSource(), names.isEmpty() ? "No morph expressions" : names);
					})
					.then(ClientCommands.literal("clear").executes(context -> {
						if (!VrmRuntime.getInstance().clearExpression()) {
							return error(context.getSource(), "No VRM is loaded");
						}
						return feedback(context.getSource(), "VRM expression cleared");
					}))
					.then(ClientCommands.argument("name", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(
							VrmRuntime.getInstance().expressionNames(), builder))
						.executes(context -> setExpression(context.getSource(),
							StringArgumentType.getString(context, "name"), 1.0F))
						.then(ClientCommands.argument("weight", FloatArgumentType.floatArg(0.0F, 1.0F))
							.executes(context -> setExpression(context.getSource(),
								StringArgumentType.getString(context, "name"),
								FloatArgumentType.getFloat(context, "weight"))))))
				.then(ClientCommands.literal("info").executes(context ->
					feedback(context.getSource(), VrmRuntime.getInstance().info()))))));
	}

	private static int setExpression(FabricClientCommandSource source, String name, float weight) {
		if (!VrmRuntime.getInstance().setExpression(name, weight)) {
			return error(source, "Unknown expression or no VRM loaded: " + name);
		}
		return feedback(source, "VRM expression set to " + name + " at " + weight);
	}

	private static int feedback(FabricClientCommandSource source, String message) {
		source.sendFeedback(Component.literal("[Celerant] " + message));
		return 1;
	}

	private static int error(FabricClientCommandSource source, String message) {
		source.sendError(Component.literal("[Celerant] " + message));
		return 0;
	}
}
