package moe.tenshii.mineagent.game.task;

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
import moe.tenshii.mineagent.path.PathStep;
import moe.tenshii.mineagent.path.PathWorld;
import moe.tenshii.mineagent.path.Pathfinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Follows A* paths to a goal, re-planning when the player drifts off course or the search was partial.
 */
public final class WalkTask implements Task {
	private static final int MAX_NODES = 40_000;
	private static final int MAX_REPLANS = 40;
	private static final int STUCK_JUMP_TICKS = 25;
	private static final int STUCK_REPLAN_TICKS = 70;
	private static final int MAX_BREAK_TICKS_PER_BLOCK = 400;
	private static final double ARRIVE_HORIZONTAL = 0.3;

	private final Goal goal;
	private final String description;
	private final boolean allowBreaking;
	private final boolean allowPillar;
	private final int timeout;
	private final SubTask landing = new SubTask();

	private @Nullable Path path;
	private int index;
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
		if (lastTickTime >= 0 && now - lastTickTime > 1 && path != null) {
			// A reflex moved the player while this walk was paused, so the old path may start somewhere else.
			path = null;
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
		}
		if (landing.tick(mc)) {
			return null;
		}

		if (goal.reached(feet) && (player.onGround() || player.isInWater())) {
			return summary(player, "Arrived");
		}
		if (path == null || index >= path.steps().size()) {
			plan(mc, feet);
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

		if (step.type() == MoveType.PILLAR) {
			return tickPillar(mc, player, step);
		}

		Vec3 target = new Vec3(step.dest().getX() + 0.5, step.dest().getY(), step.dest().getZ() + 0.5);
		double dx = target.x - player.getX();
		double dz = target.z - player.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		double dy = step.dest().getY() - player.getY();

		if (horizontal < ARRIVE_HORIZONTAL && Math.abs(dy) < 0.6 && (player.onGround() || player.isInWater())) {
			advance();
			return null;
		}
		if (player.position().distanceTo(target) > 4.0 && player.onGround()) {
			replan(mc, null);
			return null;
		}

		float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
		PlayerControl.turnTowards(player, yaw, 10.0F);
		boolean facing = Math.abs(Mth.wrapDegrees(yaw - player.getYRot())) < 30.0F;
		input.forward = facing && horizontal > 0.05;
		input.sprint = false;
		boolean needsUp = dy > 0.5;
		if ((needsUp || player.horizontalCollision) && player.onGround()) {
			input.jump = true;
		}
		if (player.isInWater() && (dy > -0.5 || player.horizontalCollision)) {
			input.jump = true;
		}

		trackProgress(mc, player.position().distanceTo(target), input);
		return null;
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
		index = 0;
		stuckTicks = 0;
		bestDistance = Double.MAX_VALUE;
		PathDisplay.clear();
		PlayerControl.idle(mc);
	}

	private void plan(Minecraft mc, BlockPos feet) throws TaskFailure {
		boolean hasScaffold = allowPillar && PlayerControl.findSlot(mc.player, WalkTask::isScaffoldBlock) >= 0;
		PathWorld world = new PathWorld(mc.level, mc.player, allowBreaking, hasScaffold);
		Path found = new Pathfinder(world, MAX_NODES).find(feet, goal);
		if (found == null) {
			throw new TaskFailure("No path found from " + feet.toShortString() + " to " + description
				+ (allowBreaking ? "" : " (block breaking was disabled)")
				+ ". The destination may be unloaded, enclosed, or across lava/water.");
		}
		path = found;
		index = 0;
		PathDisplay.show(found.steps(), 0);
		stuckTicks = 0;
		bestDistance = Double.MAX_VALUE;
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
