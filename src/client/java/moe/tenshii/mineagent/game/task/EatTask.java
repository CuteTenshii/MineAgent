package moe.tenshii.mineagent.game.task;

import java.util.Comparator;
import java.util.Set;
import java.util.function.Predicate;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Holds right-click with a food item until one is eaten. Looks at the sky so the click cannot hit a block.
 */
public final class EatTask implements Task {
	/** Foods with a harmful effect, skipped unless asked for by name. */
	public static final Set<Item> HARMFUL = Set.of(
		Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH, Items.SUSPICIOUS_STEW, Items.CHORUS_FRUIT);

	private final Lookup.@Nullable ItemQuery requested;
	private @Nullable Item eating;
	private int countBefore;
	private int ticks;

	public EatTask(Lookup.@Nullable ItemQuery requested) {
		this.requested = requested;
	}

	@Override
	public @Nullable String tick(Minecraft mc) throws TaskFailure {
		LocalPlayer player = mc.player;
		PlayerControl.takeControl(mc);
		if (eating == null) {
			start(mc, player);
			return null;
		}
		ticks++;
		int countNow = PlayerControl.countItems(player, stack -> stack.is(eating));
		if (countNow < countBefore && !player.isUsingItem()) {
			mc.options.keyUse.setDown(false);
			return "Ate " + Lookup.name(eating) + ". Food is now " + player.getFoodData().getFoodLevel() + "/20, health "
				+ Math.round(player.getHealth()) + "/" + Math.round(player.getMaxHealth()) + ".";
		}
		if (ticks > 80) {
			throw new TaskFailure("Eating " + Lookup.name(eating) + " did not finish.");
		}
		PlayerControl.turnTowards(player, player.getYRot(), -90.0F);
		mc.options.keyUse.setDown(player.getXRot() < -80.0F);
		return null;
	}

	private void start(Minecraft mc, LocalPlayer player) throws TaskFailure {
		Predicate<ItemStack> isFood = stack -> stack.has(DataComponents.FOOD);
		Predicate<ItemStack> candidates = requested != null
			? stack -> isFood.test(stack) && requested.matches().test(stack)
			: stack -> isFood.test(stack) && !HARMFUL.contains(stack.getItem());
		int slot = PlayerControl.findBestSlot(player, candidates, Comparator.comparingInt(EatTask::nutrition));
		if (slot < 0) {
			throw new TaskFailure(requested != null ? "No edible " + requested.label() + " in the inventory." : "No safe food in the inventory.");
		}
		ItemStack stack = player.getInventory().getItem(slot);
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food != null && !food.canAlwaysEat() && !player.getFoodData().needsFood()) {
			throw new TaskFailure("Not hungry: food is " + player.getFoodData().getFoodLevel() + "/20.");
		}
		PlayerControl.ensureNoContainerOpen(mc);
		PlayerControl.moveToHand(mc, slot);
		eating = stack.getItem();
		countBefore = PlayerControl.countItems(player, s -> s.is(eating));
	}

	private static int nutrition(ItemStack stack) {
		FoodProperties food = stack.get(DataComponents.FOOD);
		return food == null ? 0 : food.nutrition();
	}

	@Override
	public void stop(Minecraft mc) {
		mc.options.keyUse.setDown(false);
	}

	@Override
	public int timeoutTicks() {
		return 20 * 8;
	}

	@Override
	public String describe() {
		return eating == null ? "eating" : "eating " + Lookup.name(eating);
	}
}
