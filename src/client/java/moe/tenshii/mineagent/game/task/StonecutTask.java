package moe.tenshii.mineagent.game.task;

import java.util.List;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.MenuOps;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.TaskFailure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Cuts blocks into stairs, slabs, walls and other shapes at a stonecutter.
 */
public final class StonecutTask extends StationTask<StonecutterMenu> {
	private final Item output;
	private final int count;
	private @Nullable Item input;
	private int inputsNeeded;
	private boolean loaded;
	private boolean selected;
	private int cut;

	public StonecutTask(Item output, int count, @Nullable BlockPos pos) {
		super("stonecutter", state -> state.is(Blocks.STONECUTTER), StonecutterMenu.class, pos);
		this.output = output;
		this.count = count;
	}

	@Override
	protected void checkInventory(Minecraft mc, LocalPlayer player) throws TaskFailure {
		ContextMap context = SlotDisplayContext.fromLevel(mc.level);
		List<SelectableRecipe.SingleInputEntry<StonecutterRecipe>> recipes = mc.level.recipeAccess().stonecutterRecipes().entries().stream()
			.filter(entry -> entry.recipe().optionDisplay().resolveForFirstStack(context).is(output))
			.toList();
		if (recipes.isEmpty()) {
			throw new TaskFailure("No stonecutter recipe makes " + Lookup.name(output) + ".");
		}
		StringBuilder options = new StringBuilder();
		for (SelectableRecipe.SingleInputEntry<StonecutterRecipe> entry : recipes) {
			int perInput = Math.max(1, entry.recipe().optionDisplay().resolveForFirstStack(context).getCount());
			int needed = (count + perInput - 1) / perInput;
			int slot = PlayerControl.findSlot(player, stack -> entry.input().test(stack));
			if (slot >= 0) {
				Item candidate = player.getInventory().getItem(slot).getItem();
				int carried = PlayerControl.countItems(player, stack -> stack.is(candidate));
				if (carried >= needed) {
					input = candidate;
					inputsNeeded = needed;
					return;
				}
				options.append(options.isEmpty() ? "" : "; ").append(needed).append(' ').append(Lookup.name(candidate))
					.append(" (carrying ").append(carried).append(')');
			}
		}
		throw new TaskFailure(options.isEmpty()
			? "Carrying nothing a stonecutter can turn into " + Lookup.name(output) + "."
			: "Not enough input to cut " + count + " " + Lookup.name(output) + ": needs " + options + ".");
	}

	@Override
	protected @Nullable String work(Minecraft mc, LocalPlayer player, StonecutterMenu menu) throws TaskFailure {
		if (!loaded) {
			ItemStack existing = menu.getSlot(StonecutterMenu.INPUT_SLOT).getItem();
			if (!existing.isEmpty()) {
				throw new TaskFailure("The stonecutter's input slot is not empty.");
			}
			loaded = true;
			int placed = MenuOps.deposit(mc, player, menu, stack -> stack.is(input), StonecutterMenu.INPUT_SLOT, inputsNeeded);
			if (placed < inputsNeeded) {
				throw new TaskFailure("Could only load " + placed + " of " + inputsNeeded + " " + Lookup.name(input) + " into the stonecutter.");
			}
			resetWait();
			return null;
		}
		if (!selected) {
			int index = recipeIndex(mc, menu);
			if (index < 0) {
				if (waitedTooLong()) {
					throw new TaskFailure("The stonecutter offered no option for " + Lookup.name(output) + " from " + Lookup.name(input) + ".");
				}
				return null;
			}
			selected = true;
			menu.clickMenuButton(player, index);
			mc.gameMode.handleInventoryButtonClick(menu.containerId, index);
			resetWait();
			return null;
		}
		ItemStack result = menu.getSlot(StonecutterMenu.RESULT_SLOT).getItem();
		if (!result.isEmpty()) {
			int before = PlayerControl.countItems(player, stack -> stack.is(output));
			MenuOps.takeAll(mc, menu, StonecutterMenu.RESULT_SLOT);
			cut += PlayerControl.countItems(player, stack -> stack.is(output)) - before;
			resetWait();
		}
		if (!menu.getSlot(StonecutterMenu.INPUT_SLOT).hasItem() && !menu.getSlot(StonecutterMenu.RESULT_SLOT).hasItem()) {
			return "Cut " + inputsNeeded + " " + Lookup.name(input) + " into " + cut + " " + Lookup.name(output) + ".";
		}
		if (waitedTooLong()) {
			return "Stopped after cutting " + cut + " " + Lookup.name(output) + ": the stonecutter stopped producing (is the inventory full?).";
		}
		return null;
	}

	private int recipeIndex(Minecraft mc, StonecutterMenu menu) {
		ContextMap context = SlotDisplayContext.fromLevel(mc.level);
		List<SelectableRecipe.SingleInputEntry<StonecutterRecipe>> visible = menu.getVisibleRecipes().entries();
		for (int index = 0; index < visible.size(); index++) {
			if (visible.get(index).recipe().optionDisplay().resolveForFirstStack(context).is(output)) {
				return index;
			}
		}
		return -1;
	}

	@Override
	protected String describeWork() {
		return "cutting " + Lookup.name(output) + " (" + cut + "/" + count + ")";
	}
}
