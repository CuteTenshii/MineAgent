package moe.tenshii.mineagent.game;

import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Moving items between the player's inventory and an open menu with ordinary clicks. Client thread only.
 */
public final class MenuOps {
	private MenuOps() {
	}

	/**
	 * Picks stacks up and right-clicks single items into {@code target} so the exact amount lands there.
	 *
	 * @return how many items were placed
	 */
	public static int deposit(Minecraft mc, LocalPlayer player, AbstractContainerMenu menu, Predicate<ItemStack> matches, int target, int amount) {
		int placed = 0;
		for (Slot slot : menu.slots) {
			if (placed >= amount) {
				break;
			}
			if (slot.container != player.getInventory() || !slot.hasItem() || !matches.test(slot.getItem())) {
				continue;
			}
			PlayerControl.click(mc, menu, slot.index, 0, ContainerInput.PICKUP);
			while (placed < amount && !menu.getCarried().isEmpty()) {
				int carriedBefore = menu.getCarried().getCount();
				PlayerControl.click(mc, menu, target, 1, ContainerInput.PICKUP);
				if (menu.getCarried().getCount() == carriedBefore) {
					break;
				}
				placed++;
			}
			if (!menu.getCarried().isEmpty()) {
				PlayerControl.click(mc, menu, slot.index, 0, ContainerInput.PICKUP);
			}
		}
		return placed;
	}

	/** Shift-clicks a menu slot into the inventory. */
	public static void takeAll(Minecraft mc, AbstractContainerMenu menu, int slot) {
		if (menu.getSlot(slot).hasItem()) {
			PlayerControl.click(mc, menu, slot, 0, ContainerInput.QUICK_MOVE);
		}
	}
}
