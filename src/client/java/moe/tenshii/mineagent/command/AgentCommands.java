package moe.tenshii.mineagent.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.net.URI;
import java.util.Locale;
import moe.tenshii.mineagent.agent.AgentController;
import moe.tenshii.mineagent.agent.llm.ModelCapabilities;
import moe.tenshii.mineagent.agent.llm.Provider;
import moe.tenshii.mineagent.config.AgentConfig;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.TaskRunner;
import moe.tenshii.mineagent.game.task.WalkTask;
import moe.tenshii.mineagent.path.Goal;
import moe.tenshii.mineagent.ui.ApiKeyScreen;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Client-side /agent command. These never reach the server.
 */
public final class AgentCommands {
	private AgentCommands() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		LiteralArgumentBuilder<FabricClientCommandSource> root = literal("agent");
		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			root.then(literal("dev")
				.then(literal("walk")
					.then(argument("x", IntegerArgumentType.integer())
						.then(argument("y", IntegerArgumentType.integer())
							.then(argument("z", IntegerArgumentType.integer()).executes(AgentCommands::devWalk))))));
		}
		dispatcher.register(root
			.executes(AgentCommands::help)
			.then(literal("goal")
				.then(argument("goal", StringArgumentType.greedyString()).executes(AgentCommands::goal)))
			.then(literal("say")
				.then(argument("message", StringArgumentType.greedyString()).executes(AgentCommands::say)))
			.then(literal("stop").executes(AgentCommands::stop))
			.then(literal("continue")
				.executes(context -> continueGoal(context, AgentConfig.get().maxTurns))
				.then(argument("turns", IntegerArgumentType.integer(1, 2000))
					.executes(context -> continueGoal(context, IntegerArgumentType.getInteger(context, "turns")))))
			.then(literal("status").executes(AgentCommands::status))
			.then(literal("provider")
				.executes(AgentCommands::status)
				.then(argument("provider", StringArgumentType.word())
					.suggests((context, builder) -> SharedSuggestionProvider.suggest(Provider.ids(), builder))
					.executes(AgentCommands::setProvider)))
			.then(literal("key")
				.executes(AgentCommands::openKeyScreen)
				.then(literal("clear").executes(AgentCommands::clearKey)))
			.then(literal("model")
				.executes(AgentCommands::status)
				.then(argument("model", StringArgumentType.greedyString())
					.suggests((context, builder) -> SharedSuggestionProvider.suggest(
						new String[] {AgentConfig.get().model(AgentConfig.get().provider())}, builder))
					.executes(AgentCommands::setModel)))
			.then(literal("baseurl")
				.executes(AgentCommands::status)
				.then(literal("reset").executes(AgentCommands::resetBaseUrl))
				.then(argument("url", StringArgumentType.greedyString()).executes(AgentCommands::setBaseUrl)))
			.then(literal("showpath")
				.executes(AgentCommands::status)
				.then(literal("on").executes(context -> setShowPath(context, true)))
				.then(literal("off").executes(context -> setShowPath(context, false))))
			.then(literal("toolchat")
				.executes(AgentCommands::status)
				.then(literal("on").executes(context -> setToolChat(context, true)))
				.then(literal("off").executes(context -> setToolChat(context, false))))
			.then(literal("vision")
				.executes(AgentCommands::status)
				.then(literal("on").executes(context -> setVision(context, true)))
				.then(literal("off").executes(context -> setVision(context, false)))
				.then(literal("auto").executes(context -> setVision(context, null))))
			.then(literal("commands")
				.executes(AgentCommands::status)
				.then(literal("on").executes(context -> setCommands(context, true)))
				.then(literal("off").executes(context -> setCommands(context, false))))
			.then(literal("effort")
				.executes(AgentCommands::status)
				.then(argument("level", StringArgumentType.word())
					.suggests((context, builder) -> SharedSuggestionProvider.suggest(AgentConfig.EFFORT_LEVELS, builder))
					.executes(AgentCommands::setEffort))));
	}

	/** Development-only: runs the walker directly, without a model, to check movement and the path overlay. */
	private static int devWalk(CommandContext<FabricClientCommandSource> context) {
		if (AgentController.get().isRunning()) {
			context.getSource().sendError(Component.literal("Stop the running goal first."));
			return 0;
		}
		BlockPos target = new BlockPos(IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"),
			IntegerArgumentType.getInteger(context, "z"));
		Minecraft mc = context.getSource().getClient();
		mc.execute(() -> PlayerControl.takeControl(mc));
		Thread.ofVirtual().name("MineAgent dev walk").start(() -> {
			String outcome;
			try {
				outcome = TaskRunner.run(new WalkTask(Goal.near(target, 0), target.toShortString(), true, true, 20 * 600), () -> false);
			} catch (TaskFailure failure) {
				outcome = "Walk failed: " + failure.getMessage();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				outcome = "Walk interrupted.";
			}
			String message = outcome;
			mc.execute(() -> PlayerControl.releaseControl(mc));
			AgentController.notify(Component.literal(message).withStyle(ChatFormatting.GRAY));
		});
		return Command.SINGLE_SUCCESS;
	}

	private static int help(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		source.sendFeedback(Component.literal("MineAgent commands:").withStyle(ChatFormatting.AQUA));
		source.sendFeedback(Component.literal("/agent provider <" + String.join("|", Provider.ids()) + "> - choose the AI provider"));
		source.sendFeedback(Component.literal("/agent key - enter the API key for the current provider (/agent key clear removes it)"));
		source.sendFeedback(Component.literal("/agent model <id> - choose the model; /agent baseurl <url> - API address for custom/ollama"));
		source.sendFeedback(Component.literal("/agent goal <text> - give the agent a goal, e.g. /agent goal get 3 iron ingots"));
		source.sendFeedback(Component.literal("/agent say <text> - add instructions while it works"));
		source.sendFeedback(Component.literal("/agent stop - stop and take back control; /agent status - show settings"));
		source.sendFeedback(Component.literal("/agent continue [turns] - keep going after the turn limit (" + AgentConfig.get().maxTurns + " per goal)"));
		source.sendFeedback(Component.literal("/agent showpath <on|off> - draw the path the agent walks (on by default)"));
		source.sendFeedback(Component.literal("/agent toolchat <on|off> - post every tool call in chat (off by default)"));
		source.sendFeedback(Component.literal("/agent vision <on|off|auto> - let the model take screenshots with look (per provider; auto checks the model)"));
		source.sendFeedback(Component.literal("/agent commands <on|off> - let the agent run server commands the player may use (off by default)"));
		return Command.SINGLE_SUCCESS;
	}

	private static int goal(CommandContext<FabricClientCommandSource> context) {
		String goal = StringArgumentType.getString(context, "goal").strip();
		String error = AgentController.get().start(goal);
		if (error != null) {
			context.getSource().sendError(Component.literal(error));
			return 0;
		}
		AgentConfig config = AgentConfig.get();
		AgentController.notify(Component.literal("Working on: " + goal + " using " + config.provider().id() + "/" + config.model(config.provider())
			+ " (/agent stop to take back control)").withStyle(ChatFormatting.GRAY));
		return Command.SINGLE_SUCCESS;
	}

	private static int say(CommandContext<FabricClientCommandSource> context) {
		String message = StringArgumentType.getString(context, "message").strip();
		if (!AgentController.get().addGuidance(message)) {
			context.getSource().sendError(Component.literal("The agent is not running. Start it with /agent goal <text>."));
			return 0;
		}
		context.getSource().sendFeedback(Component.literal("The agent will read that after its current action.").withStyle(ChatFormatting.GRAY));
		return Command.SINGLE_SUCCESS;
	}

	private static int continueGoal(CommandContext<FabricClientCommandSource> context, int turns) {
		String error = AgentController.get().continueGoal(turns);
		if (error != null) {
			context.getSource().sendError(Component.literal(error));
			return 0;
		}
		context.getSource().sendFeedback(Component.literal("Continuing for up to " + turns + " more turns.").withStyle(ChatFormatting.GRAY));
		return Command.SINGLE_SUCCESS;
	}

	private static int stop(CommandContext<FabricClientCommandSource> context) {
		if (!AgentController.get().stop()) {
			context.getSource().sendError(Component.literal("The agent is not running."));
			return 0;
		}
		context.getSource().sendFeedback(Component.literal("Agent stopped; you have control again.").withStyle(ChatFormatting.GRAY));
		return Command.SINGLE_SUCCESS;
	}

	private static int status(CommandContext<FabricClientCommandSource> context) {
		AgentConfig config = AgentConfig.get();
		AgentController controller = AgentController.get();
		FabricClientCommandSource source = context.getSource();
		String goal = controller.currentGoal();
		source.sendFeedback(Component.literal(goal == null ? "Agent idle." : "Working on: " + goal + " - " + controller.phase()));
		if (goal != null) {
			source.sendFeedback(Component.literal(String.format(Locale.ROOT, "Tokens so far: %,d in / %,d out", controller.inputTokens(), controller.outputTokens())));
		}
		Provider provider = config.provider();
		String model = config.model(provider);
		source.sendFeedback(Component.literal("Provider " + provider.id() + ", model " + (model.isEmpty() ? "(not set)" : model)
			+ ", API key: " + config.keySource(provider)));
		String baseUrl = config.baseUrl(provider);
		source.sendFeedback(Component.literal("Base URL: " + (baseUrl.isEmpty() ? "(not set)" : baseUrl)
			+ (provider == Provider.ANTHROPIC ? ", effort " + config.effort : "")));
		source.sendFeedback(Component.literal("Server commands: " + (config.allowCommands ? "allowed" : "off")
			+ ", screenshots (look): " + describeVision(config, provider)));
		return Command.SINGLE_SUCCESS;
	}

	private static int setProvider(CommandContext<FabricClientCommandSource> context) {
		String id = StringArgumentType.getString(context, "provider");
		Provider provider = Provider.byId(id).orElse(null);
		if (provider == null) {
			context.getSource().sendError(Component.literal("Unknown provider. Choose one of: " + String.join(", ", Provider.ids())));
			return 0;
		}
		AgentConfig config = AgentConfig.get();
		config.provider = provider.id();
		config.save();
		context.getSource().sendFeedback(Component.literal("Provider set to " + provider.id() + "; it applies to the next goal."));
		return status(context);
	}

	private static int openKeyScreen(CommandContext<FabricClientCommandSource> context) {
		Minecraft mc = context.getSource().getClient();
		Provider provider = AgentConfig.get().provider();
		// Deferred so the chat screen finishes closing before the key screen opens.
		mc.execute(() -> mc.gui.setScreen(new ApiKeyScreen(null, provider)));
		return Command.SINGLE_SUCCESS;
	}

	private static int clearKey(CommandContext<FabricClientCommandSource> context) {
		AgentConfig config = AgentConfig.get();
		Provider provider = config.provider();
		config.setApiKey(provider, "");
		config.save();
		context.getSource().sendFeedback(Component.literal("Removed the saved " + provider.id() + " key. Key source now: " + config.keySource(provider)));
		return Command.SINGLE_SUCCESS;
	}

	private static int setModel(CommandContext<FabricClientCommandSource> context) {
		String model = StringArgumentType.getString(context, "model").strip();
		if (model.isEmpty() || model.chars().anyMatch(Character::isWhitespace)) {
			context.getSource().sendError(Component.literal("Give a single model id, e.g. claude-opus-5-5 or gpt-5."));
			return 0;
		}
		AgentConfig config = AgentConfig.get();
		Provider provider = config.provider();
		config.models.put(provider.id(), model);
		config.save();
		context.getSource().sendFeedback(Component.literal(provider.id() + " model set to " + model + "; it applies to the next goal."));
		return Command.SINGLE_SUCCESS;
	}

	private static int setBaseUrl(CommandContext<FabricClientCommandSource> context) {
		String url = StringArgumentType.getString(context, "url").strip();
		try {
			URI parsed = URI.create(url);
			if (parsed.getScheme() == null || !parsed.getScheme().matches("https?") || parsed.getHost() == null) {
				throw new IllegalArgumentException();
			}
		} catch (IllegalArgumentException e) {
			context.getSource().sendError(Component.literal("Give a full http(s) URL, e.g. http://localhost:11434/v1"));
			return 0;
		}
		AgentConfig config = AgentConfig.get();
		Provider provider = config.provider();
		config.baseUrls.put(provider.id(), url);
		config.save();
		context.getSource().sendFeedback(Component.literal(provider.id() + " base URL set to " + url + "."));
		return Command.SINGLE_SUCCESS;
	}

	private static int resetBaseUrl(CommandContext<FabricClientCommandSource> context) {
		AgentConfig config = AgentConfig.get();
		Provider provider = config.provider();
		config.baseUrls.remove(provider.id());
		config.save();
		String url = config.baseUrl(provider);
		context.getSource().sendFeedback(Component.literal(provider.id() + " base URL reset to " + (url.isEmpty() ? "(none)" : url) + "."));
		return Command.SINGLE_SUCCESS;
	}

	private static int setShowPath(CommandContext<FabricClientCommandSource> context, boolean show) {
		AgentConfig config = AgentConfig.get();
		config.showPath = show;
		config.save();
		context.getSource().sendFeedback(Component.literal(show ? "The agent's path will be drawn while it walks." : "Path drawing is off."));
		return Command.SINGLE_SUCCESS;
	}

	private static int setToolChat(CommandContext<FabricClientCommandSource> context, boolean show) {
		AgentConfig config = AgentConfig.get();
		config.showToolCalls = show;
		config.save();
		context.getSource().sendFeedback(Component.literal(show
			? "Each tool call and its result will be posted in chat."
			: "Tool calls will no longer be posted in chat."));
		return Command.SINGLE_SUCCESS;
	}

	/** @param enabled true or false to force look on or off, null to decide per model */
	private static int setVision(CommandContext<FabricClientCommandSource> context, @Nullable Boolean enabled) {
		AgentConfig config = AgentConfig.get();
		Provider provider = config.provider();
		String model = config.model(provider);
		if (enabled == null) {
			config.vision.remove(provider.id());
		} else {
			config.vision.put(provider.id(), enabled);
		}
		if (Boolean.TRUE.equals(enabled)) {
			// An explicit "on" is the way to retry a model that rejected images once.
			config.setTextOnly(provider, model, false);
		}
		config.save();
		String text;
		if (enabled == null) {
			text = "Screenshots (look) for " + provider.id() + " are decided per model from the next goal on: " + describeVision(config, provider) + ".";
		} else if (enabled) {
			text = "The model may take screenshots with look when using " + provider.id() + ", from the next goal on. The model must accept images;"
				+ " if it rejects one, look turns off for that model again.";
		} else {
			text = "The look tool is off for " + provider.id() + " from the next goal on.";
		}
		context.getSource().sendFeedback(Component.literal(text));
		return Command.SINGLE_SUCCESS;
	}

	private static String describeVision(AgentConfig config, Provider provider) {
		String model = config.model(provider);
		if (!model.isEmpty() && config.isTextOnly(provider, model)) {
			return "off (" + model + " rejected images; /agent vision on retries)";
		}
		Boolean chosen = config.visionSetting(provider);
		if (chosen != null) {
			return chosen ? "on" : "off";
		}
		return "auto (" + (ModelCapabilities.canCheck(provider) ? "checks whether the model reads images, else " : "") + (provider.visionByDefault() ? "on" : "off") + ")";
	}

	private static int setCommands(CommandContext<FabricClientCommandSource> context, boolean allow) {
		AgentConfig config = AgentConfig.get();
		config.allowCommands = allow;
		config.save();
		context.getSource().sendFeedback(Component.literal(allow
			? "The agent may run server commands, limited to what this player is permitted to use. It gets the tool from the next goal on."
			: "The agent can no longer run server commands."));
		return Command.SINGLE_SUCCESS;
	}

	private static int setEffort(CommandContext<FabricClientCommandSource> context) {
		String level = StringArgumentType.getString(context, "level").toLowerCase(Locale.ROOT);
		if (!AgentConfig.EFFORT_LEVELS.contains(level)) {
			context.getSource().sendError(Component.literal("Effort must be one of " + String.join(", ", AgentConfig.EFFORT_LEVELS) + "."));
			return 0;
		}
		AgentConfig config = AgentConfig.get();
		config.effort = level;
		config.save();
		context.getSource().sendFeedback(Component.literal("Effort set to " + level + " (Anthropic models only); it applies to the next goal."));
		return Command.SINGLE_SUCCESS;
	}
}
