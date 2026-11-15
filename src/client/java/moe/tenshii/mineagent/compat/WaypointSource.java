package moe.tenshii.mineagent.compat;

import java.util.List;
import moe.tenshii.mineagent.game.TaskFailure;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Waypoints saved by a map mod. Called on the game thread; implementations touch the mod's classes, so they are only
 * created when that mod is loaded.
 */
public interface WaypointSource {
	record MapWaypoint(String name, BlockPos pos, @Nullable String note) {
	}

	/** The map mod's display name, e.g. "JourneyMap". */
	String mod();

	/** The waypoints for the dimension the player is in. */
	List<MapWaypoint> list(Minecraft mc) throws TaskFailure;

	/** Saves a new waypoint in the player's dimension, as the player would from the map's own screen. */
	void add(Minecraft mc, String name, BlockPos pos) throws TaskFailure;
}
