package moe.tenshii.mineagent.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import moe.tenshii.mineagent.game.task.EatTask;
import moe.tenshii.mineagent.game.task.WalkTask;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Slot-level inventory listing and rearranging, done with the same clicks a player makes in the inventory screen.
 * Slots are named for the model as "hotbar 1".."hotbar 9", "inv 1".."inv 27" and "offhand". Client thread only.
 */
public final class InventoryOps {
	private static final int HOTBAR_SIZE = Inventory.getSelectionSize();
	private static final int MAIN_SIZE = Inventory.INVENTORY_SIZE - HOTBAR_SIZE;
	// The swap-click button that targets the offhand instead of a hotbar slot.
	private static final int OFFHAND_SWAP_BUTTON = 40;

	private InventoryOps() {
	}

	/** A player inventory slot: index 0-8 hotbar, 9-35 main inventory, or the offhand. */
	public record SlotRef(int index, boolean offhand) {
		static final SlotRef OFFHAND = new SlotRef(-1, true);

		int menuSlot() {
			return offhand ? InventoryMenu.SHIELD_SLOT : PlayerControl.inventoryMenuSlot(index);
		}

		public ItemStack stack(LocalPlayer player) {
			return offhand ? player.getOffhandItem() : player.getInventory().getItem(index);
		}

		boolean isHotbar() {
			return !offhand && index < HOTBAR_SIZE;
		}

		public String label() {
			if (offhand) {
				return "offhand";
			}
			return index < HOTBAR_SIZE ? "hotbar " + (index + 1) : "inv " + (index - HOTBAR_SIZE + 1);
		}
	}

	public static SlotRef parseSlot(String raw) throws TaskFailure {
		String text = raw.strip().toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ");
		if (text.equals("offhand") || text.equals("off hand")) {
			return SlotRef.OFFHAND;
		}
		String[] parts = text.split(" ");
		if (parts.length == 2) {
			try {
				int number = Integer.parseInt(parts[1]);
				if (parts[0].equals("hotbar") && number >= 1 && number <= HOTBAR_SIZE) {
					return new SlotRef(number - 1, false);
				}
				if (parts[0].equals("inv") && number >= 1 && number <= MAIN_SIZE) {
					return new SlotRef(HOTBAR_SIZE + number - 1, false);
				}
			} catch (NumberFormatException ignored) {
				// Reported below with the accepted formats.
			}
		}
		throw new TaskFailure("Unknown slot '" + raw + "'. Use \"hotbar 1\"-\"hotbar 9\", \"inv 1\"-\"inv " + MAIN_SIZE + "\" or \"offhand\".");
	}

	public static String list(LocalPlayer player) {
		StringBuilder text = new StringBuilder();
		List<Integer> freeHotbar = new ArrayList<>();
		List<Integer> freeMain = new ArrayList<>();
		for (int index = 0; index < Inventory.INVENTORY_SIZE; index++) {
			SlotRef slot = new SlotRef(index, false);
			ItemStack stack = slot.stack(player);
			if (stack.isEmpty()) {
				if (index < HOTBAR_SIZE) {
					freeHotbar.add(index + 1);
				} else {
					freeMain.add(index - HOTBAR_SIZE + 1);
				}
				continue;
			}
			text.append(slot.label()).append(": ").append(WorldInfo.stackName(stack));
			if (index == player.getInventory().getSelectedSlot()) {
				text.append(" (held)");
			}
			text.append('\n');
		}
		text.append("offhand: ").append(WorldInfo.stackName(player.getOffhandItem())).append('\n');
		for (EquipmentSlot armor : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			text.append(armor.getName()).append(": ").append(WorldInfo.stackName(player.getItemBySlot(armor))).append('\n');
		}
		List<String> free = new ArrayList<>();
		if (!freeHotbar.isEmpty()) {
			free.add("hotbar " + ranges(freeHotbar));
		}
		if (!freeMain.isEmpty()) {
			free.add("inv " + ranges(freeMain));
		}
		text.append("Empty slots: ").append(free.isEmpty() ? "none" : String.join("; ", free));
		return text.toString();
	}

	/** [1, 2, 3, 5, 8, 9] becomes "1-3, 5, 8-9". */
	private static String ranges(List<Integer> numbers) {
		List<String> parts = new ArrayList<>();
		int start = numbers.getFirst();
		int previous = start;
		for (int i = 1; i <= numbers.size(); i++) {
			int current = i < numbers.size() ? numbers.get(i) : Integer.MIN_VALUE;
			if (current == previous + 1) {
				previous = current;
				continue;
			}
			parts.add(start == previous ? Integer.toString(start) : start + "-" + previous);
			start = current;
			previous = current;
		}
		return String.join(", ", parts);
	}

	/** Swaps the contents of two slots. */
	public static void swap(Minecraft mc, SlotRef from, SlotRef to) {
		LocalPlayer player = mc.player;
		if (from.equals(to)) {
			return;
		}
		PlayerControl.ensureNoContainerOpen(mc);
		InventoryMenu menu = player.inventoryMenu;
		// Swap-clicks move items without leaving anything on the cursor, but only target the hotbar or offhand.
		if (to.isHotbar()) {
			PlayerControl.click(mc, menu, from.menuSlot(), to.index(), ContainerInput.SWAP);
		} else if (from.isHotbar()) {
			PlayerControl.click(mc, menu, to.menuSlot(), from.index(), ContainerInput.SWAP);
		} else if (to.offhand()) {
			PlayerControl.click(mc, menu, from.menuSlot(), OFFHAND_SWAP_BUTTON, ContainerInput.SWAP);
		} else if (from.offhand()) {
			PlayerControl.click(mc, menu, to.menuSlot(), OFFHAND_SWAP_BUTTON, ContainerInput.SWAP);
		} else {
			PlayerControl.click(mc, menu, from.menuSlot(), 0, ContainerInput.PICKUP);
			PlayerControl.click(mc, menu, to.menuSlot(), 0, ContainerInput.PICKUP);
			if (!menu.getCarried().isEmpty()) {
				PlayerControl.click(mc, menu, from.menuSlot(), 0, ContainerInput.PICKUP);
			}
		}
	}

	public static @Nullable SlotRef find(LocalPlayer player, Predicate<ItemStack> matches) {
		for (int index = 0; index < Inventory.INVENTORY_SIZE; index++) {
			if (matches.test(player.getInventory().getItem(index))) {
				return new SlotRef(index, false);
			}
		}
		return matches.test(player.getOffhandItem()) ? SlotRef.OFFHAND : null;
	}

	/**
	 * Merges partial stacks, then puts the most useful items in fixed hotbar slots:
	 * 1 weapon, 2 pickaxe, 3 axe, 4 shovel, 5 food, 6 torches, 7 building blocks, 8 water bucket.
	 */
	public static String tidy(Minecraft mc) {
		LocalPlayer player = mc.player;
		PlayerControl.ensureNoContainerOpen(mc);
		int merged = mergeStacks(mc);
		record Role(String name, Predicate<ItemStack> matches, Comparator<ItemStack> better) {
		}
		Comparator<ItemStack> durability = Comparator.comparingInt(ItemStack::getMaxDamage);
		Comparator<ItemStack> count = Comparator.comparingInt(ItemStack::getCount);
		List<Role> roles = List.of(
			new Role("weapon", stack -> stack.is(ItemTags.SWORDS), durability),
			new Role("pickaxe", stack -> stack.is(ItemTags.PICKAXES), durability),
			new Role("axe", stack -> stack.is(ItemTags.AXES), durability),
			new Role("shovel", stack -> stack.is(ItemTags.SHOVELS), durability),
			new Role("food", stack -> stack.has(DataComponents.FOOD) && !EatTask.HARMFUL.contains(stack.getItem()),
				Comparator.comparingInt(stack -> stack.get(DataComponents.FOOD).nutrition() * stack.getCount())),
			new Role("torches", stack -> stack.is(Items.TORCH), count),
			new Role("building blocks", WalkTask::isScaffoldBlock, count),
			new Role("water bucket", stack -> stack.is(Items.WATER_BUCKET), count));
		Set<Integer> placed = new HashSet<>();
		List<String> moves = new ArrayList<>();
		for (int hotbar = 0; hotbar < roles.size(); hotbar++) {
			Role role = roles.get(hotbar);
			int best = -1;
			for (int index = 0; index < Inventory.INVENTORY_SIZE; index++) {
				ItemStack stack = player.getInventory().getItem(index);
				if (placed.contains(index) || stack.isEmpty() || !role.matches().test(stack)) {
					continue;
				}
				if (best < 0 || role.better().compare(stack, player.getInventory().getItem(best)) > 0) {
					best = index;
				}
			}
			if (best < 0) {
				continue;
			}
			if (best != hotbar) {
				swap(mc, new SlotRef(best, false), new SlotRef(hotbar, false));
				moves.add(role.name() + " -> hotbar " + (hotbar + 1));
			}
			placed.add(hotbar);
		}
		String summary = (merged > 0 ? "Merged " + merged + (merged == 1 ? " partial stack. " : " partial stacks. ") : "")
			+ (moves.isEmpty() ? "The hotbar was already in order." : "Moved " + String.join(", ", moves) + ".");
		return summary + "\n" + list(player);
	}

	/** Combines partial stacks of the same item; returns how many stacks were emptied. */
	private static int mergeStacks(Minecraft mc) {
		LocalPlayer player = mc.player;
		InventoryMenu menu = player.inventoryMenu;
		Inventory inventory = player.getInventory();
		int emptied = 0;
		for (int target = 0; target < Inventory.INVENTORY_SIZE; target++) {
			for (int source = target + 1; source < Inventory.INVENTORY_SIZE; source++) {
				ItemStack into = inventory.getItem(target);
				ItemStack from = inventory.getItem(source);
				if (into.isEmpty() || from.isEmpty() || into.getCount() >= into.getMaxStackSize()
					|| !ItemStack.isSameItemSameComponents(into, from)) {
					continue;
				}
				int sourceMenu = PlayerControl.inventoryMenuSlot(source);
				PlayerControl.click(mc, menu, sourceMenu, 0, ContainerInput.PICKUP);
				PlayerControl.click(mc, menu, PlayerControl.inventoryMenuSlot(target), 0, ContainerInput.PICKUP);
				if (!menu.getCarried().isEmpty()) {
					PlayerControl.click(mc, menu, sourceMenu, 0, ContainerInput.PICKUP);
				} else {
					emptied++;
				}
			}
		}
		return emptied;
	}
}
