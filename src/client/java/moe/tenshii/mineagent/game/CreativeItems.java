package moe.tenshii.mineagent.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Takes items out of the creative menu the way the creative inventory screen does. Client thread only.
 */
public final class CreativeItems {
	private CreativeItems() {
	}

	/**
	 * Puts {@code count} of {@code item} into {@code slot}, replacing what was there, or into the first empty slot
	 * (hotbar first) when {@code slot} is null.
	 *
	 * @throws GameThread.GameStateException outside creative mode, for disabled items, or with a full inventory
	 */
	public static String give(Minecraft mc, Item item, int count, InventoryOps.@Nullable SlotRef slot) {
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null || player.connection == null) {
			throw new GameThread.GameStateException("Not in a world.");
		}
		if (!player.hasInfiniteMaterials()) {
			throw new GameThread.GameStateException("give_item only works in creative mode (game mode is "
				+ mc.gameMode.getPlayerMode().getName() + "). Gather or craft the item instead"
				+ " (or use run_command with give, if commands are enabled and allowed).");
		}
		if (!player.connection.isFeatureEnabled(item.requiredFeatures())) {
			throw new GameThread.GameStateException(Lookup.name(item) + " is not enabled in this world.");
		}
		ItemStack stack = new ItemStack(item, count);
		if (count > stack.getMaxStackSize()) {
			throw new GameThread.GameStateException(Lookup.name(item) + " stacks to " + stack.getMaxStackSize()
				+ "; give at most that many per call.");
		}
		InventoryOps.SlotRef target = slot != null ? slot : firstEmptySlot(player);
		if (target == null) {
			throw new GameThread.GameStateException("The inventory is full. Name a slot to replace with \"to\" (its item is deleted),"
				+ " or free a slot first.");
		}
		ItemStack replaced = target.stack(player).copy();
		int menuSlot = target.menuSlot();
		// The server records the slot as already matching the client, so it never sends it back: set it here too.
		player.inventoryMenu.getSlot(menuSlot).set(stack.copy());
		mc.gameMode.handleCreativeModeItemAdd(stack, menuSlot);
		String text = "Put " + WorldInfo.stackName(stack) + " in " + target.label();
		return replaced.isEmpty() ? text + "." : text + ", replacing " + WorldInfo.stackName(replaced) + ".";
	}

	private static InventoryOps.@Nullable SlotRef firstEmptySlot(LocalPlayer player) {
		Inventory inventory = player.getInventory();
		for (int index = 0; index < Inventory.INVENTORY_SIZE; index++) {
			if (inventory.getItem(index).isEmpty()) {
				return new InventoryOps.SlotRef(index, false);
			}
		}
		return null;
	}
}
