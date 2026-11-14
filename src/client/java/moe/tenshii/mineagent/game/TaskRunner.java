package moe.tenshii.mineagent.game;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import moe.tenshii.mineagent.MineAgentClient;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Owns the one task that may drive the player at a time and ticks it from the client tick loop.
 */
public final class TaskRunner {
	private static @Nullable Running current;

	private record Running(Task task, CompletableFuture<String> result, int[] ticks) {
	}

	private TaskRunner() {
	}

	/**
	 * Schedules a task and blocks the calling (agent) thread until it ends.
	 *
	 * @param cancelled polled while waiting; when it returns true the task is stopped
	 */
	public static String run(Task task, BooleanSupplier cancelled) throws TaskFailure, InterruptedException {
		CompletableFuture<String> result = new CompletableFuture<>();
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> start(mc, task, result));
		while (true) {
			if (cancelled.getAsBoolean()) {
				mc.execute(() -> cancel(mc, "Stopped by the user"));
				throw new TaskFailure("Stopped by the user");
			}
			try {
				return result.get(100, TimeUnit.MILLISECONDS);
			} catch (TimeoutException ignored) {
				// Keep polling so a stop request is noticed promptly.
			} catch (ExecutionException e) {
				if (e.getCause() instanceof TaskFailure failure) {
					throw failure;
				}
				throw new TaskFailure("Internal error: " + e.getCause());
			}
		}
	}

	private static void start(Minecraft mc, Task task, CompletableFuture<String> result) {
		cancel(mc, "Replaced by a new action");
		current = new Running(task, result, new int[1]);
	}

	public static @Nullable String currentDescription() {
		Running running = current;
		return running == null ? null : running.task().describe();
	}

	public static void cancel(Minecraft mc, String reason) {
		Running running = current;
		if (running == null) {
			return;
		}
		current = null;
		finish(mc, running);
		running.result().completeExceptionally(new TaskFailure(reason));
	}

	public static void tick(Minecraft mc) {
		if (PlayerControl.inControl() && mc.player != null) {
			// Respawning creates a new player with keyboard input; keep the agent in control of it.
			PlayerControl.takeControl(mc);
		}
		Running running = current;
		if (mc.isPaused()) {
			return;
		}
		if (running == null) {
			if (PlayerControl.inControl() && mc.player != null) {
				AirReflex.tickIdle(mc);
			}
			return;
		}
		if (mc.player == null || mc.level == null || mc.gameMode == null) {
			cancel(mc, "Not in a world");
			return;
		}
		if (!mc.player.isAlive()) {
			cancel(mc, "The player died");
			return;
		}
		AirReflex.Outcome air = AirReflex.tickDuringAction(mc);
		if (air == AirReflex.Outcome.GAVE_UP) {
			cancel(mc, "Ran out of air: could not reach the surface while " + running.task().describe());
			return;
		}
		if (air == AirReflex.Outcome.SURFACING) {
			// Time spent surfacing does not count against the action's own time limit.
			return;
		}
		if (++running.ticks()[0] > running.task().timeoutTicks()) {
			cancel(mc, "Timed out after " + running.task().timeoutTicks() / 20 + " seconds while " + running.task().describe());
			return;
		}
		String outcome;
		try {
			outcome = running.task().tick(mc);
		} catch (TaskFailure failure) {
			current = null;
			finish(mc, running);
			running.result().completeExceptionally(failure);
			return;
		} catch (RuntimeException e) {
			MineAgentClient.LOGGER.error("Task '{}' crashed", running.task().describe(), e);
			current = null;
			finish(mc, running);
			running.result().completeExceptionally(new TaskFailure("Internal error: " + e));
			return;
		}
		if (outcome != null) {
			current = null;
			finish(mc, running);
			running.result().complete(outcome);
		}
	}

	private static void finish(Minecraft mc, Running running) {
		running.task().stop(mc);
		PlayerControl.idle(mc);
	}
}
