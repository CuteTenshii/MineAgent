package moe.tenshii.mineagent.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Item tallies for describing inventories and what an action gained or used.
 */
public final class Inventories {
	private Inventories() {
	}

	public static Map<Item, Integer> snapshot(LocalPlayer player) {
		Map<Item, Integer> counts = new LinkedHashMap<>();
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			add(counts, inventory.getItem(slot));
		}
		add(counts, player.getOffhandItem());
		return counts;
	}

	public static void add(Map<Item, Integer> counts, ItemStack stack) {
		if (!stack.isEmpty()) {
			counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
		}
	}

	/** "+3 cobblestone, -1 wooden_pickaxe", or "no change". */
	public static String diff(Map<Item, Integer> before, Map<Item, Integer> after) {
		List<String> parts = new ArrayList<>();
		for (Map.Entry<Item, Integer> entry : after.entrySet()) {
			int delta = entry.getValue() - before.getOrDefault(entry.getKey(), 0);
			if (delta != 0) {
				parts.add((delta > 0 ? "+" : "") + delta + " " + Lookup.name(entry.getKey()));
			}
		}
		for (Map.Entry<Item, Integer> entry : before.entrySet()) {
			if (!after.containsKey(entry.getKey())) {
				parts.add("-" + entry.getValue() + " " + Lookup.name(entry.getKey()));
			}
		}
		return parts.isEmpty() ? "no change" : String.join(", ", parts);
	}

	public static String describe(Map<Item, Integer> counts) {
		if (counts.isEmpty()) {
			return "empty";
		}
		List<String> parts = new ArrayList<>();
		counts.forEach((item, count) -> parts.add(Lookup.name(item) + " x" + count));
		return String.join(", ", parts);
	}
}
