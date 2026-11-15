package moe.tenshii.mineagent.path;

import org.jspecify.annotations.Nullable;

/**
 * An A* search that runs a slice at a time on the client thread. Searches read the level, which is not safe off that
 * thread, so a long one is spread over ticks instead of stalling a single frame.
 */
public abstract class PathSearch {
	/** Client-thread time a search may use per tick. */
	public static final long TICK_BUDGET_NANOS = 4_000_000L;
	private static final int NODES_PER_CLOCK_CHECK = 16;

	private final String kind;
	private final int maxNodes;
	private boolean done;
	private @Nullable Path result;
	private int expanded;
	private int slices;
	private long nanos;

	protected PathSearch(String kind, int maxNodes) {
		this.kind = kind;
		this.maxNodes = maxNodes;
	}

	/**
	 * Runs this tick's share of the search.
	 *
	 * @return true once the search has ended and {@link #result()} is final
	 */
	public final boolean advance() {
		if (done) {
			return true;
		}
		long started = System.nanoTime();
		long cpuStarted = SearchStats.threadCpuNanos();
		long deadline = started + TICK_BUDGET_NANOS;
		try {
			while (true) {
				for (int i = 0; i < NODES_PER_CLOCK_CHECK; i++) {
					if (expanded >= maxNodes || !expandNext()) {
						result = finish();
						done = true;
						return true;
					}
					expanded++;
				}
				if (System.nanoTime() >= deadline) {
					return false;
				}
			}
		} finally {
			long spent = System.nanoTime() - started;
			nanos += spent;
			slices++;
			SearchStats.slice(spent, SearchStats.threadCpuNanos() - cpuStarted);
			if (done) {
				SearchStats.finished(kind, expanded, slices, nanos, result);
			}
		}
	}

	/**
	 * The path found, partial when the search ran out of nodes or loaded chunks, or null when no cell closer to the
	 * goal can be reached. Only meaningful once {@link #advance()} has returned true.
	 */
	public final @Nullable Path result() {
		return result;
	}

	/**
	 * Expands the next open node.
	 *
	 * @return false when the search is over: the goal was reached or nothing is left to expand
	 */
	protected abstract boolean expandNext();

	/** Builds the result once {@link #expandNext()} has returned false or the node limit was hit. */
	protected abstract @Nullable Path finish();
}
