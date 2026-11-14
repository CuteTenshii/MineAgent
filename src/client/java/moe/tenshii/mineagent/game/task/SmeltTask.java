package moe.tenshii.mineagent.game.task;

import java.util.Comparator;
import java.util.Map;
import java.util.function.Predicate;
import moe.tenshii.mineagent.game.Inventories;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.SubTask;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.WorldInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import org.jspecify.annotations.Nullable;

/**
 * Loads a furnace-type block with an exact number of items plus enough fuel, then waits and collects the output.
 */
public final class SmeltTask implements Task {
	private static final int INPUT_SLOT = 0;
	private static final int FUEL_SLOT = 1;
	private static final int RESULT_SLOT = 2;
	private static final int SEARCH_RADIUS = 24;
	private static final int POLL_TICKS = 10;
	private static final int IDLE_GIVE_UP_TICKS = 20 * 15;

	private final Lookup.ItemQuery input;
	private final Lookup.@Nullable ItemQuery fuel;
	private final int count;
	private final @Nullable BlockPos requestedPos;
	private final SubTask sub = new SubTask();
	private @Nullable BlockPos furnacePos;
	private @Nullable Map<Item, Integer> before;
	private boolean loaded;
	private int collected;
	private int ticks;
	private int idleTicks;
	private int cookTicksPerItem = 200;

	public SmeltTask(Lookup.ItemQuery input, Lookup.@Nullable ItemQuery fuel, int count, @Nullable BlockPos pos) {
		this.input = input;
		this.fuel = fuel;
		this.count = count;
		this.requestedPos = pos;
	}

	@Override
	public @Nullable String tick(Minecraft mc) throws TaskFailure {
		LocalPlayer player = mc.player;
		PlayerControl.takeControl(mc);
		if (sub.tick(mc)) {
			return null;
		}
		if (furnacePos == null) {
			if (PlayerControl.countItems(player, input.matches()) < count) {
				throw new TaskFailure("Only " + PlayerControl.countItems(player, input.matches()) + " " + input.label() + " carried; " + count + " requested.");
			}
			furnacePos = requestedPos != null ? requestedPos
				: WorldInfo.nearestBlock(mc.level, player.blockPosition(), SEARCH_RADIUS, state -> state.getBlock() instanceof AbstractFurnaceBlock);
			if (furnacePos == null) {
				throw new TaskFailure("No furnace, smoker or blast furnace within " + SEARCH_RADIUS + " blocks. Craft a furnace (8 cobblestone) and place it.");
			}
			if (!(mc.level.getBlockState(furnacePos).getBlock() instanceof AbstractFurnaceBlock)) {
				throw new TaskFailure("The block at " + furnacePos.toShortString() + " is not a furnace.");
			}
			cookTicksPerItem = mc.level.getBlockState(furnacePos).getBlock() instanceof FurnaceBlock ? 200 : 100;
			before = Inventories.snapshot(player);
			sub.set(mc, new UseBlockTask(furnacePos, menu -> menu instanceof AbstractFurnaceMenu));
			return null;
		}
		if (!(player.containerMenu instanceof AbstractFurnaceMenu menu)) {
			throw new TaskFailure("The furnace closed unexpectedly after collecting " + collected + " items.");
		}
		if (!loaded) {
			loaded = true;
			load(mc, player, menu);
			return null;
		}
		ticks++;
		if (ticks % POLL_TICKS != 0) {
			return null;
		}
		Slot result = menu.getSlot(RESULT_SLOT);
		if (result.hasItem()) {
			int amount = result.getItem().getCount();
			PlayerControl.click(mc, menu, RESULT_SLOT, 0, ContainerInput.QUICK_MOVE);
			collected += amount - (result.hasItem() ? result.getItem().getCount() : 0);
			idleTicks = 0;
		}
		boolean inputLeft = menu.getSlot(INPUT_SLOT).hasItem();
		if (collected >= count || !inputLeft && !result.hasItem()) {
			return finish(mc, player, null);
		}
		if (!menu.isLit()) {
			idleTicks += POLL_TICKS;
			if (idleTicks > IDLE_GIVE_UP_TICKS) {
				return finish(mc, player, menu.getSlot(FUEL_SLOT).hasItem()
					? "The furnace stopped making progress (the item may not be smeltable)"
					: "The furnace ran out of fuel");
			}
		}
		return null;
	}

	private void load(Minecraft mc, LocalPlayer player, AbstractFurnaceMenu menu) throws TaskFailure {
		ItemStack existing = menu.getSlot(INPUT_SLOT).getItem();
		if (!existing.isEmpty() && !input.matches().test(existing)) {
			throw new TaskFailure("The furnace input already holds " + existing.getCount() + " " + Lookup.name(existing.getItem())
				+ "; take it out with the container tool first.");
		}
		if (menu.getSlot(RESULT_SLOT).hasItem()) {
			PlayerControl.click(mc, menu, RESULT_SLOT, 0, ContainerInput.QUICK_MOVE);
		}
		int placed = deposit(mc, player, menu, input.matches(), INPUT_SLOT, count);
		if (placed < count) {
			throw new TaskFailure("Could only load " + placed + " of " + count + " " + input.label() + " into the furnace.");
		}
		double smeltsNeeded = count - smeltsFrom(menu.getSlot(FUEL_SLOT).getItem());
		if (smeltsNeeded <= 0) {
			return;
		}
		Predicate<ItemStack> fuelFilter = fuel != null
			? stack -> fuel.matches().test(stack) && isFuel(stack)
			: stack -> isFuel(stack) && !stack.is(Items.LAVA_BUCKET) && !input.matches().test(stack);
		int slot = PlayerControl.findBestSlot(player, fuelFilter, Comparator.comparingDouble(SmeltTask::smeltsPerItem));
		if (slot < 0) {
			throw new TaskFailure(fuel != null ? "No " + fuel.label() + " to burn." : "No fuel in the inventory (coal, charcoal, planks, logs...).");
		}
		ItemStack fuelStack = player.getInventory().getItem(slot);
		Item fuelItem = fuelStack.getItem();
		int fuelNeeded = (int) Math.ceil(smeltsNeeded / smeltsPerItem(fuelStack));
		ItemStack fuelInSlot = menu.getSlot(FUEL_SLOT).getItem();
		if (!fuelInSlot.isEmpty() && !fuelInSlot.is(fuelItem)) {
			return;
		}
		deposit(mc, player, menu, stack -> stack.is(fuelItem), FUEL_SLOT, fuelNeeded);
	}

	private static boolean isFuel(ItemStack stack) {
		return stack.has(DataComponents.COOKING_FUEL);
	}

	/** Items one unit of fuel smelts; unknown fuels are assumed weak so the furnace is never under-fuelled. */
	private static double smeltsPerItem(ItemStack stack) {
		if (stack.is(Items.COAL_BLOCK)) {
			return 80.0;
		}
		if (stack.is(Items.DRIED_KELP_BLOCK)) {
			return 20.0;
		}
		if (stack.is(Items.BLAZE_ROD)) {
			return 12.0;
		}
		if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL)) {
			return 8.0;
		}
		if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS)) {
			return 1.5;
		}
		if (stack.is(ItemTags.WOODEN_SLABS)) {
			return 0.75;
		}
		return 0.5;
	}

	private static double smeltsFrom(ItemStack fuelInSlot) {
		return fuelInSlot.isEmpty() ? 0.0 : fuelInSlot.getCount() * smeltsPerItem(fuelInSlot);
	}

	/** Picks stacks up and right-clicks single items into {@code target} so the exact amount lands there. */
	private static int deposit(Minecraft mc, LocalPlayer player, AbstractFurnaceMenu menu, Predicate<ItemStack> matches, int target, int amount) {
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

	private String finish(Minecraft mc, LocalPlayer player, @Nullable String problem) {
		PlayerControl.ensureNoContainerOpen(mc);
		String change = Inventories.diff(before, Inventories.snapshot(player));
		String head = problem == null ? "Smelting done" : problem;
		return head + ": collected " + collected + " items from the furnace at " + furnacePos.toShortString() + ". Inventory change: " + change + ".";
	}

	@Override
	public void stop(Minecraft mc) {
		sub.clear(mc);
		PlayerControl.ensureNoContainerOpen(mc);
	}

	@Override
	public int timeoutTicks() {
		return 20 * 90 + count * (cookTicksPerItem + 20);
	}

	@Override
	public String describe() {
		String child = sub.describe();
		return child != null ? child : "smelting " + input.label() + " (" + collected + "/" + count + ")";
	}
}
