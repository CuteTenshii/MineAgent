package moe.tenshii.mineagent.compat;

import java.util.ArrayList;
import java.util.List;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.common.JourneyMapPlugin;
import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointFactory;
import moe.tenshii.mineagent.game.TaskFailure;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * JourneyMap waypoints through its public client API. JourneyMap creates this class from the "journeymap"
 * entrypoint and hands it the API; MineAgent creates another instance to use that API.
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
public final class JourneyMapWaypoints implements IClientPlugin, WaypointSource {
	private static volatile IClientAPI api;

	@Override
	public String getModId() {
		return "mineagent";
	}

	@Override
	public void initialize(IClientAPI clientApi) {
		api = clientApi;
	}

	@Override
	public String mod() {
		return "JourneyMap";
	}

	@Override
	public List<MapWaypoint> list(Minecraft mc) throws TaskFailure {
		List<MapWaypoint> result = new ArrayList<>();
		for (Waypoint waypoint : api().getAllWaypoints(mc.level.dimension())) {
			result.add(new MapWaypoint(waypoint.getName(), waypoint.getBlockPos(), waypoint.isEnabled() ? null : "hidden"));
		}
		return result;
	}

	@Override
	public void add(Minecraft mc, String name, BlockPos pos) throws TaskFailure {
		api().addWaypoint(getModId(), WaypointFactory.createWaypoint(getModId(), pos, name, mc.level.dimension(), true));
	}

	private static IClientAPI api() throws TaskFailure {
		IClientAPI current = api;
		if (current == null) {
			throw new TaskFailure("JourneyMap has not finished starting, so its waypoints are not available yet.");
		}
		return current;
	}
}
