package moe.tenshii.mineagent.agent;

import moe.tenshii.mineagent.MineAgentClient;
import moe.tenshii.mineagent.agent.llm.AnthropicBackend;
import moe.tenshii.mineagent.agent.llm.OpenAiBackend;
import moe.tenshii.mineagent.agent.llm.Provider;
import moe.tenshii.mineagent.config.AgentConfig;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.TaskRunner;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

/**
 * Entry point for starting, steering and stopping the agent. Safe to call from any thread.
 */
public final class AgentController {
	private static final AgentController INSTANCE = new AgentController();

	private @Nullable AgentSession session;
	private volatile String phase = "idle";
	private volatile long inputTokens;
	private volatile long outputTokens;

	private AgentController() {
	}

	public static AgentController get() {
		return INSTANCE;
	}

	public synchronized boolean isRunning() {
		return session != null;
	}

	public synchronized @Nullable String currentGoal() {
		return session == null ? null : session.goal();
	}

	/**
	 * @return an error to show the player, or null when the goal started
	 */
	public synchronized @Nullable String start(String goal) {
		if (session != null) {
			return "Already working on: " + session.goal() + ". Use /agent say to add instructions, or /agent stop first.";
		}
		AgentConfig config = AgentConfig.get();
		Provider provider = config.provider();
		String key = config.apiKey(provider);
		if (key.isEmpty() && provider.keyRequired()) {
			return "No API key set for " + provider.id() + ". Run /agent key to enter one"
				+ (provider.envVar().isEmpty() ? "." : ", or set " + provider.envVar() + " before launching the game.");
		}
		String model = config.model(provider);
		if (model.isEmpty()) {
			return "No model chosen for " + provider.id() + ". Set one with /agent model <id>.";
		}
		String baseUrl = config.baseUrl(provider);
		if (baseUrl.isEmpty()) {
			return "No base URL set for " + provider.id() + ". Set one with /agent baseurl <url>.";
		}
		String effort = config.effort;
		int maxTokens = config.maxTokens;
		AgentSession.BackendFactory factory = (system, tools) -> switch (provider.protocol()) {
			case ANTHROPIC -> new AnthropicBackend(key, baseUrl, model, effort, maxTokens, system, tools);
			case OPENAI -> new OpenAiBackend(provider.id(), baseUrl, key, model, system, tools);
		};
		AgentSession created = new AgentSession(goal, config.maxTurns, factory, this);
		session = created;
		inputTokens = 0;
		outputTokens = 0;
		phase = "starting";
		Minecraft.getInstance().execute(() -> PlayerControl.takeControl(Minecraft.getInstance()));
		Thread.ofVirtual().name("MineAgent session").start(created);
		return null;
	}

	public synchronized boolean addGuidance(String text) {
		if (session == null) {
			return false;
		}
		session.addGuidance(text);
		return true;
	}

	/**
	 * Called on the client thread when the server reports the local player's death. Respawns so the death screen
	 * does not block the agent, and tells the model what happened.
	 */
	public synchronized void onPlayerDeath(Minecraft mc, Component deathMessage) {
		if (session == null || mc.player == null || mc.level == null) {
			return;
		}
		String cause = deathMessage.getString().isBlank() ? "unknown cause" : deathMessage.getString();
		BlockPos pos = mc.player.blockPosition();
		String where = pos.toShortString() + " in " + mc.level.dimension().identifier();
		if (mc.level.getLevelData().isHardcore()) {
			notify(Component.literal("Died in hardcore (" + cause + "). Stopping; the death screen is yours.").withStyle(ChatFormatting.RED));
			stop();
			return;
		}
		// Queued before the running action fails, so the model reads the event together with that failure.
		session.addGameEvent("You died at " + where + " (" + cause + ") and have respawned. Unless keepInventory is on, your items"
			+ " and experience were dropped there and the items despawn about 5 minutes after death. Call observe to see where you are now.");
		TaskRunner.cancel(mc, "The player died: " + cause);
		// With immediate respawn on, vanilla has already respawned and shows no death screen.
		if (mc.player.shouldShowDeathScreen()) {
			mc.player.respawn();
		}
		notify(Component.literal("Died (" + cause + ") at " + where + "; respawning.").withStyle(ChatFormatting.YELLOW));
	}

	public synchronized boolean stop() {
		if (session == null) {
			return false;
		}
		AgentSession stopping = session;
		session = null;
		stopping.cancel();
		phase = "idle";
		releaseGame("Stopped by the user");
		return true;
	}

	synchronized void finished(AgentSession ended, @Nullable String message, boolean error) {
		if (session != ended) {
			return;
		}
		session = null;
		phase = "idle";
		releaseGame("Agent finished");
		if (message != null) {
			notify(Component.literal(message).withStyle(error ? ChatFormatting.RED : ChatFormatting.GRAY));
		}
		notify(Component.literal(String.format("Goal ended. Tokens used: %,d in / %,d out.", inputTokens, outputTokens))
			.withStyle(ChatFormatting.DARK_GRAY));
	}

	private static void releaseGame(String reason) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			TaskRunner.cancel(mc, reason);
			PlayerControl.releaseControl(mc);
		});
	}

	void setPhase(String value) {
		phase = value;
	}

	void setUsage(long input, long output) {
		inputTokens = input;
		outputTokens = output;
	}

	public String phase() {
		String task = TaskRunner.currentDescription();
		return task != null ? task : phase;
	}

	public long inputTokens() {
		return inputTokens;
	}

	public long outputTokens() {
		return outputTokens;
	}

	/** Shows the model's own words in chat. */
	void say(String text) {
		notify(Component.literal(text).withStyle(ChatFormatting.WHITE));
	}

	public static void notify(Component message) {
		MutableComponent line = Component.literal("[Agent] ").withStyle(ChatFormatting.AQUA).append(message);
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) {
				mc.player.sendSystemMessage(line);
			} else {
				MineAgentClient.LOGGER.info("{}", line.getString());
			}
		});
	}
}
