package moe.tenshii.mineagent.game.task;

import java.util.Map;
import moe.tenshii.mineagent.game.Inventories;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.SubTask;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.path.Goal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Walks into reach of a block, breaks it with the best tool and picks up the drops.
 */
public final class MineTask implements Task {
	private static final int MAX_BREAK_TICKS = 600;

	private final BlockPos pos;
	private final boolean collect;
	private final boolean force;
	private final SubTask sub = new SubTask();
	private @Nullable Map<Item, Integer> before;
	private String blockName = "block";
	private boolean walked;
	private boolean broken;
	private int breakTicks;

	public MineTask(BlockPos pos, boolean collect, boolean force) {
		this.pos = pos.immutable();
		this.collect = collect;
		this.force = force;
	}

	@Override
	public @Nullable String tick(Minecraft mc) throws TaskFailure {
		LocalPlayer player = mc.player;
		PlayerControl.takeControl(mc);
		if (before == null) {
			BlockState state = mc.level.getBlockState(pos);
			if (state.isAir()) {
				throw new TaskFailure("There is no block at " + pos.toShortString() + " (it is air).");
			}
			if (state.getDestroySpeed(mc.level, pos) < 0.0F) {
				throw new TaskFailure(Lookup.name(state.getBlock()) + " at " + pos.toShortString() + " is unbreakable.");
			}
			if (!force && !PlayerControl.hasCorrectTool(player, state)) {
				throw new TaskFailure(Lookup.name(state.getBlock()) + " needs a better tool to drop anything (e.g. a pickaxe of the right tier)."
					+ " Craft one first, or pass force=true to break it anyway.");
			}
			blockName = Lookup.name(state.getBlock());
			before = Inventories.snapshot(player);
		}
		if (sub.tick(mc)) {
			return null;
		}
		if (!broken) {
			if (PlayerControl.eyeDistance(player, pos) > PlayerControl.reach(player) - 0.2) {
				if (walked) {
					throw new TaskFailure("Could not get within reach of " + pos.toShortString() + "; stopped at " + player.blockPosition().toShortString());
				}
				walked = true;
				sub.set(mc, new WalkTask(Goal.withinReach(pos, PlayerControl.reach(player) - 0.6), pos.toShortString(), true, true, 20 * 120));
				return null;
			}
			if (++breakTicks > MAX_BREAK_TICKS) {
				throw new TaskFailure("Breaking " + blockName + " took too long.");
			}
			if (!PlayerControl.mineTick(mc, pos)) {
				return null;
			}
			broken = true;
			PlayerControl.stopMining(mc);
			if (collect) {
				sub.set(mc, new CollectTask(pos, 5.0, null, 20 * 8));
				return null;
			}
		}
		return "Mined " + blockName + " at " + pos.toShortString() + ". Inventory change: " + Inventories.diff(before, Inventories.snapshot(player)) + ".";
	}

	@Override
	public void stop(Minecraft mc) {
		sub.clear(mc);
		PlayerControl.idle(mc);
	}

	@Override
	public int timeoutTicks() {
		return 20 * 180;
	}

	@Override
	public String describe() {
		String child = sub.describe();
		return child != null ? child : "mining " + blockName + " at " + pos.toShortString();
	}
}
