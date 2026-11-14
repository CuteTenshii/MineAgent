package moe.tenshii.mineagent.game.task;

import java.util.LinkedHashMap;
import java.util.Map;
import moe.tenshii.mineagent.game.Inventories;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.SubTask;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * Opens a container block and lists it, or moves whole stacks of an item in or out with shift-clicks.
 */
public final class ContainerTask implements Task {
	public enum Action { LIST, TAKE, PUT }

	private final BlockPos pos;
	private final Action action;
	private final Lookup.@Nullable ItemQuery item;
	private final int count;
	private final SubTask sub = new SubTask();
	private boolean opened;
	private boolean moved;
	private int settleTicks;
	private @Nullable Map<Item, Integer> before;

	public ContainerTask(BlockPos pos, Action action, Lookup.@Nullable ItemQuery item, int count) {
		this.pos = pos.immutable();
		this.action = action;
		this.item = item;
		this.count = count;
	}

	@Override
	public @Nullable String tick(Minecraft mc) throws TaskFailure {
		LocalPlayer player = mc.player;
		PlayerControl.takeControl(mc);
		if (sub.tick(mc)) {
			return null;
		}
		if (!opened) {
			opened = true;
			before = Inventories.snapshot(player);
			sub.set(mc, new UseBlockTask(pos, menu -> true));
			return null;
		}
		AbstractContainerMenu menu = player.containerMenu;
		if (menu == player.inventoryMenu) {
			throw new TaskFailure("The container at " + pos.toShortString() + " closed unexpectedly.");
		}
		if (!moved) {
			moved = true;
			if (action == Action.TAKE) {
				transfer(mc, player, menu, false);
			} else if (action == Action.PUT) {
				transfer(mc, player, menu, true);
			}
			return null;
		}
		if (++settleTicks < 3) {
			return null;
		}
		String contents = describeContainer(player, menu);
		PlayerControl.ensureNoContainerOpen(mc);
		String change = Inventories.diff(before, Inventories.snapshot(player));
		return switch (action) {
			case LIST -> "Container at " + pos.toShortString() + " holds: " + contents + ".";
			case TAKE, PUT -> "Inventory change: " + change + ". Container now holds: " + contents + ".";
		};
	}

	private void transfer(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, boolean intoContainer) throws TaskFailure {
		int movedCount = 0;
		for (Slot slot : menu.slots) {
			if (movedCount >= count) {
				break;
			}
			boolean playerSlot = slot.container == player.getInventory();
			if (playerSlot != intoContainer || !slot.hasItem() || !item.matches().test(slot.getItem())) {
				continue;
			}
			int stackSize = slot.getItem().getCount();
			PlayerControl.click(mc, menu, slot.index, 0, ContainerInput.QUICK_MOVE);
			movedCount += stackSize - (slot.hasItem() ? slot.getItem().getCount() : 0);
		}
		if (movedCount == 0) {
			throw new TaskFailure(intoContainer
				? "Could not put any " + item.label() + " in: none carried, or the container is full."
				: "Could not take any " + item.label() + ": the container has none, or the inventory is full.");
		}
	}

	private static String describeContainer(LocalPlayer player, AbstractContainerMenu menu) {
		Map<Item, Integer> counts = new LinkedHashMap<>();
		int free = 0;
		int total = 0;
		for (Slot slot : menu.slots) {
			if (slot.container == player.getInventory()) {
				continue;
			}
			total++;
			if (slot.hasItem()) {
				Inventories.add(counts, slot.getItem());
			} else {
				free++;
			}
		}
		return Inventories.describe(counts) + " (" + free + " of " + total + " slots free)";
	}

	@Override
	public void stop(Minecraft mc) {
		sub.clear(mc);
		PlayerControl.ensureNoContainerOpen(mc);
	}

	@Override
	public int timeoutTicks() {
		return 20 * 120;
	}

	@Override
	public String describe() {
		String child = sub.describe();
		return child != null ? child : "using container at " + pos.toShortString();
	}
}
