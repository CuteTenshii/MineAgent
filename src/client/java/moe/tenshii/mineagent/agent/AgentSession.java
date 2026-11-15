package moe.tenshii.mineagent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import moe.tenshii.mineagent.MineAgentClient;
import moe.tenshii.mineagent.agent.llm.ApiFailure;
import moe.tenshii.mineagent.agent.llm.ChatBackend;
import moe.tenshii.mineagent.agent.llm.Reply;
import moe.tenshii.mineagent.agent.llm.ToolCall;
import moe.tenshii.mineagent.agent.llm.ToolResult;
import moe.tenshii.mineagent.agent.llm.ToolSpec;
import moe.tenshii.mineagent.compat.Waypoints;
import moe.tenshii.mineagent.config.AgentConfig;
import moe.tenshii.mineagent.game.ContentMods;
import moe.tenshii.mineagent.game.DamageTracker;
import moe.tenshii.mineagent.game.GameThread;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.TaskRunner;
import moe.tenshii.mineagent.game.WorldInfo;
import moe.tenshii.mineagent.game.task.TeleportTask;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

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
	private final AtomicInteger turnLimit;
	private volatile boolean waitingForTurns;
	private final BackendFactory factory;
	private final AgentController controller;
	private final VisionPolicy vision;
	private volatile Tools tools;
	private static final String IMAGES_REJECTED =
		"[Screenshot removed: this model does not accept images, so the look tool is unavailable for the rest of this goal.]";

	private final Queue<String> guidance = new ConcurrentLinkedQueue<>();
	private final BlockingQueue<String> answers = new LinkedBlockingQueue<>();
	private volatile @Nullable List<String> askedOptions;
	private volatile boolean cancelled;
	private volatile ChatBackend backend;

	AgentSession(String goal, int maxTurns, BackendFactory factory, VisionPolicy vision, AgentController controller) {
		this.goal = goal;
		this.vision = vision;
		this.maxTurns = maxTurns;
		this.turnLimit = new AtomicInteger(maxTurns);
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

	boolean isWaitingForTurns() {
		return waitingForTurns;
	}

	/** Raises the turn limit; a session paused at the limit resumes. */
	void grantTurns(int extra) {
		turnLimit.addAndGet(extra);
	}

	boolean isAsking() {
		return askedOptions != null;
	}

	/**
	 * Answers the question the model is waiting on.
	 *
	 * @return false when no question is waiting
	 */
	boolean answer(String text) {
		if (askedOptions == null) {
			return false;
		}
		answers.add(text.strip());
		return true;
	}

	/** Shows the question and blocks the session until the player answers or the goal is stopped. */
	private String ask(String question, List<String> options) throws InterruptedException {
		answers.clear();
		askedOptions = options;
		controller.onQuestion(this, question, options);
		try {
			while (true) {
				if (cancelled) {
					throw new CancellationException();
				}
				String reply = answers.poll(200, TimeUnit.MILLISECONDS);
				if (reply != null) {
					return describeAnswer(options, reply);
				}
			}
		} finally {
			askedOptions = null;
			controller.onResumed(this);
		}
	}

	/** A number or an option's exact text picks that option; anything else is the player's own answer. */
	static String describeAnswer(List<String> options, String reply) {
		for (int i = 0; i < options.size(); i++) {
			if (reply.equals(Integer.toString(i + 1)) || reply.equalsIgnoreCase(options.get(i))) {
				return "The player chose option " + (i + 1) + ": " + options.get(i);
			}
		}
		return "The player answered: " + reply;
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
		boolean offerLook;
		boolean offerTeleport;
		try {
			offerLook = vision.offerLook(note -> AgentController.notify(Component.literal(note).withStyle(ChatFormatting.GRAY)));
			offerTeleport = GameThread.call(() -> TeleportTask.command(Minecraft.getInstance().player) != null);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			controller.finished(this, "Stopped.", false);
			return;
		} catch (TaskFailure notInWorld) {
			controller.finished(this, notInWorld.getMessage(), false);
			return;
		}
		tools = new Tools(AgentConfig.get().allowCommands, offerLook, offerTeleport, Waypoints.available());
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
		String mods = ContentMods.describe();
		chat.addUserText("Goal from the player: " + goal + "\n\nCurrent state (from observe):\n" + initialState
			+ (mods == null ? "" : "\n\nInstalled mods adding content (namespace, name, what it adds): " + mods));

		long inputTokens = 0;
		long outputTokens = 0;
		for (int turn = 1; ; turn++) {
			if (cancelled) {
				throw new CancellationException();
			}
			if (turn > turnLimit.get()) {
				awaitMoreTurns(turn - 1);
			}
			controller.setPhase("thinking (turn " + turn + ")");
			Reply reply = nextReply(chat);
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
	}

	/**
	 * Asks the model for its next reply. A model that cannot read images rejects any request carrying a screenshot,
	 * so on such an error the screenshots are swapped for a note, look is turned off and the request is sent again.
	 */
	private Reply nextReply(ChatBackend chat) throws ApiFailure, InterruptedException {
		try {
			return chat.next();
		} catch (ApiFailure failure) {
			if (!failure.rejectedContent() || !chat.replaceImages(IMAGES_REJECTED)) {
				throw failure;
			}
			MineAgentClient.LOGGER.info("Request with screenshots rejected, retrying without them: {}", failure.getMessage());
			tools.disableLook();
			vision.imagesRejected();
			AgentController.notify(Component.literal("This model does not accept images, so screenshots (look) are off for it from now on. "
				+ "/agent vision on tries again.").withStyle(ChatFormatting.GRAY));
			return chat.next();
		}
	}

	/**
	 * Pauses at the turn limit, keeping the conversation, until the player allows more turns or stops the goal.
	 */
	private void awaitMoreTurns(int turnsUsed) throws InterruptedException {
		waitingForTurns = true;
		controller.onTurnLimit(this, turnsUsed, maxTurns);
		try {
			while (turnsUsed >= turnLimit.get()) {
				if (cancelled) {
					throw new CancellationException();
				}
				Thread.sleep(200);
			}
		} finally {
			waitingForTurns = false;
		}
		controller.onResumed(this);
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
		controller.toolStarted(call.name(), call.input());
		long started = System.nanoTime();
		ToolResult result = executeTool(call);
		long millis = (System.nanoTime() - started) / 1_000_000;
		controller.toolFinished(result.content(), result.error(), millis);
		MineAgentClient.LOGGER.info("Tool {} {} -> {} in {} ms", call.name(), call.input(), result.error() ? "error" : "ok", millis);
		return result;
	}

	private ToolResult executeTool(ToolCall call) {
		if (call.input() == null) {
			return new ToolResult(call.id(), "Invalid input: the arguments must be a JSON object matching the tool's schema.", true);
		}
		startDamageTracking();
		ToolResult.Image[] image = new ToolResult.Image[1];
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

				@Override
				public void attachImage(ToolResult.Image attached) {
					image[0] = attached;
				}

				@Override
				public String askPlayer(String question, List<String> options) throws InterruptedException {
					return ask(question, options);
				}
			});
			return new ToolResult(call.id(), withStatus(result), false, image[0]);
		} catch (TaskFailure failure) {
			return new ToolResult(call.id(), withStatus(failure.getMessage()), true);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new ToolResult(call.id(), "Interrupted.", true);
		} catch (IllegalArgumentException e) {
			return new ToolResult(call.id(), "Invalid input: " + e.getMessage(), true);
		}
	}
}
