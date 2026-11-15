package moe.tenshii.mineagent.game.task;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import moe.tenshii.mineagent.game.Inventories;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.SubTask;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.WorldInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * Mines the nearest matching blocks one after another until enough are broken or none are left nearby.
 */
public final class GatherTask implements Task {
	private static final int MAX_FAILURES = 6;
	// Large enough that the rest of a tree or vein beats any other one nearby, so none is left half mined.
	private static final double SAME_CLUSTER_BONUS = 8.0;
	private static final double HIDDEN_PENALTY = 6.0;
	private static final double CLIMB_PENALTY_PER_BLOCK = 2.0;

	private final Lookup.BlockQuery block;
	private final int wanted;
	private final int radius;
	private final SubTask sub = new SubTask();
	private final Set<BlockPos> skip = new HashSet<>();
	private @Nullable Map<Item, Integer> before;
	private @Nullable BlockPos current;
	private @Nullable BlockPos lastMined;
	private int mined;
	private int failures;
	private int ticks;
	private @Nullable String lastFailure;

	public GatherTask(Lookup.BlockQuery block, int count, int radius) {
		this.block = block;
		this.wanted = count;
		this.radius = radius;
	}

	@Override
	public @Nullable String tick(Minecraft mc) throws TaskFailure {
		LocalPlayer player = mc.player;
		PlayerControl.takeControl(mc);
		if (before == null) {
			before = Inventories.snapshot(player);
		}
		if (++ticks > timeoutTicks() - 20 * 5) {
			return result(player, "Ran out of time");
		}
		try {
			if (sub.tick(mc)) {
				return null;
			}
		} catch (TaskFailure failure) {
			skip.add(current);
			lastFailure = failure.getMessage();
			if (++failures > MAX_FAILURES) {
				return result(player, "Stopped after repeated failures (last: " + lastFailure + ")");
			}
			current = null;
		}
		if (current != null) {
			mined++;
			lastMined = current;
			current = null;
		}
		if (mined >= wanted) {
			return result(player, null);
		}
		List<WorldInfo.Found> candidates = WorldInfo.findBlocks(mc.level, player.blockPosition(), radius, block.matches(), 64);
		// Blocks this high above the feet can be mined standing on the ground; only higher ones need climbing.
		int reachableRise = (int) Math.floor(player.getEyeHeight() + PlayerControl.reach(player) - 1.0);
		WorldInfo.Found next = candidates.stream()
			.filter(found -> !skip.contains(found.pos()))
			.min(Comparator.comparingDouble(found -> cost(found, player.getBlockY() + reachableRise)))
			.orElse(null);
		if (next == null) {
			if (mined == 0) {
				throw new TaskFailure("No reachable " + block.label() + " within " + radius + " blocks"
					+ (lastFailure == null ? "." : " (last problem: " + lastFailure + ")."));
			}
			return result(player, "No more " + block.label() + " within " + radius + " blocks");
		}
		if (!PlayerControl.hasCorrectTool(player, mc.level.getBlockState(next.pos()))) {
			throw new TaskFailure(Lookup.name(mc.level.getBlockState(next.pos()).getBlock())
				+ " needs a better tool to drop anything (e.g. a pickaxe of the right tier). Craft one first.");
		}
		current = next.pos();
		sub.set(mc, new MineTask(current, true, false));
		return null;
	}

	/** Lower is better: near, visible, reachable from the ground, and part of the tree or vein just started. */
	private double cost(WorldInfo.Found found, int reachableTop) {
		BlockPos pos = found.pos();
		double cost = found.distance() + (found.exposed() ? 0.0 : HIDDEN_PENALTY)
			+ Math.max(0, pos.getY() - reachableTop) * CLIMB_PENALTY_PER_BLOCK;
		if (lastMined != null && Math.abs(pos.getX() - lastMined.getX()) <= 1 && Math.abs(pos.getY() - lastMined.getY()) <= 1
			&& Math.abs(pos.getZ() - lastMined.getZ()) <= 1) {
			cost -= SAME_CLUSTER_BONUS;
		}
		return cost;
	}

	private String result(LocalPlayer player, @Nullable String problem) {
		String head = problem == null ? "Mined " + mined + " " + block.label() : problem + "; mined " + mined + " of " + wanted + " " + block.label();
		return head + ". Inventory change: " + Inventories.diff(before, Inventories.snapshot(player)) + ".";
	}

	@Override
	public void stop(Minecraft mc) {
		sub.clear(mc);
		PlayerControl.idle(mc);
	}

	@Override
	public int timeoutTicks() {
		return 20 * (60 + wanted * 45);
	}

	@Override
	public String describe() {
		String child = sub.describe();
		return "gathering " + block.label() + " (" + mined + "/" + wanted + ")" + (child == null ? "" : ": " + child);
	}
}
