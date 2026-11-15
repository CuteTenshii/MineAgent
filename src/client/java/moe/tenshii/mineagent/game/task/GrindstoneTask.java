package moe.tenshii.mineagent.game.task;

import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.MenuOps;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.WorldInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Removes enchantments from an item, or merges two damaged items of the same kind, at a grindstone.
 */
public final class GrindstoneTask extends StationTask<GrindstoneMenu> {
	private final Lookup.ItemQuery item;
	private final Lookup.@Nullable ItemQuery second;
	private boolean loaded;
	private @Nullable String resultName;

	public GrindstoneTask(Lookup.ItemQuery item, Lookup.@Nullable ItemQuery second, @Nullable BlockPos pos) {
		super("grindstone", state -> state.is(Blocks.GRINDSTONE), GrindstoneMenu.class, pos);
		this.item = item;
		this.second = second;
	}

	@Override
	protected void checkInventory(Minecraft mc, LocalPlayer player) throws TaskFailure {
		require(player, item.matches(), item.label(), 1);
		if (second != null) {
			boolean same = PlayerControl.findSlot(player, stack -> item.matches().test(stack) && second.matches().test(stack)) >= 0;
			require(player, second.matches(), second.label(), same ? 2 : 1);
		}
	}

	@Override
	protected @Nullable String work(Minecraft mc, LocalPlayer player, GrindstoneMenu menu) throws TaskFailure {
		if (!loaded) {
			loaded = true;
			if (menu.getSlot(GrindstoneMenu.INPUT_SLOT).hasItem() || menu.getSlot(GrindstoneMenu.ADDITIONAL_SLOT).hasItem()) {
				throw new TaskFailure("The grindstone's slots were not empty.");
			}
			if (MenuOps.deposit(mc, player, menu, item.matches(), GrindstoneMenu.INPUT_SLOT, 1) < 1) {
				throw new TaskFailure("Could not put " + item.label() + " into the grindstone.");
			}
			if (second != null && MenuOps.deposit(mc, player, menu, second.matches(), GrindstoneMenu.ADDITIONAL_SLOT, 1) < 1) {
				throw new TaskFailure("Could not put " + second.label() + " into the grindstone.");
			}
			resetWait();
			return null;
		}
		ItemStack result = menu.getSlot(GrindstoneMenu.RESULT_SLOT).getItem();
		if (resultName == null) {
			if (!result.isEmpty()) {
				resultName = WorldInfo.stackName(result);
				MenuOps.takeAll(mc, menu, GrindstoneMenu.RESULT_SLOT);
				resetWait();
				return null;
			}
			if (waitedTooLong()) {
				throw new TaskFailure("The grindstone made nothing: it needs an enchanted item, or two damaged items of the same kind.");
			}
			return null;
		}
		if (result.isEmpty() || waitedTooLong()) {
			return "Ground " + resultName + " (any experience drops as orbs nearby).";
		}
		return null;
	}

	@Override
	protected String describeWork() {
		return "grinding " + item.label();
	}
}
