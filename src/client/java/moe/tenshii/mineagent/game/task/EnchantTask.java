package moe.tenshii.mineagent.game.task;

import java.util.Optional;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.MenuOps;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.WorldInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Lists an enchanting table's three offers for an item, or takes one of them.
 */
public final class EnchantTask extends StationTask<EnchantmentMenu> {
	private static final int ITEM_SLOT = 0;
	private static final int LAPIS_SLOT = 1;
	private static final int OFFERS = 3;

	private final Lookup.ItemQuery item;
	private final int option;
	private boolean loaded;
	private boolean chosen;

	/**
	 * @param option 1-3 to enchant with that offer, or 0 to only list the offers
	 */
	public EnchantTask(Lookup.ItemQuery item, int option, @Nullable BlockPos pos) {
		super("enchanting table", state -> state.is(Blocks.ENCHANTING_TABLE), EnchantmentMenu.class, pos);
		this.item = item;
		this.option = option;
	}

	@Override
	protected void checkInventory(Minecraft mc, LocalPlayer player) throws TaskFailure {
		require(player, item.matches(), item.label(), 1);
		if (option > 0 && !player.hasInfiniteMaterials()) {
			require(player, stack -> stack.is(Items.LAPIS_LAZULI), "lapis_lazuli", option);
			if (player.experienceLevel < option) {
				throw new TaskFailure("Offer " + option + " needs at least " + option + " experience levels; the player has "
					+ player.experienceLevel + ".");
			}
		}
	}

	@Override
	protected @Nullable String work(Minecraft mc, LocalPlayer player, EnchantmentMenu menu) throws TaskFailure {
		if (!loaded) {
			loaded = true;
			if (menu.getSlot(ITEM_SLOT).hasItem()) {
				throw new TaskFailure("The enchanting table's item slot was not empty.");
			}
			if (MenuOps.deposit(mc, player, menu, item.matches(), ITEM_SLOT, 1) < 1) {
				throw new TaskFailure("Could not put " + item.label() + " into the enchanting table.");
			}
			int lapis = Math.min(OFFERS, PlayerControl.countItems(player, stack -> stack.is(Items.LAPIS_LAZULI)));
			MenuOps.deposit(mc, player, menu, stack -> stack.is(Items.LAPIS_LAZULI), LAPIS_SLOT, lapis);
			resetWait();
			return null;
		}
		if (!chosen) {
			boolean anyOffer = menu.costs[0] > 0 || menu.costs[1] > 0 || menu.costs[2] > 0;
			if (!anyOffer) {
				if (waitedTooLong()) {
					throw new TaskFailure(item.label() + " cannot be enchanted here: it may already be enchanted or not be enchantable.");
				}
				return null;
			}
			if (option == 0) {
				return offers(mc, player, menu);
			}
			int cost = menu.costs[option - 1];
			if (cost <= 0) {
				throw new TaskFailure("Offer " + option + " is not available. " + offers(mc, player, menu));
			}
			if (!player.hasInfiniteMaterials() && player.experienceLevel < cost) {
				throw new TaskFailure("Offer " + option + " needs level " + cost + "; the player is level " + player.experienceLevel + ".");
			}
			chosen = true;
			mc.gameMode.handleInventoryButtonClick(menu.containerId, option - 1);
			resetWait();
			return null;
		}
		ItemStack enchanted = menu.getSlot(ITEM_SLOT).getItem();
		if (enchanted.isEnchanted() || enchanted.is(Items.ENCHANTED_BOOK)) {
			String name = WorldInfo.stackName(enchanted);
			MenuOps.takeAll(mc, menu, ITEM_SLOT);
			return "Enchanted with offer " + option + ": " + name + ".";
		}
		if (waitedTooLong()) {
			throw new TaskFailure("The enchanting table did not enchant the item.");
		}
		return null;
	}

	private static String offers(Minecraft mc, LocalPlayer player, EnchantmentMenu menu) {
		StringBuilder text = new StringBuilder("Offers (player level ").append(player.experienceLevel).append(", lapis in the table ")
			.append(menu.getGoldCount()).append("):");
		for (int index = 0; index < OFFERS; index++) {
			text.append("\n").append(index + 1).append(": ");
			if (menu.costs[index] <= 0) {
				text.append("none (more bookshelves around the table unlock higher offers)");
				continue;
			}
			text.append("needs level ").append(menu.costs[index]).append(", costs ").append(index + 1).append(" lapis and ")
				.append(index + 1).append(index == 0 ? " level" : " levels");
			Optional<Holder.Reference<Enchantment>> hint = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(menu.enchantClue[index]);
			int hintLevel = menu.levelClue[index];
			hint.ifPresent(enchantment -> text.append("; includes ")
				.append(Enchantment.getFullname(enchantment, hintLevel).getString()).append(" (maybe more)"));
		}
		return text.toString();
	}

	@Override
	protected String describeWork() {
		return option == 0 ? "checking enchantments for " + item.label() : "enchanting " + item.label();
	}
}
