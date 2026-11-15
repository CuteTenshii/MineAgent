package moe.tenshii.mineagent.game.task;

import java.util.List;
import moe.tenshii.mineagent.game.AgentInput;
import moe.tenshii.mineagent.game.Flight;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.SubTask;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.path.Goal;
import moe.tenshii.mineagent.path.MoveType;
import moe.tenshii.mineagent.path.Path;
import moe.tenshii.mineagent.path.PathDisplay;
import moe.tenshii.mineagent.path.PathSearch;
import moe.tenshii.mineagent.path.PathStep;
import moe.tenshii.mineagent.path.PathWorld;
import moe.tenshii.mineagent.path.Pathfinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Follows A* paths to a goal, re-planning when the player drifts off course or the search was partial.
 */
public final class WalkTask implements Task {
	private static final int MAX_NODES = 40_000;
	private static final int MAX_REPLANS = 40;
	// Steps that go up are jumped on purpose; this only covers a ledge the path did not expect, such as a slab edge.
	private static final int STUCK_JUMP_TICKS = 10;
	private static final int STUCK_REPLAN_TICKS = 70;
	private static final int MAX_BREAK_TICKS_PER_BLOCK = 400;
	private static final double ARRIVE_HORIZONTAL = 0.3;
	// A flat waypoint this close counts as reached, so cutting a corner near it does not turn the player back.
	private static final double WAYPOINT_PASSED = 0.9;
	private static final double OFF_ROUTE = 4.0;
	private static final int LOOKAHEAD = 12;
	private static final double SWEEP_STEP = 0.3;
	// About sin 22.5 degrees: a key is pressed when the aim lies within 67.5 degrees of its direction.
	private static final double KEY_THRESHOLD = 0.38;
	// Nearer than this the direction to the aim swings as the player drifts around it; strafing corrects instead.
	private static final double TURN_MIN_DISTANCE = 0.75;
	// Vanilla only sprints while moving almost straight forward.
	private static final double SPRINT_ALIGNMENT = 0.95;
	// Closer than this, sprinting overshoots the waypoint.
	private static final double SPRINT_MIN_DISTANCE = 2.5;
	private static final int DOOR_RETRY_TICKS = 10;

	private final Goal goal;
	private final String description;
	private final boolean allowBreaking;
	private final boolean allowPillar;
	private final int timeout;
	private final SubTask landing = new SubTask();

	private @Nullable Path path;
	private @Nullable PathSearch search;
	private BlockPos searchFrom = BlockPos.ZERO;
	private int index;
	private int lastAimIndex = -1;
	private int doorCooldown;
	private int replans;
	private int stuckTicks;
	private int breakTicks;
	private double bestDistance = Double.MAX_VALUE;
	private int blocksBroken;
	private int blocksPlaced;
	private long lastTickTime = -1;

	public WalkTask(Goal goal, String description, boolean allowBreaking, boolean allowPillar, int timeoutTicks) {
		this.goal = goal;
		this.description = description;
		this.allowBreaking = allowBreaking;
		this.allowPillar = allowPillar;
		this.timeout = timeoutTicks;
	}

	public static boolean isScaffoldBlock(ItemStack stack) {
		return stack.getItem() instanceof BlockItem blockItem
			&& (stack.is(ItemTags.DIRT) || stack.is(ItemTags.STONE_CRAFTING_MATERIALS) || stack.is(ItemTags.PLANKS)
				|| stack.is(ItemTags.STONE_TOOL_MATERIALS))
			&& !(blockItem.getBlock() instanceof FallingBlock);
	}

	@Override
	public @Nullable String tick(Minecraft mc) throws TaskFailure {
		LocalPlayer player = mc.player;
		PlayerControl.takeControl(mc);
		AgentInput input = PlayerControl.input();
		BlockPos feet = player.blockPosition();
		long now = mc.level.getGameTime();
		if (lastTickTime >= 0 && now - lastTickTime > 1 && (path != null || search != null)) {
			// A reflex moved the player while this walk was paused, so the old path may start somewhere else.
			path = null;
			search = null;
			PlayerControl.stopMining(mc);
		}
		lastTickTime = now;

		if (player.isSpectator()) {
			throw new TaskFailure("Spectators cannot walk; use fly_to instead.");
		}
		if (Flight.isFlying(player) && !landing.active()) {
			if (Flight.landingSpot(new PathWorld(mc.level, player, false, false), feet) == null) {
				throw new TaskFailure("The player is flying with no safe ground within " + Flight.MAX_LANDING_DEPTH
					+ " blocks below " + feet.toShortString() + "; use fly_to to reach solid ground first.");
			}
			// The ground pathfinder assumes the feet rest on something, so come down before planning.
			landing.set(mc, FlyTask.landHere());
			path = null;
			search = null;
		}
		if (landing.tick(mc)) {
			return null;
		}

		if (goal.reached(feet) && (player.onGround() || player.isInWater() || player.onClimbable())) {
			return summary(player, "Arrived");
		}
		if (path == null || index >= path.steps().size()) {
			if (!plan(mc, feet)) {
				return null;
			}
			if (path.steps().isEmpty()) {
				return summary(player, "Arrived");
			}
		}

		PathStep step = path.steps().get(index);
		input.clear();

		for (BlockPos block : step.toBreak()) {
			if (!mc.level.getBlockState(block).getCollisionShape(mc.level, block).isEmpty()) {
				if (++breakTicks > MAX_BREAK_TICKS_PER_BLOCK) {
					replan(mc, "could not break " + block.toShortString());
					return null;
				}
				if (PlayerControl.mineTick(mc, block)) {
					blocksBroken++;
				}
				return null;
			}
		}
		PlayerControl.stopMining(mc);
		breakTicks = 0;

		if (doorCooldown > 0) {
			doorCooldown--;
		}
		return switch (step.type()) {
			case PILLAR -> tickPillar(mc, player, step);
			case CLIMB_UP, CLIMB_DOWN -> tickClimb(mc, player, step, input);
			default -> openDoor(mc, player, step) ? null : tickMove(mc, player, input);
		};
	}

	private @Nullable String tickMove(Minecraft mc, LocalPlayer player, AgentInput input) throws TaskFailure {
		PathWorld view = new PathWorld(mc.level, player, false, false);
		Vec3 position = player.position();
		boolean resting = player.onGround() || player.isInWater() || player.onClimbable();
		if (resting) {
			passWaypoints(view, position);
			if (index >= path.steps().size()) {
				return null;
			}
		}
		PathStep step = path.steps().get(index);
		Vec3 stepTarget = Vec3.atBottomCenterOf(step.dest());
		double dy = step.dest().getY() - player.getY();
		if (horizontalDistance(position, stepTarget) < ARRIVE_HORIZONTAL && Math.abs(dy) < 0.6 && resting) {
			advance();
			return null;
		}
		// Only height above the player counts as off course: a drop's landing is meant to be far below.
		if ((horizontalDistance(position, stepTarget) > OFF_ROUTE || dy > OFF_ROUTE) && player.onGround()) {
			replan(mc, null);
			return null;
		}

		int aimIndex = resting ? farthestInSight(mc, view, player, position) : index;
		if (aimIndex != lastAimIndex) {
			lastAimIndex = aimIndex;
			bestDistance = Double.MAX_VALUE;
			stuckTicks = 0;
		}
		Vec3 aim = Vec3.atBottomCenterOf(path.steps().get(aimIndex).dest());
		double distance = horizontalDistance(position, aim);
		double forward = steer(player, input, aim);
		input.sprint = view.canSprint() && !player.isInWater() && forward > SPRINT_ALIGNMENT && distance > SPRINT_MIN_DISTANCE
			&& isFlat(view, path.steps().get(aimIndex));
		if (dy > 0.5 && (player.onGround() || player.onClimbable())) {
			input.jump = true;
		}
		if (player.isInWater() && (dy > -0.5 || player.horizontalCollision)) {
			input.jump = true;
		}
		trackProgress(mc, distance, input);
		return null;
	}

	/**
	 * Turns toward {@code aim} and presses the forward, back and strafe keys that move toward it, so the player keeps
	 * going while it turns instead of stopping to face each waypoint.
	 *
	 * @return how directly the player faces the aim, from -1 (away) to 1 (straight at it)
	 */
	private static double steer(LocalPlayer player, AgentInput input, Vec3 aim) {
		double dx = aim.x - player.getX();
		double dz = aim.z - player.getZ();
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length < 0.05) {
			return 0.0;
		}
		if (length > TURN_MIN_DISTANCE) {
			float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
			PlayerControl.turnTowards(player, yaw, player.getXRot());
		}
		double facing = Math.toRadians(player.getYRot());
		double forward = (-dx * Math.sin(facing) + dz * Math.cos(facing)) / length;
		double left = (dx * Math.cos(facing) + dz * Math.sin(facing)) / length;
		input.forward = forward > KEY_THRESHOLD;
		input.backward = forward < -KEY_THRESHOLD;
		input.left = left > KEY_THRESHOLD;
		input.right = left < -KEY_THRESHOLD;
		return forward;
	}

	/**
	 * Moves {@link #index} past the flat waypoints the player has already reached or cut past, looking only along the
	 * run of flat steps ahead so no door, climb or block to break is skipped.
	 */
	private void passWaypoints(PathWorld view, Vec3 position) {
		int end = flatRunEnd(view, index);
		int nearest = -1;
		double nearestDistance = Double.MAX_VALUE;
		for (int candidate = index; candidate < end; candidate++) {
			BlockPos dest = path.steps().get(candidate).dest();
			if (Math.abs(dest.getY() - position.y) > 0.6) {
				continue;
			}
			double distance = horizontalDistance(position, Vec3.atBottomCenterOf(dest));
			if (distance < nearestDistance) {
				nearestDistance = distance;
				nearest = candidate;
			}
		}
		if (nearest < 0) {
			return;
		}
		// The final step is never passed this way: the player must end inside the goal block, not near it.
		int next = nearestDistance < WAYPOINT_PASSED ? Math.min(nearest + 1, path.steps().size() - 1) : nearest;
		if (next > index) {
			index = next;
			PathDisplay.show(path.steps(), index);
			stuckTicks = 0;
			bestDistance = Double.MAX_VALUE;
		}
	}

	/** The farthest waypoint of the flat run ahead that the player can walk to in a straight line from here. */
	private int farthestInSight(Minecraft mc, PathWorld view, LocalPlayer player, Vec3 position) {
		int end = Math.min(flatRunEnd(view, index), index + LOOKAHEAD);
		for (int candidate = end - 1; candidate > index; candidate--) {
			if (straightWalk(mc, view, player, position, Vec3.atBottomCenterOf(path.steps().get(candidate).dest()))) {
				return candidate;
			}
		}
		return index;
	}

	/** One past the last step, from {@code start}, of the run of plain flat walking steps. */
	private int flatRunEnd(PathWorld view, int start) {
		int end = start;
		while (end < path.steps().size() && isFlat(view, path.steps().get(end))) {
			end++;
		}
		return end;
	}

	/** A plain step on level ground: nothing to break, open or climb, so the walker may cut across it. */
	private static boolean isFlat(PathWorld view, PathStep step) {
		return (step.type() == MoveType.WALK || step.type() == MoveType.DIAGONAL)
			&& step.from().getY() == step.dest().getY()
			&& step.toBreak().isEmpty()
			&& !view.isDoor(step.from()) && !view.isDoor(step.dest()) && !view.isDoor(step.dest().above());
	}

	/**
	 * Whether the player can walk straight from {@code from} to {@code to}: the hitbox stays clear of blocks, and every
	 * point along the way has open space for the body and a floor to stand on.
	 */
	private static boolean straightWalk(Minecraft mc, PathWorld view, LocalPlayer player, Vec3 from, Vec3 to) {
		Vec3 delta = new Vec3(to.x - from.x, 0.0, to.z - from.z);
		int samples = Math.max(1, (int) Math.ceil(delta.length() / SWEEP_STEP));
		AABB box = player.getBoundingBox().inflate(0.05, 0.0, 0.05);
		for (int i = 1; i <= samples; i++) {
			Vec3 offset = delta.scale((double) i / samples);
			BlockPos cell = BlockPos.containing(from.add(offset).add(0.0, 0.1, 0.0));
			if (!view.passable(cell) || !view.passable(cell.above()) || !view.solidFloor(cell.below())) {
				return false;
			}
			if (!mc.level.noCollision(player, box.move(offset))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Opens a closed door or fence gate this step walks through once it is within reach.
	 *
	 * @return true while the player should wait for it to open
	 */
	private boolean openDoor(Minecraft mc, LocalPlayer player, PathStep step) {
		for (BlockPos pos : List.of(step.dest(), step.dest().above())) {
			BlockState state = mc.level.getBlockState(pos);
			if (!PathWorld.isDoor(state) || state.getValue(BlockStateProperties.OPEN)) {
				continue;
			}
			if (PlayerControl.eyeDistance(player, pos) > PlayerControl.reach(player) - 0.5) {
				return false;
			}
			if (doorCooldown == 0) {
				PlayerControl.useOn(mc, pos, PlayerControl.faceTowardsPlayer(player, pos));
				// The server's answer takes a moment; clicking again before it arrives would close the door.
				doorCooldown = DOOR_RETRY_TICKS;
			}
			return true;
		}
		return false;
	}

	private @Nullable String tickClimb(Minecraft mc, LocalPlayer player, PathStep step, AgentInput input) throws TaskFailure {
		boolean up = step.type() == MoveType.CLIMB_UP;
		double dy = step.dest().getY() - player.getY();
		if (up ? dy <= 0.05 : dy >= -0.05 && (player.onClimbable() || player.onGround())) {
			advance();
			return null;
		}
		BlockState ladder = mc.level.getBlockState(up ? step.from() : step.dest());
		if (ladder.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			// Facing the wall the ladder hangs on, so pressing forward pushes into it and climbs.
			float wallYaw = ladder.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite().toYRot();
			PlayerControl.turnTowards(player, wallYaw, player.getXRot());
			input.forward = up;
		}
		// Holding on to a ladder with nothing pressed slides down it; jumping climbs any climbable block.
		input.jump = up;
		trackProgress(mc, Math.abs(dy), input);
		return null;
	}

	private static double horizontalDistance(Vec3 a, Vec3 b) {
		double dx = a.x - b.x;
		double dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	private @Nullable String tickPillar(Minecraft mc, LocalPlayer player, PathStep step) throws TaskFailure {
		AgentInput input = PlayerControl.input();
		BlockPos base = step.from();
		if (player.onGround() && player.blockPosition().equals(step.dest())) {
			blocksPlaced++;
			advance();
			return null;
		}
		if (!PlayerControl.holdItem(mc, WalkTask::isScaffoldBlock)) {
			replan(mc, "ran out of blocks to pillar with");
			return null;
		}
		PlayerControl.turnTowards(player, player.getYRot(), 90.0F);
		Vec3 center = new Vec3(base.getX() + 0.5, player.getY(), base.getZ() + 0.5);
		if (center.distanceTo(player.position()) > 0.2 && player.onGround()) {
			double dx = center.x - player.getX();
			double dz = center.z - player.getZ();
			float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
			player.setYRot(yaw);
			input.forward = true;
			return null;
		}
		input.forward = false;
		input.jump = true;
		if (player.getY() > base.getY() + 1.0 && mc.level.getBlockState(base).canBeReplaced()) {
			PlayerControl.useOn(mc, base.below(), Direction.UP);
		}
		trackProgress(mc, step.dest().getY() + 0.5 - player.getY(), input);
		return null;
	}

	private void trackProgress(Minecraft mc, double distance, AgentInput input) throws TaskFailure {
		if (distance < bestDistance - 0.05) {
			bestDistance = distance;
			stuckTicks = 0;
			return;
		}
		stuckTicks++;
		if (stuckTicks > STUCK_JUMP_TICKS && mc.player.onGround()) {
			input.jump = true;
		}
		if (stuckTicks > STUCK_REPLAN_TICKS) {
			replan(mc, "got stuck");
		}
	}

	private void advance() {
		index++;
		lastAimIndex = -1;
		PathDisplay.show(path.steps(), index);
		stuckTicks = 0;
		bestDistance = Double.MAX_VALUE;
	}

	private void replan(Minecraft mc, @Nullable String reason) throws TaskFailure {
		if (++replans > MAX_REPLANS) {
			throw new TaskFailure("Gave up walking: too many re-plans" + (reason == null ? "" : " (last: " + reason + ")")
				+ ". Stopped at " + mc.player.blockPosition().toShortString());
		}
		path = null;
		search = null;
		index = 0;
		stuckTicks = 0;
		bestDistance = Double.MAX_VALUE;
		PathDisplay.clear();
		PlayerControl.idle(mc);
	}

	/**
	 * Runs this tick's share of the path search, standing still meanwhile.
	 *
	 * @return false while the search is still running
	 */
	private boolean plan(Minecraft mc, BlockPos feet) throws TaskFailure {
		if (search == null) {
			boolean hasScaffold = allowPillar && PlayerControl.findSlot(mc.player, WalkTask::isScaffoldBlock) >= 0;
			PathWorld world = new PathWorld(mc.level, mc.player, allowBreaking, hasScaffold);
			search = new Pathfinder(world, MAX_NODES).start(feet, goal);
			searchFrom = feet;
		}
		if (!search.advance()) {
			PlayerControl.idle(mc);
			// Treading water keeps the player from sinking away from where the search started.
			PlayerControl.input().jump = mc.player.isInWater();
			return false;
		}
		Path found = search.result();
		search = null;
		if (found == null) {
			throw new TaskFailure("No path found from " + searchFrom.toShortString() + " to " + description
				+ (allowBreaking ? "" : " (block breaking was disabled)")
				+ ". The destination may be unloaded, enclosed, or across lava/water.");
		}
		path = found;
		index = 0;
		PathDisplay.show(found.steps(), 0);
		stuckTicks = 0;
		bestDistance = Double.MAX_VALUE;
		return true;
	}

	private String summary(LocalPlayer player, String verb) {
		StringBuilder text = new StringBuilder(verb).append(" at ").append(player.blockPosition().toShortString());
		if (blocksBroken > 0) {
			text.append("; broke ").append(blocksBroken).append(" blocks on the way");
		}
		if (blocksPlaced > 0) {
			text.append("; placed ").append(blocksPlaced).append(" blocks to climb");
		}
		return text.append('.').toString();
	}

	@Override
	public void stop(Minecraft mc) {
		landing.clear(mc);
		PathDisplay.clear();
		PlayerControl.idle(mc);
	}

	@Override
	public int timeoutTicks() {
		return timeout;
	}

	@Override
	public String describe() {
		return "walking to " + description;
	}
}
