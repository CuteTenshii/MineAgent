package moe.tenshii.mineagent.game.task;

import java.util.Map;
import java.util.function.Predicate;
import moe.tenshii.mineagent.game.Inventories;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.SubTask;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.WorldInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Walks to a workstation block, opens it, drives its menu through {@link #work}, then closes it and reports what
 * the inventory gained and lost.
 */
abstract class StationTask<M extends AbstractContainerMenu> implements Task {
	private static final int SEARCH_RADIUS = 24;
	/** How long to wait for the server to fill a result slot or update a menu before giving up. */
	protected static final int SERVER_WAIT_TICKS = 40;
	// The server hands back leftover input items a moment after the menu closes; count the inventory after that.
	private static final int RETURN_SETTLE_TICKS = 6;

	private final String stationName;
	private final Predicate<BlockState> isStation;
	private final Class<M> menuType;
	private final @Nullable BlockPos requestedPos;
	private final SubTask sub = new SubTask();
	private @Nullable BlockPos stationPos;
	private @Nullable Map<Item, Integer> before;
	private int waitTicks;
	private @Nullable String finishedResult;
	private int settleTicks;

	protected StationTask(String stationName, Predicate<BlockState> isStation, Class<M> menuType, @Nullable BlockPos pos) {
		this.stationName = stationName;
		this.isStation = isStation;
		this.menuType = menuType;
		this.requestedPos = pos == null ? null : pos.immutable();
	}

	/** Fails before walking anywhere when the inventory lacks what the job needs. */
	protected abstract void checkInventory(Minecraft mc, LocalPlayer player) throws TaskFailure;

	/**
	 * Advances the job by one tick with the station's menu open.
	 *
	 * @return null while still working, or a description of what was done
	 */
	protected abstract @Nullable String work(Minecraft mc, LocalPlayer player, M menu) throws TaskFailure;

	/** Present-tense description of the job, e.g. "enchanting a diamond sword". */
	protected abstract String describeWork();

	@Override
	public final @Nullable String tick(Minecraft mc) throws TaskFailure {
		LocalPlayer player = mc.player;
		PlayerControl.takeControl(mc);
		if (sub.tick(mc)) {
			return null;
		}
		if (finishedResult != null) {
			if (++settleTicks < RETURN_SETTLE_TICKS) {
				return null;
			}
			return finishedResult + " Inventory change: " + Inventories.diff(before, Inventories.snapshot(player)) + ".";
		}
		if (stationPos == null) {
			checkInventory(mc, player);
			stationPos = requestedPos != null ? requestedPos : WorldInfo.nearestBlock(mc.level, player.blockPosition(), SEARCH_RADIUS, isStation);
			if (stationPos == null) {
				throw new TaskFailure("No " + stationName + " within " + SEARCH_RADIUS + " blocks. Craft one and place it, or walk closer to one.");
			}
			if (!isStation.test(mc.level.getBlockState(stationPos))) {
				throw new TaskFailure("The block at " + stationPos.toShortString() + " is not a " + stationName + ".");
			}
			before = Inventories.snapshot(player);
			sub.set(mc, new UseBlockTask(stationPos, menuType::isInstance));
			return null;
		}
		if (!menuType.isInstance(player.containerMenu)) {
			throw new TaskFailure("The " + stationName + " at " + stationPos.toShortString() + " closed unexpectedly.");
		}
		String result = work(mc, player, menuType.cast(player.containerMenu));
		if (result == null) {
			return null;
		}
		PlayerControl.ensureNoContainerOpen(mc);
		finishedResult = result;
		return null;
	}

	/**
	 * Counts ticks spent waiting for the server.
	 *
	 * @return true once {@link #SERVER_WAIT_TICKS} have passed since the last {@link #resetWait()}
	 */
	protected final boolean waitedTooLong() {
		return ++waitTicks > SERVER_WAIT_TICKS;
	}

	protected final void resetWait() {
		waitTicks = 0;
	}

	protected final String stationName() {
		return stationName;
	}

	/** Fails unless at least {@code amount} matching items are carried. */
	protected static void require(LocalPlayer player, Predicate<ItemStack> matches, String label, int amount)
		throws TaskFailure {
		int carried = PlayerControl.countItems(player, matches);
		if (carried < amount) {
			throw new TaskFailure("Needs " + amount + " " + label + " but only " + carried + " carried.");
		}
	}

	@Override
	public void stop(Minecraft mc) {
		sub.clear(mc);
		// Closing returns anything left in the station's input slots, except for block entities such as brewing stands.
		PlayerControl.ensureNoContainerOpen(mc);
	}

	@Override
	public int timeoutTicks() {
		return 20 * 90;
	}

	@Override
	public final String describe() {
		String child = sub.describe();
		return child != null ? child : describeWork();
	}
}
