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
import moe.tenshii.mineagent.ui.ConfigScreen;
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
			.then(literal("answer")
				.then(argument("answer", StringArgumentType.greedyString()).executes(AgentCommands::answer)))
			.then(literal("stop").executes(AgentCommands::stop))
			.then(literal("continue")
				.executes(context -> continueGoal(context, AgentConfig.get().maxTurns))
				.then(argument("turns", IntegerArgumentType.integer(1, 2000))
					.executes(context -> continueGoal(context, IntegerArgumentType.getInteger(context, "turns")))))
			.then(literal("status").executes(AgentCommands::status))
			.then(literal("config").executes(AgentCommands::openConfigScreen))
			.then(literal("key").executes(AgentCommands::openKeyScreen)));
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
		source.sendFeedback(Component.literal("/agent goal <text> - give the agent a goal, e.g. /agent goal get 3 iron ingots"));
		source.sendFeedback(Component.literal("/agent say <text> - add instructions while it works"));
		source.sendFeedback(Component.literal("/agent answer <number or text> - answer the agent's question"));
		source.sendFeedback(Component.literal("/agent stop - stop and take back control; /agent status - show settings"));
		source.sendFeedback(Component.literal("/agent continue [turns] - keep going after the turn limit (" + AgentConfig.get().maxTurns + " per goal)"));
		source.sendFeedback(Component.literal("/agent config - settings: provider, model, screenshots, display and more"));
		source.sendFeedback(Component.literal("/agent key - enter the API key for the current provider in a hidden field"));
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
		// While the agent waits on a question, a message is the answer; otherwise it would sit unread until the reply.
		if (AgentController.get().isAsking()) {
			return answer(context, message);
		}
		if (!AgentController.get().addGuidance(message)) {
			context.getSource().sendError(Component.literal("The agent is not running. Start it with /agent goal <text>."));
			return 0;
		}
		context.getSource().sendFeedback(Component.literal("The agent will read that after its current action.").withStyle(ChatFormatting.GRAY));
		return Command.SINGLE_SUCCESS;
	}

	private static int answer(CommandContext<FabricClientCommandSource> context) {
		return answer(context, StringArgumentType.getString(context, "answer").strip());
	}

	private static int answer(CommandContext<FabricClientCommandSource> context, String text) {
		String error = AgentController.get().answer(text);
		if (error != null) {
			context.getSource().sendError(Component.literal(error));
			return 0;
		}
		context.getSource().sendFeedback(Component.literal("Answered: " + text).withStyle(ChatFormatting.GRAY));
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

	private static int openConfigScreen(CommandContext<FabricClientCommandSource> context) {
		Minecraft mc = context.getSource().getClient();
		// Deferred so the chat screen finishes closing before the settings screen opens.
		mc.execute(() -> mc.gui.setScreen(ConfigScreen.create(null)));
		return Command.SINGLE_SUCCESS;
	}

	private static int openKeyScreen(CommandContext<FabricClientCommandSource> context) {
		Minecraft mc = context.getSource().getClient();
		Provider provider = AgentConfig.get().provider();
		// Deferred so the chat screen finishes closing before the key screen opens.
		mc.execute(() -> mc.gui.setScreen(new ApiKeyScreen(null, provider)));
		return Command.SINGLE_SUCCESS;
	}

	private static String describeVision(AgentConfig config, Provider provider) {
		String model = config.model(provider);
		if (!model.isEmpty() && config.isTextOnly(provider, model)) {
			return "off (" + model + " rejected images; set Screenshots to On in /agent config to retry)";
		}
		Boolean chosen = config.visionSetting(provider);
		if (chosen != null) {
			return chosen ? "on" : "off";
		}
		return "auto (" + (ModelCapabilities.canCheck(provider) ? "checks whether the model reads images, else " : "") + (provider.visionByDefault() ? "on" : "off") + ")";
	}


}
