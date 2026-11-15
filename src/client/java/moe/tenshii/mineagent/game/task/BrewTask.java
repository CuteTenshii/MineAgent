package moe.tenshii.mineagent.game.task;

import java.util.ArrayList;
import java.util.List;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.MenuOps;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.WorldInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BrewingStandBlock;
import org.jspecify.annotations.Nullable;

/**
 * Brews up to three bottles with one ingredient at a brewing stand, adding blaze powder when it has no fuel, and
 * collects them once done.
 */
public final class BrewTask extends StationTask<BrewingStandMenu> {
	private static final int INGREDIENT_SLOT = 3;
	private static final int FUEL_SLOT = 4;
	// The stand starts within a tick or two of getting a valid ingredient; much longer means nothing brews.
	private static final int START_WAIT_TICKS = 30;
	private static final int BREW_TICKS = 400;

	private final Lookup.ItemQuery bottles;
	private final int count;
	private final Lookup.ItemQuery ingredient;
	private boolean loaded;
	private boolean started;
	private int ticksSinceLoad;
	private boolean collecting;

	public BrewTask(Lookup.ItemQuery bottles, int count, Lookup.ItemQuery ingredient, @Nullable BlockPos pos) {
		super("brewing stand", state -> state.getBlock() instanceof BrewingStandBlock, BrewingStandMenu.class, pos);
		this.bottles = bottles;
		this.count = count;
		this.ingredient = ingredient;
	}

	@Override
	protected void checkInventory(Minecraft mc, LocalPlayer player) throws TaskFailure {
		require(player, bottles.matches(), bottles.label(), count);
		require(player, ingredient.matches(), ingredient.label(), 1);
	}

	@Override
	protected @Nullable String work(Minecraft mc, LocalPlayer player, BrewingStandMenu menu) throws TaskFailure {
		if (!loaded) {
			load(mc, player, menu);
			loaded = true;
			return null;
		}
		if (collecting) {
			List<String> brewed = new ArrayList<>();
			for (int slot = 0; slot < count; slot++) {
				ItemStack bottle = menu.getSlot(slot).getItem();
				if (!bottle.isEmpty()) {
					brewed.add(WorldInfo.stackName(bottle));
					MenuOps.takeAll(mc, menu, slot);
				}
			}
			return "Brewed " + String.join(", ", brewed) + ".";
		}
		ticksSinceLoad++;
		if (menu.getBrewingTicks() > 0) {
			started = true;
			return null;
		}
		if (started) {
			collecting = true;
			return null;
		}
		if (ticksSinceLoad > START_WAIT_TICKS) {
			for (int slot = 0; slot <= INGREDIENT_SLOT; slot++) {
				MenuOps.takeAll(mc, menu, slot);
			}
			throw new TaskFailure(ingredient.label() + " does not brew anything from " + bottles.label() + "; took the items back. "
				+ "Water bottles need nether_wart first to become awkward potions, which most effect ingredients then apply to.");
		}
		return null;
	}

	private void load(Minecraft mc, LocalPlayer player, BrewingStandMenu menu) throws TaskFailure {
		for (int slot = 0; slot <= INGREDIENT_SLOT; slot++) {
			if (menu.getSlot(slot).hasItem()) {
				throw new TaskFailure("The brewing stand already holds " + WorldInfo.stackName(menu.getSlot(slot).getItem())
					+ "; empty it with the container tool first.");
			}
		}
		// Check before loading anything, since the stand keeps whatever was put in when this fails.
		ItemStack ingredientStack = carried(player, ingredient);
		if (!menu.getSlot(INGREDIENT_SLOT).mayPlace(ingredientStack)) {
			throw new TaskFailure(ingredient.label() + " is not a brewing ingredient.");
		}
		if (!menu.getSlot(0).mayPlace(carried(player, bottles))) {
			throw new TaskFailure(bottles.label() + " cannot go in a brewing stand's bottle slots; use potions or water bottles.");
		}
		if (menu.getFuel() <= 0 && !menu.getSlot(FUEL_SLOT).hasItem()) {
			if (MenuOps.deposit(mc, player, menu, stack -> stack.is(Items.BLAZE_POWDER), FUEL_SLOT, 1) < 1) {
				throw new TaskFailure("The brewing stand has no fuel and no blaze_powder is carried.");
			}
		}
		for (int slot = 0; slot < count; slot++) {
			if (MenuOps.deposit(mc, player, menu, bottles.matches(), slot, 1) < 1) {
				throw new TaskFailure("Could not put " + bottles.label() + " into the brewing stand.");
			}
		}
		if (MenuOps.deposit(mc, player, menu, ingredient.matches(), INGREDIENT_SLOT, 1) < 1) {
			throw new TaskFailure("Could not put " + ingredient.label() + " into the brewing stand.");
		}
	}

	private static ItemStack carried(LocalPlayer player, Lookup.ItemQuery query) {
		int slot = PlayerControl.findSlot(player, query.matches());
		return slot < 0 ? ItemStack.EMPTY : player.getInventory().getItem(slot);
	}

	@Override
	public int timeoutTicks() {
		return 20 * 90 + BREW_TICKS;
	}

	@Override
	protected String describeWork() {
		return "brewing " + count + " " + bottles.label() + " with " + ingredient.label();
	}
}
