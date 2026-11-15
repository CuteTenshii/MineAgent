package moe.tenshii.mineagent.game;

import java.util.ArrayDeque;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.TickRateManager;
import org.jspecify.annotations.Nullable;

/**
 * The server's ticks per second. Exact in singleplayer, from the integrated server's own tick timing. On a remote
 * server the client cannot read it, so it is estimated from the world time the server sends about once a second:
 * game ticks passed over real time passed.
 */
public final class ServerTps {
	// Long enough to smooth out network jitter, short enough to notice lag that started half a minute ago.
	private static final long WINDOW_NANOS = 15_000_000_000L;
	// Below this much real time the estimate is mostly jitter.
	private static final long MIN_SPAN_NANOS = 3_000_000_000L;
	private static final double LOW_FRACTION = 0.75;

	private record Sample(long gameTime, long nanos) {
	}

	private static final ArrayDeque<Sample> SAMPLES = new ArrayDeque<>();

	private ServerTps() {
	}

	/** Called on the client thread for every world time update from the server. */
	public static void onTimeUpdate(long gameTime) {
		record(gameTime, System.nanoTime());
	}

	/** Adds a time update received at {@code nanos}; public so the estimate can be tested without a remote server. */
	public static synchronized void record(long gameTime, long nanos) {
		Sample last = SAMPLES.peekLast();
		// Time going backwards means another world or server: earlier samples no longer apply.
		if (last != null && (gameTime < last.gameTime() || nanos < last.nanos())) {
			SAMPLES.clear();
		}
		SAMPLES.addLast(new Sample(gameTime, nanos));
		while (SAMPLES.size() > 2 && nanos - SAMPLES.peekFirst().nanos() > WINDOW_NANOS) {
			SAMPLES.removeFirst();
		}
	}

	public static synchronized void reset() {
		SAMPLES.clear();
	}

	/** Estimated TPS from the recorded time updates, or null until they span a few seconds. */
	public static synchronized @Nullable Double estimate() {
		Sample first = SAMPLES.peekFirst();
		Sample last = SAMPLES.peekLast();
		if (first == null || last.nanos() - first.nanos() < MIN_SPAN_NANOS) {
			return null;
		}
		return (last.gameTime() - first.gameTime()) * 1e9 / (last.nanos() - first.nanos());
	}

	/** The current TPS, or null when it is not known yet. */
	public static @Nullable Double current(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			long tickNanos = server.getAverageTickTimeNanos();
			float target = server.tickRateManager().tickrate();
			return tickNanos <= 0 ? target : Math.min(target, 1e9 / tickNanos);
		}
		return estimate();
	}

	/**
	 * "server TPS 19.9/20", marked as estimated on a remote server, with a note when ticking is frozen or the rate is
	 * not known yet.
	 */
	public static String describe(Minecraft mc) {
		TickRateManager rates = mc.level.tickRateManager();
		if (rates.isFrozen()) {
			return "server ticking frozen (/tick freeze): nothing in the world moves";
		}
		Double tps = current(mc);
		if (tps == null) {
			return "server TPS not measured yet";
		}
		return String.format(Locale.ROOT, "server TPS %.1f/%.0f%s", tps, rates.tickrate(), estimated(mc) ? " (estimated)" : "");
	}

	/** Whether the rate is estimated from time updates, which only happens on a remote server. */
	private static boolean estimated(Minecraft mc) {
		return mc.getSingleplayerServer() == null;
	}

	/** A warning when the server runs well below its target rate, or null. */
	public static @Nullable String lagWarning(Minecraft mc) {
		Double tps = current(mc);
		float target = mc.level.tickRateManager().tickrate();
		if (tps == null || mc.level.tickRateManager().isFrozen() || tps >= target * LOW_FRACTION) {
			return null;
		}
		return String.format(Locale.ROOT, "server lagging at %.1f/%.0f TPS%s, so actions, furnaces and mobs run slower", tps, target,
			estimated(mc) ? " (estimated)" : "");
	}
}
