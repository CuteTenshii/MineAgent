package moe.tenshii.mineagent.agent;

import java.util.Map;
import moe.tenshii.mineagent.MineAgentClient;
import moe.tenshii.mineagent.agent.llm.AnthropicBackend;
import moe.tenshii.mineagent.agent.llm.OpenAiBackend;
import moe.tenshii.mineagent.agent.llm.Provider;
import moe.tenshii.mineagent.config.AgentConfig;
import moe.tenshii.mineagent.game.Inventories;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.TaskRunner;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.gamerules.GameRules;
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

	/** A death on a server whose keepInventory rule cannot be read; resolved by watching the respawned inventory. */
	private record PendingDeath(LocalPlayer deadPlayer, String where, Map<Item, Integer> carried, int[] ticksSinceRespawn) {
	}

	private static final int INVENTORY_SYNC_TICKS = 40;

	private @Nullable PendingDeath pendingDeath;

	/**
	 * Called on the client thread when the server reports the local player's death. Respawns so the death screen
	 * does not block the agent, and tells the model what happened, including whether the inventory was kept.
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
		// The server sends the death before dropping the loot, so this is still what the player carried.
		Map<Item, Integer> carried = Inventories.snapshot(mc.player);
		String event = "You died at " + where + " (" + cause + ") and have respawned.";
		IntegratedServer server = mc.getSingleplayerServer();
		pendingDeath = null;
		if (carried.isEmpty()) {
			event += " You were carrying nothing.";
		} else if (server != null) {
			event += " " + inventoryFate(server.getGameRules().get(GameRules.KEEP_INVENTORY), where, carried);
		} else {
			event += " Whether your items were kept is being checked; you will be told shortly.";
			pendingDeath = new PendingDeath(mc.player, where, carried, new int[1]);
		}
		// Queued before the running action fails, so the model reads the event together with that failure.
		session.addGameEvent(event + " Call observe to see where you are now.");
		TaskRunner.cancel(mc, "The player died: " + cause);
		// With immediate respawn on, vanilla has already respawned and shows no death screen.
		if (mc.player.shouldShowDeathScreen()) {
			mc.player.respawn();
		}
		notify(Component.literal("Died (" + cause + ") at " + where + "; respawning.").withStyle(ChatFormatting.YELLOW));
	}

	/**
	 * Called every client tick. On servers, keepInventory is not sent to clients, so after a respawn the new inventory
	 * shows whether the items were kept.
	 */
	public synchronized void tick(Minecraft mc) {
		PendingDeath pending = pendingDeath;
		if (pending == null || session == null) {
			pendingDeath = null;
			return;
		}
		LocalPlayer player = mc.player;
		if (player == null || player == pending.deadPlayer() || !player.isAlive()) {
			return;
		}
		boolean kept = Inventories.snapshot(player).keySet().stream().anyMatch(pending.carried()::containsKey);
		if (!kept && ++pending.ticksSinceRespawn()[0] < INVENTORY_SYNC_TICKS) {
			return;
		}
		pendingDeath = null;
		session.addGameEvent(inventoryFate(kept, pending.where(), pending.carried()));
	}

	private static String inventoryFate(boolean kept, String where, Map<Item, Integer> carried) {
		if (kept) {
			return "keepInventory is on, so you kept your items and experience.";
		}
		return "keepInventory is off: your items (" + Inventories.describe(carried) + ") and experience were dropped at " + where
			+ ". Dropped items despawn about 5 minutes after death.";
	}

	/** Called from the session thread when it reaches its turn limit and pauses. */
	synchronized void onTurnLimit(AgentSession paused, int turnsUsed, int defaultExtra) {
		if (session != paused) {
			return;
		}
		phase = "paused at " + turnsUsed + " turns - /agent continue or /agent stop";
		Minecraft mc = Minecraft.getInstance();
		// Hand the player back while nothing is happening, so the user is not stuck waiting for a decision.
		mc.execute(() -> PlayerControl.releaseControl(mc));
		MutableComponent message = Component.literal(String.format("Reached %d turns (%,d tokens in / %,d out so far). ",
				turnsUsed, inputTokens, outputTokens))
			.withStyle(ChatFormatting.YELLOW)
			.append(chatButton("[Continue]", "/agent continue", "Allow " + defaultExtra + " more turns", ChatFormatting.GREEN))
			.append(Component.literal(" "))
			.append(chatButton("[Stop]", "/agent stop", "End this goal", ChatFormatting.RED))
			.append(Component.literal(" or type /agent continue <n> for a specific number of turns.").withStyle(ChatFormatting.GRAY));
		notify(message);
	}

	/** Clickable chat text that runs a client command; Fabric routes clicked commands to client commands directly. */
	private static MutableComponent chatButton(String label, String command, String hover, ChatFormatting color) {
		return Component.literal(label).withStyle(style -> style
			.withColor(color)
			.withBold(true)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover + " (" + command + ")"))));
	}

	synchronized void onResumed(AgentSession resumed) {
		if (session != resumed) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> PlayerControl.takeControl(mc));
	}

	/**
	 * Lets a goal paused at its turn limit continue for {@code extra} more turns.
	 *
	 * @return an error to show the player, or null when the goal resumed
	 */
	public synchronized @Nullable String continueGoal(int extra) {
		if (session == null) {
			return "The agent is not running.";
		}
		if (!session.isWaitingForTurns()) {
			return "The agent is not paused at its turn limit.";
		}
		session.grantTurns(extra);
		phase = "resuming";
		return null;
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
