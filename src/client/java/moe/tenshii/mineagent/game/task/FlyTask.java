package moe.tenshii.mineagent.game.task;

import java.util.ArrayList;
import java.util.List;
import moe.tenshii.mineagent.game.AgentInput;
import moe.tenshii.mineagent.game.Flight;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.path.FlightPathfinder;
import moe.tenshii.mineagent.path.Goal;
import moe.tenshii.mineagent.path.MoveType;
import moe.tenshii.mineagent.path.Path;
import moe.tenshii.mineagent.path.PathDisplay;
import moe.tenshii.mineagent.path.PathSearch;
import moe.tenshii.mineagent.path.PathStep;
import moe.tenshii.mineagent.path.PathWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Flies like a creative player: takes off, follows an A* route through open air, and optionally lands.
 */
public final class FlyTask implements Task {
	private static final int MAX_NODES = 60_000;
	private static final int MAX_REPLANS = 60;
	private static final int STUCK_REPLAN_TICKS = 60;
	private static final int LOOKAHEAD = 16;
	// A partial route is extended once this few waypoints remain, so the next leg is found before the player gets there.
	private static final int EXTEND_AHEAD = 24;
	private static final double SWEEP_STEP = 0.3;
	private static final double WAYPOINT_PASSED = 1.2;
	// How far along the route to look for the waypoint nearest the player, so an overshoot skips ahead instead of turning back.
	private static final int PROGRESS_WINDOW = 32;
	// Farther than this from every nearby waypoint, with none of them in sight, the route is re-planned from here.
	private static final double OFF_ROUTE = 3.0;
	// Velocity errors smaller than this, in blocks per tick, are left to drag.
	private static final double STEER_DEADBAND = 0.03;
	// Nearer than this the direction to the aim swings wildly as the player drifts around it, so it holds its yaw and
	// corrects with the strafe keys instead of spinning.
	private static final double TURN_MIN_DISTANCE = 1.0;
	// Feet stay this far above a cell's floor, since touching the ground ends creative flight.
	private static final double HOVER = 0.2;
	// Drag keeps 91% of horizontal speed per tick, so a player still moving at 0.03 coasts about 0.3 blocks further.
	private static final double SETTLED_HORIZONTAL_SPEED = 0.03;
	private static final double SETTLED_VERTICAL_SPEED = 0.08;
	private static final double CRUISE_SPEED = 1.0;
	private static final double SPRINT_ABOVE = 0.55;
	// Flying drag keeps 91% of horizontal speed per tick, so speed is ramped down with distance to avoid overshooting.
	private static final double BRAKE_GAIN = 0.25;
	// Vertical speed keeps 60% per tick, so a coasting player still rises 1.5 times its current speed.
	private static final double VERTICAL_COAST = 1.5;
	// Two jump presses within 7 ticks toggle creative flight off, so presses are spaced further apart.
	private static final int JUMP_REPRESS_TICKS = 9;
	private static final double TAKE_OFF_HEIGHT = 1.5;
	private static final int LANDING_STUCK_TICKS = 40;

	private enum Mode {
		TRAVEL,
		TAKE_OFF,
		LAND
	}

	private enum Phase {
		FLYING,
		LANDING
	}

	private final Mode mode;
	private final @Nullable BlockPos target;
	private final int range;
	private final @Nullable Goal goal;
	private final boolean land;
	private final int timeout;

	private Phase phase = Phase.FLYING;
	private @Nullable List<PathStep> steps;
	private boolean complete;
	private @Nullable PathSearch search;
	private BlockPos searchStart = BlockPos.ZERO;
	private Vec3 holdAt = Vec3.ZERO;
	private boolean extensionFailed;
	private int index;
	private int replans;
	private int stuckTicks;
	private double bestDistance = Double.MAX_VALUE;
	private int lastProgressIndex = -1;
	private long lastTickTime = -1;
	// Written on the client thread each tick; describe() is also called from the agent thread and the HUD.
	private volatile @Nullable BlockPos lastPosition;
	private boolean jumpHeld;
	private long jumpReleasedAt = Long.MIN_VALUE / 2;
	private double takeOffY = Double.NaN;
	private double lowestLandingY = Double.MAX_VALUE;
	private int landingStuckTicks;
	private @Nullable String arrivalNote;

	private FlyTask(Mode mode, @Nullable BlockPos target, int range, boolean land, int timeoutTicks) {
		this.mode = mode;
		this.target = target;
		this.range = range;
		this.goal = target == null ? null : Goal.near(target, range);
		this.land = land;
		this.timeout = timeoutTicks;
	}

	/** Flies until the feet are within {@code range} blocks of {@code target}, then lands if {@code land}. */
	public static FlyTask to(BlockPos target, int range, boolean land, int timeoutTicks) {
		return new FlyTask(Mode.TRAVEL, target.immutable(), range, land, timeoutTicks);
	}

	/** Starts flying and rises a little off the ground, then hovers. */
	public static FlyTask takeOff() {
		return new FlyTask(Mode.TAKE_OFF, null, 0, false, 20 * 10);
	}

	/** Descends straight down until standing on the ground or in water. */
	public static FlyTask landHere() {
		return new FlyTask(Mode.LAND, null, 0, true, 20 * 60);
	}

	@Override
	public @Nullable String tick(Minecraft mc) throws TaskFailure {
		LocalPlayer player = mc.player;
		PlayerControl.takeControl(mc);
		AgentInput input = PlayerControl.input();
		input.clear();
		long now = mc.level.getGameTime();
		if (lastTickTime >= 0 && now - lastTickTime > 1) {
			// A reflex moved the player while this flight was paused, so the old route may start somewhere else.
			steps = null;
			search = null;
		}
		lastTickTime = now;
		lastPosition = player.blockPosition();

		if (mode == Mode.LAND || phase == Phase.LANDING) {
			return tickLanding(mc, player, input, now);
		}
		if (!Flight.canFly(player)) {
			throw Flight.unavailable(mc);
		}
		if (!Flight.isFlying(player)) {
			if (mode == Mode.TRAVEL && player.onGround() && goal.reached(player.blockPosition())) {
				return arrive(mc, player);
			}
			if (Double.isNaN(takeOffY)) {
				takeOffY = player.getY();
			}
			if (player.onGround()) {
				// Vanilla turns flight off on every tick the player touches the ground, so jump first and fly once airborne.
				pressJump(input, now);
				return null;
			}
			Flight.setFlying(player, true);
		}
		if (mode == Mode.TAKE_OFF) {
			if (Double.isNaN(takeOffY) || player.getY() >= takeOffY + TAKE_OFF_HEIGHT || player.isSpectator()) {
				return "Flying, hovering at " + player.blockPosition().toShortString() + ".";
			}
			pressJump(input, now);
			return null;
		}
		return tickTravel(mc, player, input, now);
	}

	private @Nullable String tickTravel(Minecraft mc, LocalPlayer player, AgentInput input, long now) throws TaskFailure {
		BlockPos feet = player.blockPosition();
		if (goal.reached(feet) && settled(player)) {
			return arrive(mc, player);
		}
		if (steps == null && !plan(mc, player)) {
			steer(player, input, holdAt, true, now);
			return null;
		}

		Vec3 position = player.position();
		double offRoute = advanceToNearestWaypoint(position);
		extendRoute(mc, player);
		boolean awaitingExtension = false;
		if (index >= steps.size()) {
			if (!complete && extensionFailed) {
				replan(mc, null);
				return null;
			}
			awaitingExtension = !complete;
			index = steps.size() - 1;
		}
		PathDisplay.show(steps, index);

		int aimIndex = index;
		if (player.isSpectator()) {
			aimIndex = steps.size() - 1;
		} else {
			int last = Math.min(steps.size() - 1, index + LOOKAHEAD);
			for (int candidate = last; candidate > index; candidate--) {
				if (clearSweep(mc, player, position, hoverPoint(steps.get(candidate).dest()))) {
					aimIndex = candidate;
					break;
				}
			}
		}
		if (aimIndex == index && offRoute > OFF_ROUTE && !player.isSpectator()
			&& !clearSweep(mc, player, position, hoverPoint(steps.get(index).dest()))) {
			// Momentum carried the player off the route and out of sight of it: plan again from here rather than backtracking.
			replan(mc, null);
			return null;
		}
		boolean finalAim = complete && aimIndex == steps.size() - 1;
		Vec3 aim = finalAim ? hoverPoint(target) : hoverPoint(steps.get(aimIndex).dest());
		steer(player, input, aim, finalAim || awaitingExtension, now);
		if (!awaitingExtension) {
			trackProgress(mc, position.distanceTo(aim), aimIndex);
		}
		return null;
	}

	/**
	 * Searches on from the end of a partial route while the player is still flying along it, and appends the next
	 * leg when it is found. A leg that cannot be found makes the flight re-plan from wherever the player is at the route's end.
	 */
	private void extendRoute(Minecraft mc, LocalPlayer player) {
		if (complete || extensionFailed) {
			return;
		}
		if (search == null) {
			if (steps.size() - index > EXTEND_AHEAD) {
				return;
			}
			PathWorld world = new PathWorld(mc.level, player, false, false);
			BlockPos end = steps.getLast().dest();
			if (world.atLoadedEdge(end)) {
				// The chunks past the route have not arrived yet; a search from here could not see any further.
				return;
			}
			search = new FlightPathfinder(world, MAX_NODES).start(end, goal);
		}
		if (!search.advance()) {
			return;
		}
		Path leg = search.result();
		search = null;
		if (leg == null || leg.steps().isEmpty()) {
			extensionFailed = true;
			return;
		}
		List<PathStep> joined = new ArrayList<>(steps.size() + leg.steps().size());
		joined.addAll(steps);
		joined.addAll(leg.steps());
		steps = joined;
		complete = leg.complete();
	}

	/**
	 * Moves {@link #index} up to the route waypoint nearest the player, past it when it is close enough to count as
	 * reached.
	 *
	 * @return the distance from the player to that nearest waypoint
	 */
	private double advanceToNearestWaypoint(Vec3 position) {
		int end = Math.min(steps.size(), index + PROGRESS_WINDOW);
		int nearest = index;
		double nearestDistance = Double.MAX_VALUE;
		for (int candidate = index; candidate < end; candidate++) {
			double distance = position.distanceTo(hoverPoint(steps.get(candidate).dest()));
			if (distance < nearestDistance) {
				nearestDistance = distance;
				nearest = candidate;
			}
		}
		index = nearestDistance < WAYPOINT_PASSED ? nearest + 1 : nearest;
		return nearestDistance;
	}

	/**
	 * Faces the aim point and presses forward, back and strafe keys to match a target velocity toward it. Steering
	 * against the whole velocity error, not just the forward part, cancels the sideways drift left over from turns.
	 */
	private void steer(LocalPlayer player, AgentInput input, Vec3 aim, boolean finalAim, long now) {
		Vec3 position = player.position();
		Vec3 velocity = player.getDeltaMovement();
		double dx = aim.x - position.x;
		double dz = aim.z - position.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal > 0.02) {
			float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
			if (horizontal > TURN_MIN_DISTANCE) {
				PlayerControl.turnTowards(player, yaw, 0.0F);
			}
			double wanted = Math.min(CRUISE_SPEED, BRAKE_GAIN * horizontal + (finalAim ? 0.0 : 0.2));
			double errorX = dx / horizontal * wanted - velocity.x;
			double errorZ = dz / horizontal * wanted - velocity.z;
			double facingYaw = Math.toRadians(player.getYRot());
			double errorForward = -errorX * Math.sin(facingYaw) + errorZ * Math.cos(facingYaw);
			double errorLeft = errorX * Math.cos(facingYaw) + errorZ * Math.sin(facingYaw);
			input.forward = errorForward > STEER_DEADBAND;
			input.backward = errorForward < -STEER_DEADBAND;
			input.left = errorLeft > STEER_DEADBAND;
			input.right = errorLeft < -STEER_DEADBAND;
			boolean facing = Math.abs(Mth.wrapDegrees(yaw - player.getYRot())) < 35.0F;
			input.sprint = input.forward && facing && wanted > SPRINT_ABOVE;
		}
		double predicted = aim.y - position.y - velocity.y * VERTICAL_COAST;
		if (predicted > 0.1) {
			pressJump(input, now);
		} else {
			releaseJump(now);
			if (predicted < -0.1) {
				input.sneak = true;
			}
		}
	}

	private static boolean settled(LocalPlayer player) {
		Vec3 velocity = player.getDeltaMovement();
		return velocity.horizontalDistance() < SETTLED_HORIZONTAL_SPEED && Math.abs(velocity.y) < SETTLED_VERTICAL_SPEED;
	}

	private void pressJump(AgentInput input, long now) {
		if (jumpHeld || now - jumpReleasedAt >= JUMP_REPRESS_TICKS) {
			input.jump = true;
			jumpHeld = true;
		}
	}

	private void releaseJump(long now) {
		if (jumpHeld) {
			jumpHeld = false;
			jumpReleasedAt = now;
		}
	}

	private @Nullable String tickLanding(Minecraft mc, LocalPlayer player, AgentInput input, long now) throws TaskFailure {
		if (player.isSpectator()) {
			return finished(player, "Spectators always fly, so stayed in the air at " + player.blockPosition().toShortString() + ".");
		}
		if (!Flight.isFlying(player) && (player.onGround() || player.isInWater())) {
			return finished(player, "Landed at " + player.blockPosition().toShortString() + ".");
		}
		if (player.isInWater()) {
			Flight.setFlying(player, false);
			return finished(player, "Landed in water at " + player.blockPosition().toShortString() + ".");
		}
		if (!Flight.isFlying(player)) {
			// Already falling: nothing to steer, just wait for the ground.
			return null;
		}
		releaseJump(now);
		input.sneak = true;
		if (player.getY() < lowestLandingY - 0.05) {
			lowestLandingY = player.getY();
			landingStuckTicks = 0;
		} else if (++landingStuckTicks > LANDING_STUCK_TICKS) {
			// Resting on something too thin to count as ground, such as a fence top or a ledge edge.
			Flight.setFlying(player, false);
		}
		return null;
	}

	private String arrive(Minecraft mc, LocalPlayer player) {
		PathDisplay.clear();
		String where = player.blockPosition().toShortString();
		if (!land || player.isSpectator()) {
			return Flight.isFlying(player) ? "Flew to " + where + " and is hovering there." : "Flew to " + where + ", standing on the ground.";
		}
		BlockPos spot = Flight.landingSpot(new PathWorld(mc.level, player, false, false), player.blockPosition());
		if (spot == null) {
			return "Flew to " + where + " and is hovering: no safe ground within " + Flight.MAX_LANDING_DEPTH
				+ " blocks below (lava, a hazard, the void or unloaded chunks).";
		}
		arrivalNote = "Flew to " + where;
		phase = Phase.LANDING;
		return null;
	}

	private String finished(LocalPlayer player, String text) {
		return arrivalNote == null ? text : arrivalNote + "; " + Character.toLowerCase(text.charAt(0)) + text.substring(1);
	}

	/** Point the feet aim for inside a cell: centered, slightly above its floor. */
	private static Vec3 hoverPoint(BlockPos cell) {
		return new Vec3(cell.getX() + 0.5, cell.getY() + HOVER, cell.getZ() + 0.5);
	}

	/** Whether the player's hitbox can move in a straight line without touching blocks, fluids or hazards. */
	private static boolean clearSweep(Minecraft mc, LocalPlayer player, Vec3 from, Vec3 to) {
		Vec3 delta = to.subtract(from);
		int samples = Math.max(1, (int) Math.ceil(delta.length() / SWEEP_STEP));
		AABB box = player.getBoundingBox().move(from.subtract(player.position())).inflate(0.05, 0.0, 0.05);
		for (int i = 1; i <= samples; i++) {
			AABB moved = box.move(delta.scale((double) i / samples));
			if (!mc.level.noCollision(player, moved)) {
				return false;
			}
			boolean unsafe = mc.level.getBlockStatesIfLoaded(moved).anyMatch(state -> !state.getFluidState().isEmpty()
				|| state.is(BlockTags.FIRE) || state.getFluidState().is(FluidTags.LAVA));
			if (unsafe) {
				return false;
			}
		}
		return true;
	}

	private void trackProgress(Minecraft mc, double distance, int aimIndex) throws TaskFailure {
		if (aimIndex != lastProgressIndex) {
			lastProgressIndex = aimIndex;
			bestDistance = distance;
			stuckTicks = 0;
			return;
		}
		if (distance < bestDistance - 0.05) {
			bestDistance = distance;
			stuckTicks = 0;
			return;
		}
		if (++stuckTicks > STUCK_REPLAN_TICKS) {
			replan(mc, "got stuck");
		}
	}

	private void replan(Minecraft mc, @Nullable String reason) throws TaskFailure {
		if (++replans > MAX_REPLANS) {
			throw new TaskFailure("Gave up flying: too many re-plans" + (reason == null ? "" : " (last: " + reason + ")")
				+ ". Stopped at " + mc.player.blockPosition().toShortString() + ".");
		}
		steps = null;
		search = null;
		PathDisplay.clear();
	}

	/**
	 * Runs this tick's share of the route search; the caller holds the player where the search started meanwhile.
	 *
	 * @return false while the search is still running
	 */
	private boolean plan(Minecraft mc, LocalPlayer player) throws TaskFailure {
		if (player.isSpectator()) {
			startRoute(List.of(new PathStep(player.blockPosition(), target, MoveType.FLY, List.of())), true);
			return true;
		}
		if (search == null) {
			BlockPos feet = player.blockPosition();
			FlightPathfinder pathfinder = new FlightPathfinder(new PathWorld(mc.level, player, false, false), MAX_NODES);
			searchStart = !pathfinder.fits(feet) && pathfinder.fits(feet.above()) ? feet.above() : feet;
			String blocked = pathfinder.unreachable(target, range, searchStart);
			if (blocked != null) {
				throw new TaskFailure("Cannot fly to " + target.toShortString() + ": " + blocked + " Pick an open spot next to it, or give a range.");
			}
			holdAt = player.position();
			search = pathfinder.start(searchStart, goal);
		}
		if (!search.advance()) {
			return false;
		}
		Path found = search.result();
		search = null;
		if (found == null) {
			throw new TaskFailure("No flight path from " + searchStart.toShortString() + " to " + target.toShortString()
				+ ". The destination may be enclosed, inside blocks, unloaded, or reachable only through lava or fire.");
		}
		// Already in the goal cell: keep a single step to the target so the player still settles there.
		startRoute(found.steps().isEmpty() ? List.of(new PathStep(searchStart, target, MoveType.FLY, List.of())) : found.steps(),
			found.complete());
		return true;
	}

	private void startRoute(List<PathStep> route, boolean routeComplete) {
		steps = route;
		complete = routeComplete;
		extensionFailed = false;
		index = 0;
		stuckTicks = 0;
		bestDistance = Double.MAX_VALUE;
		lastProgressIndex = -1;
		PathDisplay.show(steps, 0);
	}

	@Override
	public void stop(Minecraft mc) {
		PathDisplay.clear();
		PlayerControl.idle(mc);
	}

	@Override
	public int timeoutTicks() {
		return timeout;
	}

	/** Where the flight has got to, so a timeout message says whether it was stuck or just slow. */
	private String progress() {
		BlockPos at = lastPosition;
		return at == null ? "" : " (at " + at.toShortString() + (replans > 0 ? ", re-planned " + replans + " times" : "") + ")";
	}

	@Override
	public String describe() {
		return switch (mode) {
			case TRAVEL -> phase == Phase.LANDING ? "landing" : "flying to " + target.toShortString() + progress();
			case TAKE_OFF -> "taking off";
			case LAND -> "landing";
		};
	}
}
