package moe.tenshii.mineagent.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.net.URI;
import java.util.Locale;
import moe.tenshii.mineagent.agent.AgentController;
import moe.tenshii.mineagent.agent.llm.Provider;
import moe.tenshii.mineagent.config.AgentConfig;
import moe.tenshii.mineagent.ui.ApiKeyScreen;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/**
 * Client-side /agent command. These never reach the server.
 */
public final class AgentCommands {
	private AgentCommands() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("agent")
			.executes(AgentCommands::help)
			.then(literal("goal")
				.then(argument("goal", StringArgumentType.greedyString()).executes(AgentCommands::goal)))
			.then(literal("say")
				.then(argument("message", StringArgumentType.greedyString()).executes(AgentCommands::say)))
			.then(literal("stop").executes(AgentCommands::stop))
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
			.then(literal("effort")
				.executes(AgentCommands::status)
				.then(argument("level", StringArgumentType.word())
					.suggests((context, builder) -> SharedSuggestionProvider.suggest(AgentConfig.EFFORT_LEVELS, builder))
					.executes(AgentCommands::setEffort))));
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
