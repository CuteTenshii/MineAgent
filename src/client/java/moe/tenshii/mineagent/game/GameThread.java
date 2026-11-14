package moe.tenshii.mineagent.game;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

/**
 * Runs short snippets on the client thread from the agent thread and waits for the result.
 */
public final class GameThread {
	private static final long TIMEOUT_SECONDS = 20;

	private GameThread() {
	}

	public static <T> T call(Supplier<T> action) throws TaskFailure, InterruptedException {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread()) {
			return action.get();
		}
		try {
			return mc.submit(action).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof GameStateException state) {
				throw new TaskFailure(state.getMessage());
			}
			throw new TaskFailure("Internal error: " + cause);
		} catch (TimeoutException e) {
			throw new TaskFailure("The game did not respond in time (is it frozen?)");
		}
	}

	/** Unchecked failure for use inside {@link #call} lambdas. */
	public static final class GameStateException extends RuntimeException {
		public GameStateException(String message) {
			super(message, null, false, false);
		}
	}
}
