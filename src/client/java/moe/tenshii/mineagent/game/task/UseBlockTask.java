package moe.tenshii.mineagent.game.task;

import java.util.function.Predicate;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.SubTask;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.path.Goal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Walks into reach of a block and right-clicks it, optionally waiting for a container menu to open.
 */
public final class UseBlockTask implements Task {
	private static final int MENU_WAIT_TICKS = 40;

	private final BlockPos pos;
	private final @Nullable Predicate<AbstractContainerMenu> expectedMenu;
	private final SubTask sub = new SubTask();
	private boolean walked;
	private int waitTicks = -1;
	private @Nullable BlockState before;

	/**
	 * @param expectedMenu when non-null, the task only finishes once the open menu matches it
	 */
	public UseBlockTask(BlockPos pos, @Nullable Predicate<AbstractContainerMenu> expectedMenu) {
		this.pos = pos.immutable();
		this.expectedMenu = expectedMenu;
	}

	@Override
	public @Nullable String tick(Minecraft mc) throws TaskFailure {
		LocalPlayer player = mc.player;
		PlayerControl.takeControl(mc);
		if (sub.tick(mc)) {
			return null;
		}
		if (waitTicks >= 0) {
			if (expectedMenu == null) {
				if (++waitTicks < 4) {
					return null;
				}
				BlockState after = mc.level.getBlockState(pos);
				String opened = player.containerMenu != player.inventoryMenu ? " A menu opened." : "";
				return "Used " + Lookup.name(after.getBlock()) + " at " + pos.toShortString() + "."
					+ (after.equals(before) ? "" : " Its state is now " + after + ".") + opened;
			}
			if (player.containerMenu != player.inventoryMenu && expectedMenu.test(player.containerMenu)) {
				return "Opened " + Lookup.name(mc.level.getBlockState(pos).getBlock()) + " at " + pos.toShortString() + ".";
			}
			if (++waitTicks > MENU_WAIT_TICKS) {
				throw new TaskFailure("Right-clicking " + Lookup.name(mc.level.getBlockState(pos).getBlock()) + " at " + pos.toShortString()
					+ " did not open the expected menu.");
			}
			return null;
		}
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir()) {
			throw new TaskFailure("There is no block at " + pos.toShortString() + ".");
		}
		if (PlayerControl.eyeDistance(player, pos) > PlayerControl.reach(player) - 0.3) {
			if (walked) {
				throw new TaskFailure("Could not get within reach of " + pos.toShortString() + ".");
			}
			walked = true;
			sub.set(mc, new WalkTask(Goal.withinReach(pos, PlayerControl.reach(player) - 0.8), pos.toShortString(), true, true, 20 * 90));
			return null;
		}
		PlayerControl.ensureNoContainerOpen(mc);
		PlayerControl.input().clear();
		before = state;
		InteractionResult result = PlayerControl.useOn(mc, pos, PlayerControl.faceTowardsPlayer(player, pos));
		if (result == InteractionResult.FAIL) {
			throw new TaskFailure("The game refused the interaction with " + Lookup.name(state.getBlock()) + ".");
		}
		waitTicks = 0;
		return null;
	}

	@Override
	public void stop(Minecraft mc) {
		sub.clear(mc);
		PlayerControl.input().clear();
	}

	@Override
	public int timeoutTicks() {
		return 20 * 120;
	}

	@Override
	public String describe() {
		String child = sub.describe();
		return child != null ? child : "using block at " + pos.toShortString();
	}
}
