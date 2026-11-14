package moe.tenshii.mineagent.game;

import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Holds an optional child task so a parent can delegate ticks to it until it finishes.
 */
public final class SubTask {
	private @Nullable Task child;
	private @Nullable String lastResult;

	public void set(Minecraft mc, Task task) {
		clear(mc);
		child = task;
		lastResult = null;
	}

	public boolean active() {
		return child != null;
	}

	/**
	 * Ticks the child.
	 *
	 * @return true while the child is still running
	 */
	public boolean tick(Minecraft mc) throws TaskFailure {
		if (child == null) {
			return false;
		}
		String result;
		try {
			result = child.tick(mc);
		} catch (TaskFailure failure) {
			clear(mc);
			throw failure;
		}
		if (result == null) {
			return true;
		}
		lastResult = result;
		clear(mc);
		return false;
	}

	public @Nullable String lastResult() {
		return lastResult;
	}

	public @Nullable String describe() {
		return child == null ? null : child.describe();
	}

	public void clear(Minecraft mc) {
		if (child != null) {
			Task old = child;
			child = null;
			old.stop(mc);
		}
	}
}
