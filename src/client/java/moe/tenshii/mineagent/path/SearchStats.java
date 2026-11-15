package moe.tenshii.mineagent.path;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import moe.tenshii.mineagent.MineAgentClient;
import org.jspecify.annotations.Nullable;

/**
 * What path searches cost on the client thread, logged at debug level and read by tests. Client thread only.
 */
public final class SearchStats {
	private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
	private static int searches;
	private static long totalNanos;
	private static long longestSliceNanos;
	private static long longestSliceCpuNanos;
	private static long longestSearchNanos;

	private SearchStats() {
	}

	static void slice(long nanos, long cpuNanos) {
		totalNanos += nanos;
		longestSliceNanos = Math.max(longestSliceNanos, nanos);
		longestSliceCpuNanos = Math.max(longestSliceCpuNanos, cpuNanos);
	}

	/** CPU time used by the current thread, which unlike wall time leaves out GC pauses and the thread being descheduled. */
	static long threadCpuNanos() {
		return THREADS.getCurrentThreadCpuTime();
	}

	static void finished(String kind, int expanded, int slices, long nanos, @Nullable Path result) {
		searches++;
		longestSearchNanos = Math.max(longestSearchNanos, nanos);
		MineAgentClient.LOGGER.debug("{} expanded {} nodes in {} over {} ticks: {}", kind, expanded, millis(nanos), slices,
			result == null ? "no path" : (result.complete() ? "complete, " : "partial, ") + result.steps().size() + " steps");
	}

	public static void reset() {
		searches = 0;
		totalNanos = 0;
		longestSliceNanos = 0;
		longestSliceCpuNanos = 0;
		longestSearchNanos = 0;
	}

	/** The most CPU time any search used in a single tick. */
	public static long longestSliceCpuNanos() {
		return longestSliceCpuNanos;
	}

	public static String summary() {
		return String.format(Locale.ROOT, "%d searches, %s in total, longest search %s, longest tick %s (%s of CPU)",
			searches, millis(totalNanos), millis(longestSearchNanos), millis(longestSliceNanos), millis(longestSliceCpuNanos));
	}

	private static String millis(long nanos) {
		return String.format(Locale.ROOT, "%.1f ms", nanos / 1.0E6);
	}
}
