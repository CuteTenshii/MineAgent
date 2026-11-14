package moe.tenshii.mineagent.path;

import java.util.List;

/**
 * The path the agent is currently following, published by the walker for the in-world overlay. Client thread only.
 */
public final class PathDisplay {
	private static List<PathStep> steps = List.of();
	private static int index;

	private PathDisplay() {
	}

	public static void show(List<PathStep> path, int nextStep) {
		steps = path;
		index = nextStep;
	}

	public static void clear() {
		steps = List.of();
		index = 0;
	}

	/** Steps not yet completed, in order. */
	public static List<PathStep> remaining() {
		return index >= steps.size() ? List.of() : steps.subList(index, steps.size());
	}
}
