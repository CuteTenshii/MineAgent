package moe.tenshii.mineagent.game.task;

import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.MenuOps;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.WorldInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Upgrades gear to netherite or applies an armor trim at a smithing table.
 */
public final class SmithTask extends StationTask<SmithingMenu> {
	private final Lookup.ItemQuery template;
	private final Lookup.ItemQuery base;
	private final Lookup.ItemQuery addition;
	private boolean loaded;
	private @Nullable String resultName;

	public SmithTask(Lookup.ItemQuery template, Lookup.ItemQuery base, Lookup.ItemQuery addition, @Nullable BlockPos pos) {
		super("smithing table", state -> state.is(Blocks.SMITHING_TABLE), SmithingMenu.class, pos);
		this.template = template;
		this.base = base;
		this.addition = addition;
	}

	@Override
	protected void checkInventory(Minecraft mc, LocalPlayer player) throws TaskFailure {
		require(player, template.matches(), template.label(), 1);
		require(player, base.matches(), base.label(), 1);
		require(player, addition.matches(), addition.label(), 1);
	}

	@Override
	protected @Nullable String work(Minecraft mc, LocalPlayer player, SmithingMenu menu) throws TaskFailure {
		if (!loaded) {
			loaded = true;
			load(mc, player, menu, template, SmithingMenu.TEMPLATE_SLOT);
			load(mc, player, menu, base, SmithingMenu.BASE_SLOT);
			load(mc, player, menu, addition, SmithingMenu.ADDITIONAL_SLOT);
			resetWait();
			return null;
		}
		ItemStack result = menu.getSlot(SmithingMenu.RESULT_SLOT).getItem();
		if (resultName == null) {
			if (!result.isEmpty()) {
				resultName = WorldInfo.stackName(result);
				MenuOps.takeAll(mc, menu, SmithingMenu.RESULT_SLOT);
				resetWait();
				return null;
			}
			if (waitedTooLong()) {
				throw new TaskFailure("The smithing table made nothing from " + template.label() + ", " + base.label() + " and " + addition.label()
					+ ". Check the template matches: netherite_upgrade_smithing_template with diamond gear and a netherite ingot, "
					+ "or a trim template with armor and a trim material.");
			}
			return null;
		}
		if (result.isEmpty() || waitedTooLong()) {
			return "Smithed " + resultName + ".";
		}
		return null;
	}

	private static void load(Minecraft mc, LocalPlayer player, SmithingMenu menu, Lookup.ItemQuery item, int slot) throws TaskFailure {
		if (menu.getSlot(slot).hasItem()) {
			throw new TaskFailure("A smithing table slot was not empty.");
		}
		if (MenuOps.deposit(mc, player, menu, item.matches(), slot, 1) < 1) {
			throw new TaskFailure("Could not put " + item.label() + " into the smithing table.");
		}
	}

	@Override
	protected String describeWork() {
		return "smithing " + base.label();
	}
}
