package moe.tenshii.mineagent.compat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import moe.tenshii.mineagent.game.TaskFailure;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

/**
 * Xaero's Minimap waypoints through its internal classes, since it has no public API. Xaero's keeps one waypoint
 * world per dimension, so the current world holds exactly the player's dimension.
 */
final class XaeroWaypoints implements WaypointSource {
	@Override
	public String mod() {
		return "Xaero's Minimap";
	}

	@Override
	public List<MapWaypoint> list(Minecraft mc) throws TaskFailure {
		MinimapWorld world = world(session());
		List<MapWaypoint> result = new ArrayList<>();
		for (WaypointSet set : world.getIterableWaypointSets()) {
			for (Waypoint waypoint : set.getWaypoints()) {
				result.add(new MapWaypoint(waypoint.getLocalizedName(), new BlockPos(waypoint.getX(), waypoint.getY(), waypoint.getZ()),
					note(set, waypoint)));
			}
		}
		return result;
	}

	@Override
	public void add(Minecraft mc, String name, BlockPos pos) throws TaskFailure {
		MinimapSession session = session();
		MinimapWorld world = world(session);
		world.getCurrentWaypointSet().add(new Waypoint(pos.getX(), pos.getY(), pos.getZ(), name, initials(name), WaypointColor.GOLD));
		try {
			session.getWorldManagerIO().saveWorld(world);
		} catch (IOException e) {
			throw new TaskFailure("Added the waypoint to Xaero's Minimap, but saving it to disk failed (" + e.getMessage()
				+ "); it lasts until the game closes.");
		}
	}

	private static MinimapSession session() throws TaskFailure {
		MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
		if (session == null) {
			throw new TaskFailure("Xaero's Minimap has not loaded this world's waypoints yet; try again in a moment.");
		}
		return session;
	}

	private static MinimapWorld world(MinimapSession session) throws TaskFailure {
		MinimapWorld world = session.getWorldManager().getCurrentWorld();
		if (world == null) {
			throw new TaskFailure("Xaero's Minimap has not loaded this world's waypoints yet; try again in a moment.");
		}
		return world;
	}

	private static @Nullable String note(WaypointSet set, Waypoint waypoint) {
		List<String> notes = new ArrayList<>();
		if (!set.getName().equals(MinimapWorld.DEFAULT_SET)) {
			notes.add("set " + set.getName());
		}
		if (waypoint.getPurpose() == WaypointPurpose.DEATH || waypoint.getPurpose() == WaypointPurpose.OLD_DEATH) {
			notes.add("death point");
		}
		if (waypoint.isTemporary()) {
			notes.add("temporary");
		}
		if (waypoint.isDisabled()) {
			notes.add("hidden");
		}
		if (!waypoint.isYIncluded()) {
			notes.add("height unknown");
		}
		return notes.isEmpty() ? null : String.join(", ", notes);
	}

	// Xaero's shows up to two characters on the waypoint marker.
	private static String initials(String name) {
		String letters = name.strip();
		return letters.isEmpty() ? "A" : letters.substring(0, Math.min(2, letters.length())).toUpperCase(Locale.ROOT);
	}
}
