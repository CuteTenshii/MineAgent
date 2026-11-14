package moe.tenshii.mineagent.game;

import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * A multi-tick action driven from the client tick loop. Everything here runs on the client thread.
 */
public interface Task {
	/**
	 * Advances the task by one tick.
	 *
	 * @return {@code null} while the task is still running, or a description of the outcome when it finished
	 * @throws TaskFailure when the task cannot complete
	 */
	@Nullable String tick(Minecraft mc) throws TaskFailure;

	/** Releases any controls the task holds. Called once when the task ends for any reason. */
	default void stop(Minecraft mc) {
	}

	/** Upper bound on how long the task may run, in ticks. */
	int timeoutTicks();

	/** Short present-tense description for the HUD, e.g. "mining stone". */
	String describe();
}
