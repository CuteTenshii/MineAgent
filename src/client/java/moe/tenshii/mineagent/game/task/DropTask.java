package moe.tenshii.mineagent.game.task;

import moe.tenshii.mineagent.game.DroppedItems;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Throws items a few blocks ahead, like pressing Q. The look direction is set a tick before throwing because the
 * server aims the throw with the rotation it last received, and looking down would drop everything onto the player.
 */
public final class DropTask implements Task {
	private static final float THROW_PITCH = -15.0F;
	private static final double LANDING_DISTANCE = 3.0;

	private final Lookup.ItemQuery item;
	private final int wanted;
	private final boolean force;
	private int ticks;
	private int dropped;

	public DropTask(Lookup.ItemQuery item, int count, boolean force) {
		this.item = item;
		this.wanted = count;
		this.force = force;
	}

	@Override
	public @Nullable String tick(Minecraft mc) throws TaskFailure {
		LocalPlayer player = mc.player;
		PlayerControl.takeControl(mc);
		ticks++;
		if (ticks == 1) {
			if (PlayerControl.findSlot(player, item.matches()) < 0) {
				throw new TaskFailure("No " + item.label() + " in the inventory.");
			}
			if (!force && PlayerControl.findSlot(player, stack -> item.matches().test(stack) && stack.isDamageableItem()) >= 0) {
				throw new TaskFailure(item.label() + " includes tools, weapons or armor. Those are not dropped unless force=true; "
					+ "store them in a chest instead if you need space.");
			}
			PlayerControl.ensureNoContainerOpen(mc);
			PlayerControl.input().clear();
			player.setXRot(THROW_PITCH);
			return null;
		}
		if (ticks == 2) {
			throwItems(mc, player);
			return null;
		}
		if (ticks < 6) {
			return null;
		}
		return "Dropped " + dropped + " " + item.label() + " a few blocks ahead. Dropped items despawn after 5 minutes, "
			+ "and automatic pickup will leave them alone.";
	}

	private void throwItems(Minecraft mc, LocalPlayer player) {
		Vec3 landing = player.getEyePosition().add(player.getLookAngle().scale(LANDING_DISTANCE));
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE && dropped < wanted; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.isEmpty() || !item.matches().test(stack) || !force && stack.isDamageableItem()) {
				continue;
			}
			DroppedItems.record(stack.getItem(), landing);
			int menuSlot = PlayerControl.inventoryMenuSlot(slot);
			if (wanted - dropped >= stack.getCount()) {
				dropped += stack.getCount();
				PlayerControl.click(mc, player.inventoryMenu, menuSlot, 1, ContainerInput.THROW);
			} else {
				while (dropped < wanted && !player.getInventory().getItem(slot).isEmpty()) {
					PlayerControl.click(mc, player.inventoryMenu, menuSlot, 0, ContainerInput.THROW);
					dropped++;
				}
			}
		}
	}

	@Override
	public int timeoutTicks() {
		return 40;
	}

	@Override
	public String describe() {
		return "dropping " + item.label();
	}
}
