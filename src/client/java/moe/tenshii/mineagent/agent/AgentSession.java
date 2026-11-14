package moe.tenshii.mineagent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import moe.tenshii.mineagent.MineAgentClient;
import moe.tenshii.mineagent.agent.llm.ApiFailure;
import moe.tenshii.mineagent.agent.llm.ChatBackend;
import moe.tenshii.mineagent.agent.llm.Reply;
import moe.tenshii.mineagent.agent.llm.ToolCall;
import moe.tenshii.mineagent.agent.llm.ToolResult;
import moe.tenshii.mineagent.agent.llm.ToolSpec;
import moe.tenshii.mineagent.game.DamageTracker;
import moe.tenshii.mineagent.game.GameThread;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.TaskRunner;
import moe.tenshii.mineagent.game.WorldInfo;
import net.minecraft.client.Minecraft;

/**
 * One goal's agent loop: send the conversation to the model, run the requested tools in the game, repeat until done.
 * Runs on its own thread.
 */
final class AgentSession implements Runnable {
	interface BackendFactory {
		ChatBackend create(String system, List<ToolSpec> tools) throws ApiFailure;
	}

	private final String goal;
	private final int maxTurns;
	private final BackendFactory factory;
	private final AgentController controller;
	private final Tools tools;
	private final Queue<String> guidance = new ConcurrentLinkedQueue<>();
	private volatile boolean cancelled;
	private volatile ChatBackend backend;

	AgentSession(String goal, int maxTurns, BackendFactory factory, AgentController controller) {
		this.goal = goal;
		this.tools = new Tools();
		this.maxTurns = maxTurns;
		this.factory = factory;
		this.controller = controller;
	}

	String goal() {
		return goal;
	}

	void cancel() {
		cancelled = true;
		ChatBackend active = backend;
		if (active != null) {
			active.cancel();
		}
	}

	boolean isCancelled() {
		return cancelled;
	}

	void addGuidance(String text) {
		guidance.add("Private message from the person you work for (answer in your reply, which only they see): " + text);
	}

	/** Something happened in the game that the model did not cause and should know about. */
	void addGameEvent(String text) {
		guidance.add("Game event: " + text);
	}

	@Override
	public void run() {
		try (ChatBackend created = factory.create(SystemPrompt.TEXT, tools.specs())) {
			backend = created;
			if (cancelled) {
				created.cancel();
			}
			loop(created);
		} catch (ApiFailure failure) {
			controller.finished(this, failure.getMessage(), true);
		} catch (CancellationException e) {
			controller.finished(this, "Stopped.", false);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			controller.finished(this, "Stopped.", false);
		} catch (RuntimeException e) {
			MineAgentClient.LOGGER.error("Agent session crashed", e);
			controller.finished(this, "Agent crashed: " + e, true);
		}
	}

	private void loop(ChatBackend chat) throws ApiFailure, InterruptedException {
		String initialState;
		try {
			initialState = GameThread.call(() -> WorldInfo.observe(Minecraft.getInstance()));
		} catch (TaskFailure failure) {
			controller.finished(this, "Could not read the game state: " + failure.getMessage(), true);
			return;
		}
		chat.addUserText("Goal from the player: " + goal + "\n\nCurrent state (from observe):\n" + initialState);

		long inputTokens = 0;
		long outputTokens = 0;
		for (int turn = 1; turn <= maxTurns; turn++) {
			if (cancelled) {
				throw new CancellationException();
			}
			controller.setPhase("thinking (turn " + turn + ")");
			Reply reply = chat.next();
			inputTokens += reply.inputTokens();
			outputTokens += reply.outputTokens();
			controller.setUsage(inputTokens, outputTokens);
			reply.texts().stream().filter(text -> !text.isBlank()).forEach(text -> controller.say(text.strip()));

			switch (reply.stop()) {
				case REFUSED -> {
					controller.finished(this, "The model declined to continue this goal (" + reply.refusalDetail() + ").", true);
					return;
				}
				case CONTEXT_FULL -> {
					controller.finished(this, "The conversation grew past the model's context window. Start a new goal to continue.", true);
					return;
				}
				case PAUSED -> {
					continue;
				}
				default -> {
				}
			}

			boolean truncated = reply.stop() == Reply.Stop.TRUNCATED;
			List<ToolResult> results = new ArrayList<>();
			for (ToolCall call : reply.toolCalls()) {
				results.add(truncated
					? new ToolResult(call.id(), "Not run: your response hit the output token limit. Issue the call again, more concisely.", true)
					: runTool(call));
				if (cancelled) {
					throw new CancellationException();
				}
			}
			List<String> extra = new ArrayList<>();
			for (String note = guidance.poll(); note != null; note = guidance.poll()) {
				extra.add(note);
			}
			if (truncated && reply.toolCalls().isEmpty()) {
				extra.add("Your response was cut off by the output token limit. Continue, more concisely.");
			}
			if (results.isEmpty() && extra.isEmpty()) {
				controller.finished(this, null, false);
				return;
			}
			chat.addToolResults(results, extra);
		}
		controller.finished(this, "Reached the limit of " + maxTurns + " turns for one goal (change maxTurns in config/mineagent.json).", true);
	}

	/**
	 * Appends the damage taken during the action and a warning about health, hunger, air, fire or harmful effects,
	 * so the model notices them mid-goal.
	 */
	private static String withStatus(String result) {
		try {
			String status = GameThread.call(() -> {
				Minecraft mc = Minecraft.getInstance();
				String damage = DamageTracker.end(mc);
				String alerts = WorldInfo.alerts(mc);
				if (alerts != null && result.contains(alerts)) {
					alerts = null;
				}
				if (damage == null) {
					return alerts;
				}
				return alerts == null ? damage : damage + "\n" + alerts;
			});
			return status == null ? result : result + "\n" + status;
		} catch (TaskFailure | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return result;
		}
	}

	private static void startDamageTracking() {
		try {
			GameThread.call(() -> {
				DamageTracker.begin(Minecraft.getInstance());
				return null;
			});
		} catch (TaskFailure | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private ToolResult runTool(ToolCall call) {
		controller.setPhase(call.name());
		if (call.input() == null) {
			return new ToolResult(call.id(), "Invalid input: the arguments must be a JSON object matching the tool's schema.", true);
		}
		startDamageTracking();
		try {
			String result = tools.execute(call.name(), call.input(), new Tools.Context() {
				@Override
				public String runTask(Task task) throws TaskFailure, InterruptedException {
					return TaskRunner.run(task, AgentSession.this::isCancelled);
				}

				@Override
				public <T> T onGame(Supplier<T> action) throws TaskFailure, InterruptedException {
					return GameThread.call(action);
				}
			});
			MineAgentClient.LOGGER.debug("Tool {} -> {}", call.name(), result);
			return new ToolResult(call.id(), withStatus(result), false);
		} catch (TaskFailure failure) {
			MineAgentClient.LOGGER.debug("Tool {} failed: {}", call.name(), failure.getMessage());
			return new ToolResult(call.id(), withStatus(failure.getMessage()), true);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new ToolResult(call.id(), "Interrupted.", true);
		} catch (IllegalArgumentException e) {
			return new ToolResult(call.id(), "Invalid input: " + e.getMessage(), true);
		}
	}
}
