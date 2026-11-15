package moe.tenshii.mineagent.game.task;

import java.util.List;
import java.util.Map;
import moe.tenshii.mineagent.game.Inventories;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.Recipes;
import moe.tenshii.mineagent.game.SubTask;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.Workstations;
import moe.tenshii.mineagent.game.WorldInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * Crafts through the recipe book: fills the grid with a place-recipe request and shift-clicks the result.
 * Uses the inventory's 2x2 grid when possible and a nearby crafting table otherwise.
 */
public final class CraftTask implements Task {
	private static final int TABLE_SEARCH_RADIUS = 24;
	private static final int RESULT_WAIT_TICKS = 20;

	private enum Phase { PREPARE, OPEN_TABLE, PLACE, TAKE, SETTLE, CLEANUP }

	private final Item item;
	private final int wanted;
	private final SubTask sub = new SubTask();
	private Phase phase = Phase.PREPARE;
	private Recipes.@Nullable CraftingRecipe recipe;
	private @Nullable Map<Item, Integer> before;
	private int startCount;
	private int waitTicks;
	private boolean openedTable;
	private @Nullable String problem;

	public CraftTask(Item item, int count) {
		this.item = item;
		this.wanted = count;
	}

	@Override
	public @Nullable String tick(Minecraft mc) throws TaskFailure {
		LocalPlayer player = mc.player;
		PlayerControl.takeControl(mc);
		if (sub.tick(mc)) {
			return null;
		}
		return switch (phase) {
			case PREPARE -> prepare(mc, player);
			case OPEN_TABLE -> {
				if (!Workstations.isTableGrid(player.containerMenu)) {
					throw new TaskFailure("The crafting table did not open.");
				}
				openedTable = true;
				phase = Phase.PLACE;
				yield null;
			}
			case PLACE -> place(mc, player);
			case TAKE -> take(mc, player);
			case SETTLE -> {
				if (++waitTicks >= 2) {
					phase = Phase.PLACE;
				}
				yield null;
			}
			case CLEANUP -> cleanup(mc, player);
		};
	}

	private @Nullable String prepare(Minecraft mc, LocalPlayer player) throws TaskFailure {
		List<Recipes.CraftingRecipe> recipes = Recipes.craftingRecipesFor(player, item);
		if (recipes.isEmpty()) {
			throw new TaskFailure("No unlocked crafting recipe makes " + Lookup.name(item)
				+ ". Recipes unlock in the recipe book once you pick up one of their ingredients; it may also not be craftable (e.g. it is smelted or mined).");
		}
		recipe = recipes.stream().filter(r -> Recipes.canCraft(player, r)).findFirst().orElse(null);
		if (recipe == null) {
			StringBuilder message = new StringBuilder("Missing ingredients for ").append(Lookup.name(item)).append(". Known recipes need: ");
			for (int i = 0; i < recipes.size(); i++) {
				message.append(i == 0 ? "" : " OR ").append(Recipes.describeIngredients(player, recipes.get(i)))
					.append(recipes.get(i).needsTable() ? " (crafting table)" : "");
			}
			throw new TaskFailure(message.append('.').toString());
		}
		before = Inventories.snapshot(player);
		startCount = PlayerControl.countItems(player, stack -> stack.is(item));
		if (!recipe.needsTable()) {
			PlayerControl.ensureNoContainerOpen(mc);
			phase = Phase.PLACE;
			return null;
		}
		if (Workstations.isTableGrid(player.containerMenu)) {
			phase = Phase.PLACE;
			return null;
		}
		BlockPos table = WorldInfo.nearestBlock(mc.level, player.blockPosition(), TABLE_SEARCH_RADIUS, Workstations::isCraftingTable);
		if (table == null) {
			throw new TaskFailure(Lookup.name(item) + " needs a crafting table and none is within " + TABLE_SEARCH_RADIUS
				+ " blocks. Craft a crafting_table and place it nearby first.");
		}
		sub.set(mc, new UseBlockTask(table, Workstations::isTableGrid));
		phase = Phase.OPEN_TABLE;
		return null;
	}

	private @Nullable String place(Minecraft mc, LocalPlayer player) throws TaskFailure {
		int crafted = crafted(player);
		if (crafted >= wanted) {
			return finish(mc, player, null);
		}
		if (!Recipes.canCraft(player, recipe)) {
			return finish(mc, player, crafted == 0 ? "Ran out of ingredients" : "Ran out of ingredients after crafting " + crafted);
		}
		AbstractContainerMenu menu = player.containerMenu;
		if (!(menu instanceof AbstractCraftingMenu)) {
			throw new TaskFailure("The crafting grid closed unexpectedly.");
		}
		mc.gameMode.handlePlaceRecipe(menu.containerId, recipe.entry().id(), false);
		waitTicks = 0;
		phase = Phase.TAKE;
		return null;
	}

	private @Nullable String take(Minecraft mc, LocalPlayer player) throws TaskFailure {
		AbstractContainerMenu menu = player.containerMenu;
		if (!(menu instanceof AbstractCraftingMenu craftingMenu)) {
			throw new TaskFailure("The crafting grid closed unexpectedly.");
		}
		if (!craftingMenu.getResultSlot().hasItem()) {
			if (++waitTicks > RESULT_WAIT_TICKS) {
				return finish(mc, player, "The server did not fill the crafting grid");
			}
			return null;
		}
		PlayerControl.click(mc, menu, craftingMenu.getResultSlot().index, 0, ContainerInput.QUICK_MOVE);
		waitTicks = 0;
		phase = Phase.SETTLE;
		return null;
	}

	private int crafted(LocalPlayer player) {
		return PlayerControl.countItems(player, stack -> stack.is(item)) - startCount;
	}

	private @Nullable String finish(Minecraft mc, LocalPlayer player, @Nullable String problem) {
		AbstractContainerMenu menu = player.containerMenu;
		if (menu instanceof AbstractCraftingMenu craftingMenu) {
			// Leftover ingredients stay in the grid otherwise, since no screen closing will return them.
			for (Slot slot : craftingMenu.getInputGridSlots()) {
				if (slot.hasItem()) {
					PlayerControl.click(mc, menu, slot.index, 0, ContainerInput.QUICK_MOVE);
				}
			}
		}
		this.problem = problem;
		waitTicks = 0;
		phase = Phase.CLEANUP;
		return null;
	}

	private @Nullable String cleanup(Minecraft mc, LocalPlayer player) {
		if (++waitTicks < 2) {
			return null;
		}
		if (openedTable) {
			PlayerControl.ensureNoContainerOpen(mc);
		}
		int crafted = crafted(player);
		String text = problem == null
			? "Crafted " + crafted + " " + Lookup.name(item) + "."
			: problem + "; crafted " + crafted + " " + Lookup.name(item) + " of " + wanted + " requested.";
		return text + " Inventory change: " + Inventories.diff(before, Inventories.snapshot(player)) + ".";
	}

	@Override
	public void stop(Minecraft mc) {
		sub.clear(mc);
		if (openedTable) {
			PlayerControl.ensureNoContainerOpen(mc);
		}
	}

	@Override
	public int timeoutTicks() {
		return 20 * 120 + wanted * 10;
	}

	@Override
	public String describe() {
		String child = sub.describe();
		return child != null ? child : "crafting " + Lookup.name(item);
	}
}
