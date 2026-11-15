package moe.tenshii.mineagent.compat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import moe.tenshii.mineagent.MineAgentClient;
import moe.tenshii.mineagent.game.TaskFailure;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * The waypoint tool's backend: lists and adds waypoints in whichever map mods are installed.
 */
public final class Waypoints {
	private static final int MAX_LISTED = 50;
	private static final int MAX_NAME_LENGTH = 32;
	private static @Nullable List<WaypointSource> sources;

	private Waypoints() {
	}

	/** The installed map mods MineAgent can read waypoints from, in a fixed order. */
	public static synchronized List<WaypointSource> sources() {
		if (sources == null) {
			List<WaypointSource> found = new ArrayList<>();
			// Lambdas, not constructor references: a reference loads the class, and with it the mod's API, as soon as it is evaluated.
			load(found, "xaerominimap", () -> new XaeroWaypoints());
			load(found, "journeymap", () -> new JourneyMapWaypoints());
			sources = List.copyOf(found);
		}
		return sources;
	}

	public static boolean available() {
		return !sources().isEmpty();
	}

	// The adapter class is only touched once its mod is known to be loaded, and a version whose classes moved is skipped.
	private static void load(List<WaypointSource> found, String modId, Supplier<WaypointSource> adapter) {
		if (!FabricLoader.getInstance().isModLoaded(modId)) {
			return;
		}
		try {
			found.add(adapter.get());
		} catch (LinkageError e) {
			MineAgentClient.LOGGER.warn("Waypoints from {} are unavailable with this version of it", modId, e);
		}
	}

	/** Waypoints in the player's dimension from every map mod, nearest first, optionally only names containing {@code filter}. */
	public static String list(Minecraft mc, @Nullable String filter) throws TaskFailure {
		BlockPos here = mc.player.blockPosition();
		String needle = filter == null ? null : filter.strip().toLowerCase(Locale.ROOT);
		StringBuilder out = new StringBuilder();
		for (WaypointSource source : sources()) {
			List<WaypointSource.MapWaypoint> waypoints;
			try {
				waypoints = new ArrayList<>(source.list(mc));
			} catch (LinkageError e) {
				out.append(unavailable(source, e)).append('\n');
				continue;
			} catch (TaskFailure e) {
				out.append(source.mod()).append(": ").append(e.getMessage()).append('\n');
				continue;
			}
			waypoints.removeIf(waypoint -> needle != null && !waypoint.name().toLowerCase(Locale.ROOT).contains(needle));
			waypoints.sort(Comparator.comparingDouble(waypoint -> waypoint.pos().distSqr(here)));
			out.append(source.mod()).append(": ");
			if (waypoints.isEmpty()) {
				out.append(needle == null ? "no waypoints in this dimension" : "no waypoint named like \"" + filter + "\" in this dimension").append('\n');
				continue;
			}
			out.append(waypoints.size()).append(waypoints.size() == 1 ? " waypoint" : " waypoints").append(" in this dimension, nearest first\n");
			for (WaypointSource.MapWaypoint waypoint : waypoints.subList(0, Math.min(MAX_LISTED, waypoints.size()))) {
				out.append(String.format(Locale.ROOT, "- \"%s\" at %s, %d blocks away%s%n", waypoint.name(), waypoint.pos().toShortString(),
					Math.round(Math.sqrt(waypoint.pos().distSqr(here))), waypoint.note() == null ? "" : " (" + waypoint.note() + ")"));
			}
			if (waypoints.size() > MAX_LISTED) {
				out.append("- and ").append(waypoints.size() - MAX_LISTED).append(" more farther away; pass name to narrow the list\n");
			}
		}
		return out.toString().strip();
	}

	/** Adds the waypoint to every installed map mod, so it shows on whichever map the player uses. */
	public static String add(Minecraft mc, String name, BlockPos pos) throws TaskFailure {
		String label = name.strip();
		if (label.isEmpty() || label.length() > MAX_NAME_LENGTH) {
			throw new TaskFailure("The waypoint name must be 1 to " + MAX_NAME_LENGTH + " characters.");
		}
		List<String> added = new ArrayList<>();
		List<String> failed = new ArrayList<>();
		for (WaypointSource source : sources()) {
			try {
				source.add(mc, label, pos);
				added.add(source.mod());
			} catch (LinkageError e) {
				failed.add(unavailable(source, e));
			} catch (TaskFailure e) {
				failed.add(source.mod() + ": " + e.getMessage());
			}
		}
		if (added.isEmpty()) {
			throw new TaskFailure("No waypoint was added. " + String.join(" ", failed));
		}
		return "Added waypoint \"" + label + "\" at " + pos.toShortString() + " in " + mc.level.dimension().identifier() + " to "
			+ String.join(" and ", added) + "." + (failed.isEmpty() ? "" : " Not added: " + String.join(" ", failed));
	}

	private static String unavailable(WaypointSource source, LinkageError e) {
		MineAgentClient.LOGGER.warn("{} waypoint access failed", source.mod(), e);
		return source.mod() + ": its waypoints are unavailable with this version of the mod.";
	}
}
