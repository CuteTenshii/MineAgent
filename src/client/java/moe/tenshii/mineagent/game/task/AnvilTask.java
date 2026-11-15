package moe.tenshii.mineagent.game.task;

import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.MenuOps;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.WorldInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Combines, repairs or renames an item at an anvil, paying the experience cost.
 */
public final class AnvilTask extends StationTask<AnvilMenu> {
	// Vanilla refuses anvil jobs costing this many levels or more outside creative ("Too Expensive!").
	private static final int TOO_EXPENSIVE = 40;
	// Four units of a repair material restore an item fully, so more would be wasted.
	private static final int MAX_REPAIR_UNITS = 4;

	private final Lookup.ItemQuery item;
	private final Lookup.@Nullable ItemQuery addition;
	private final @Nullable String name;
	private boolean loaded;
	private @Nullable String resultName;
	private int cost;

	public AnvilTask(Lookup.ItemQuery item, Lookup.@Nullable ItemQuery addition, @Nullable String name, @Nullable BlockPos pos) throws TaskFailure {
		super("anvil", state -> state.is(BlockTags.ANVIL), AnvilMenu.class, pos);
		if (addition == null && (name == null || name.isBlank())) {
			throw new TaskFailure("Give a second item to combine or repair with, a new name, or both.");
		}
		if (name != null && name.length() > 50) {
			throw new TaskFailure("Anvil names can be at most 50 characters.");
		}
		this.item = item;
		this.addition = addition;
		this.name = name;
	}

	@Override
	protected void checkInventory(Minecraft mc, LocalPlayer player) throws TaskFailure {
		require(player, item.matches(), item.label(), 1);
		if (addition != null) {
			// Combining two of the same item, such as two pickaxes, needs both.
			boolean same = PlayerControl.findSlot(player, stack -> item.matches().test(stack) && addition.matches().test(stack)) >= 0;
			require(player, addition.matches(), addition.label(), same ? 2 : 1);
		}
		if (player.experienceLevel == 0 && !player.hasInfiniteMaterials()) {
			throw new TaskFailure("Anvils cost experience levels and the player has none.");
		}
	}

	@Override
	protected @Nullable String work(Minecraft mc, LocalPlayer player, AnvilMenu menu) throws TaskFailure {
		if (!loaded) {
			loaded = true;
			if (menu.getSlot(AnvilMenu.INPUT_SLOT).hasItem() || menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).hasItem()) {
				throw new TaskFailure("The anvil's slots were not empty.");
			}
			if (MenuOps.deposit(mc, player, menu, item.matches(), AnvilMenu.INPUT_SLOT, 1) < 1) {
				throw new TaskFailure("Could not put " + item.label() + " into the anvil.");
			}
			if (addition != null) {
				ItemStack first = menu.getSlot(AnvilMenu.INPUT_SLOT).getItem();
				int units = first.isDamageableItem() && !addition.matches().test(first) ? MAX_REPAIR_UNITS : 1;
				MenuOps.deposit(mc, player, menu, addition.matches(), AnvilMenu.ADDITIONAL_SLOT, units);
				if (!menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).hasItem()) {
					throw new TaskFailure("Could not put " + addition.label() + " into the anvil.");
				}
			}
			if (name != null && !name.isBlank() && menu.setItemName(name)) {
				player.connection.send(new ServerboundRenameItemPacket(name));
			}
			resetWait();
			return null;
		}
		ItemStack result = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem();
		if (resultName == null) {
			if (result.isEmpty() || menu.getCost() <= 0) {
				if (waitedTooLong()) {
					throw new TaskFailure("The anvil cannot do this: " + item.label()
						+ (addition != null ? " does not combine with " + addition.label() : " cannot be renamed") + ".");
				}
				return null;
			}
			cost = menu.getCost();
			if (!player.hasInfiniteMaterials()) {
				if (cost >= TOO_EXPENSIVE) {
					throw new TaskFailure("Too expensive: the anvil asks " + cost + " levels, and anything from " + TOO_EXPENSIVE + " up is refused.");
				}
				if (cost > player.experienceLevel) {
					throw new TaskFailure("The anvil asks " + cost + " levels but the player has " + player.experienceLevel + ".");
				}
			}
			resultName = WorldInfo.stackName(result);
			MenuOps.takeAll(mc, menu, AnvilMenu.RESULT_SLOT);
			resetWait();
			return null;
		}
		if (result.isEmpty() || waitedTooLong()) {
			return "Made " + resultName + " at the anvil for " + cost + " levels.";
		}
		return null;
	}

	@Override
	protected String describeWork() {
		return "using an anvil on " + item.label();
	}
}
