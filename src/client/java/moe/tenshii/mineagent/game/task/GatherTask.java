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

	private final Lookup.BlockQuery block;
	private final int wanted;
	private final int radius;
	private final SubTask sub = new SubTask();
	private final Set<BlockPos> skip = new HashSet<>();
	private @Nullable Map<Item, Integer> before;
	private @Nullable BlockPos current;
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
			current = null;
		}
		if (mined >= wanted) {
			return result(player, null);
		}
		List<WorldInfo.Found> candidates = WorldInfo.findBlocks(mc.level, player.blockPosition(), radius, block.matches(), 64);
		WorldInfo.Found next = candidates.stream()
			.filter(found -> !skip.contains(found.pos()))
			.min(Comparator.comparingDouble(found -> found.distance() + (found.exposed() ? 0.0 : 6.0) + Math.max(0, found.pos().getY() - player.getBlockY()) * 2.0))
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
