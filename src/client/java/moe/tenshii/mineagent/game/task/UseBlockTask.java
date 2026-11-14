package moe.tenshii.mineagent.game.task;

import java.util.Map;
import java.util.function.Predicate;
import moe.tenshii.mineagent.game.Inventories;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.SubTask;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.WorldInfo;
import moe.tenshii.mineagent.path.Goal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Walks to where a block is in reach and in clear view, then right-clicks it with the held item,
 * optionally waiting for a container menu to open.
 */
public final class UseBlockTask implements Task {
	private static final int MENU_WAIT_TICKS = 40;

	private final BlockPos pos;
	private final @Nullable Predicate<AbstractContainerMenu> expectedMenu;
	private static final int MAX_APPROACHES = 2;

	private final SubTask sub = new SubTask();
	private int approaches;
	private int waitTicks = -1;
	private @Nullable BlockState before;
	private @Nullable Map<Item, Integer> inventoryBefore;
	private ItemStack heldBefore = ItemStack.EMPTY;

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
				return "Right-clicked " + Lookup.name(before.getBlock()) + " at " + pos.toShortString() + " holding "
					+ WorldInfo.stackName(heldBefore) + "."
					+ (after.equals(before) ? " The block did not change." : " The block is now " + after + ".") + opened
					+ " Inventory change: " + Inventories.diff(inventoryBefore, Inventories.snapshot(player)) + ".";
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
		Vec3 aim = PlayerControl.eyeDistance(player, pos) > PlayerControl.reach(player) - 0.3 ? null : PlayerControl.visiblePoint(mc, pos);
		if (aim == null) {
			if (approaches >= MAX_APPROACHES) {
				throw new TaskFailure("Could not get a clear line of sight to " + Lookup.name(state.getBlock()) + " at " + pos.toShortString()
					+ " within reach; something is in the way. Mine the obstruction or stand elsewhere.");
			}
			// The second approach gets much closer, which usually clears whatever blocked the view.
			double reach = approaches == 0 ? PlayerControl.reach(player) - 0.8 : 2.0;
			approaches++;
			sub.set(mc, new WalkTask(Goal.withinReach(pos, reach), pos.toShortString(), true, true, 20 * 90));
			return null;
		}
		PlayerControl.ensureNoContainerOpen(mc);
		PlayerControl.input().clear();
		before = state;
		heldBefore = player.getMainHandItem().copy();
		inventoryBefore = Inventories.snapshot(player);
		InteractionResult result = PlayerControl.rightClick(mc, aim);
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
