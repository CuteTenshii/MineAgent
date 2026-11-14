package moe.tenshii.mineagent.game.task;

import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.SubTask;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.path.Goal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Places a block item at a position by clicking a solid neighbouring face while sneaking.
 */
public final class PlaceTask implements Task {
	private static final int MAX_ATTEMPTS = 4;

	private final Lookup.ItemQuery item;
	private final BlockPos pos;
	private final SubTask sub = new SubTask();
	private boolean walked;
	private int sneakTicks;
	private int attempts;
	private int verifyTicks = -1;

	public PlaceTask(Lookup.ItemQuery item, BlockPos pos) {
		this.item = item;
		this.pos = pos.immutable();
	}

	@Override
	public @Nullable String tick(Minecraft mc) throws TaskFailure {
		LocalPlayer player = mc.player;
		PlayerControl.takeControl(mc);
		if (sub.tick(mc)) {
			return null;
		}
		BlockState current = mc.level.getBlockState(pos);
		if (verifyTicks >= 0) {
			if (!current.canBeReplaced()) {
				return "Placed " + Lookup.name(current.getBlock()) + " at " + pos.toShortString() + ".";
			}
			if (++verifyTicks < 6) {
				return null;
			}
			verifyTicks = -1;
			sneakTicks = 0;
		}
		if (!current.canBeReplaced()) {
			throw new TaskFailure(pos.toShortString() + " is occupied by " + Lookup.name(current.getBlock()) + "; mine it first.");
		}
		if (PlayerControl.findSlot(player, stack -> item.matches().test(stack) && stack.getItem() instanceof BlockItem) < 0) {
			throw new TaskFailure("No placeable " + item.label() + " in the inventory.");
		}
		Direction face = supportFace(mc, player);
		if (face == null) {
			throw new TaskFailure("Nothing solid next to " + pos.toShortString() + " to place against. Place a supporting block first.");
		}
		boolean overlapping = player.getBoundingBox().intersects(new AABB(pos));
		if (overlapping || PlayerControl.eyeDistance(player, pos) > PlayerControl.reach(player) - 0.5) {
			if (walked && !overlapping) {
				throw new TaskFailure("Could not get within reach of " + pos.toShortString() + ".");
			}
			if (walked) {
				throw new TaskFailure("The player is standing in " + pos.toShortString() + " and could not step out of it.");
			}
			walked = true;
			sub.set(mc, new WalkTask(Goal.withinReach(pos, PlayerControl.reach(player) - 1.0), pos.toShortString(), true, true, 20 * 90));
			return null;
		}
		PlayerControl.input().clear();
		// Sneaking makes the click place the block instead of opening a container being clicked on.
		PlayerControl.input().sneak = true;
		PlayerControl.holdItem(mc, stack -> item.matches().test(stack) && stack.getItem() instanceof BlockItem);
		BlockPos support = pos.relative(face);
		PlayerControl.lookAt(player, PlayerControl.center(support));
		if (++sneakTicks < 3) {
			return null;
		}
		if (++attempts > MAX_ATTEMPTS) {
			throw new TaskFailure("The server did not accept placing " + item.label() + " at " + pos.toShortString() + ".");
		}
		PlayerControl.useOn(mc, support, face.getOpposite());
		verifyTicks = 0;
		return null;
	}

	private @Nullable Direction supportFace(Minecraft mc, LocalPlayer player) {
		Direction best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Direction direction : Direction.values()) {
			BlockPos neighbor = pos.relative(direction);
			BlockState state = mc.level.getBlockState(neighbor);
			if (state.canBeReplaced() || state.getCollisionShape(mc.level, neighbor).isEmpty()) {
				continue;
			}
			double distance = player.getEyePosition().distanceTo(PlayerControl.center(neighbor));
			if (distance < bestDistance) {
				bestDistance = distance;
				best = direction;
			}
		}
		return best;
	}

	@Override
	public void stop(Minecraft mc) {
		sub.clear(mc);
		PlayerControl.idle(mc);
	}

	@Override
	public int timeoutTicks() {
		return 20 * 120;
	}

	@Override
	public String describe() {
		String child = sub.describe();
		return child != null ? child : "placing " + item.label() + " at " + pos.toShortString();
	}
}
