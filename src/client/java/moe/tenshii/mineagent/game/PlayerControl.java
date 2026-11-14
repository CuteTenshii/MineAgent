package moe.tenshii.mineagent.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Low-level player manipulation shared by tasks. Client thread only.
 */
public final class PlayerControl {
	private static final float MAX_TURN_PER_TICK = 35.0F;
	private static final int HOTBAR_SIZE = Inventory.getSelectionSize();
	private static final int INVENTORY_MENU_HOTBAR_START = 36;

	private static final AgentInput INPUT = new AgentInput();
	private static boolean inControl;
	private static boolean miningOverride;
	private static @Nullable BlockPos miningPos;

	private PlayerControl() {
	}

	public static AgentInput input() {
		return INPUT;
	}

	/** Swaps the keyboard for the agent's input source. Re-applied every tick because respawning recreates the player. */
	public static void takeControl(Minecraft mc) {
		inControl = true;
		LocalPlayer player = mc.player;
		if (player != null && player.input != INPUT) {
			INPUT.clear();
			player.input = INPUT;
		}
	}

	public static void releaseControl(Minecraft mc) {
		idle(mc);
		inControl = false;
		LocalPlayer player = mc.player;
		if (player != null && player.input == INPUT) {
			player.input = new KeyboardInput(mc.options);
		}
	}

	public static boolean inControl() {
		return inControl;
	}

	/** Stops all movement and ongoing interactions without giving control back. */
	public static void idle(Minecraft mc) {
		INPUT.clear();
		stopMining(mc);
		mc.options.keyUse.setDown(false);
	}

	public static boolean isMiningOverride() {
		return miningOverride;
	}

	public static void lookAt(LocalPlayer player, Vec3 target) {
		Vec3 eye = player.getEyePosition();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
		float pitch = (float) -(Mth.atan2(dy, horizontal) * Mth.RAD_TO_DEG);
		turnTowards(player, yaw, pitch);
	}

	public static void turnTowards(LocalPlayer player, float yaw, float pitch) {
		float currentYaw = player.getYRot();
		float yawDelta = Mth.clamp(Mth.wrapDegrees(yaw - currentYaw), -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
		float pitchDelta = Mth.clamp(pitch - player.getXRot(), -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
		player.setYRot(currentYaw + yawDelta);
		player.setXRot(Mth.clamp(player.getXRot() + pitchDelta, -90.0F, 90.0F));
	}

	public static boolean isFacing(LocalPlayer player, Vec3 target, float toleranceDegrees) {
		Vec3 eye = player.getEyePosition();
		Vec3 wanted = target.subtract(eye).normalize();
		Vec3 look = player.getLookAngle();
		double dot = Mth.clamp(wanted.dot(look), -1.0, 1.0);
		return Math.toDegrees(Math.acos(dot)) <= toleranceDegrees;
	}

	public static Vec3 center(BlockPos pos) {
		return Vec3.atCenterOf(pos);
	}

	public static double eyeDistance(LocalPlayer player, BlockPos pos) {
		return player.getEyePosition().distanceTo(center(pos));
	}

	public static double reach(LocalPlayer player) {
		return player.blockInteractionRange();
	}

	/** The face of {@code pos} that points most directly at the player's eyes. */
	public static Direction faceTowardsPlayer(LocalPlayer player, BlockPos pos) {
		Vec3 delta = player.getEyePosition().subtract(center(pos));
		return Direction.getApproximateNearest(delta.x, delta.y, delta.z);
	}

	/**
	 * Advances breaking the block at {@code pos} by one tick, choosing the best tool first.
	 *
	 * @return true once the block is gone
	 */
	public static boolean mineTick(Minecraft mc, BlockPos pos) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null) {
			return false;
		}
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(mc.level, pos).isEmpty()) {
			stopMining(mc);
			return true;
		}
		if (!pos.equals(miningPos)) {
			stopMining(mc);
			selectBestTool(mc, state);
			miningPos = pos.immutable();
		}
		Vec3 target = center(pos);
		lookAt(player, target);
		miningOverride = true;
		Direction face = faceTowardsPlayer(player, pos);
		if (mc.gameMode.continueDestroyBlock(pos, face)) {
			swingAttack(player);
		}
		return mc.level.getBlockState(pos).isAir();
	}

	public static void stopMining(Minecraft mc) {
		if (miningOverride || miningPos != null) {
			miningOverride = false;
			miningPos = null;
			if (mc.gameMode != null) {
				mc.gameMode.stopDestroyBlock();
			}
		}
	}

	/** Puts the inventory item that breaks {@code state} fastest into the main hand. */
	public static void selectBestTool(Minecraft mc, BlockState state) {
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}
		Inventory inventory = player.getInventory();
		int bestSlot = -1;
		float bestScore = scoreTool(inventory.getSelectedItem(), state);
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			float score = scoreTool(inventory.getItem(slot), state);
			if (score > bestScore + 0.01F) {
				bestScore = score;
				bestSlot = slot;
			}
		}
		if (bestSlot >= 0) {
			moveToHand(mc, bestSlot);
		}
	}

	private static float scoreTool(ItemStack stack, BlockState state) {
		float speed = stack.getDestroySpeed(state);
		if (state.requiresCorrectToolForDrops() && stack.isCorrectToolForDrops(state)) {
			speed += 100.0F;
		}
		return speed;
	}

	public static boolean hasCorrectTool(LocalPlayer player, BlockState state) {
		if (!state.requiresCorrectToolForDrops()) {
			return true;
		}
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (inventory.getItem(slot).isCorrectToolForDrops(state)) {
				return true;
			}
		}
		return false;
	}

	public static int findSlot(LocalPlayer player, Predicate<ItemStack> matches) {
		Inventory inventory = player.getInventory();
		if (matches.test(inventory.getSelectedItem())) {
			return inventory.getSelectedSlot();
		}
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && matches.test(stack)) {
				return slot;
			}
		}
		return -1;
	}

	public static int findBestSlot(LocalPlayer player, Predicate<ItemStack> matches, Comparator<ItemStack> better) {
		Inventory inventory = player.getInventory();
		int best = -1;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && matches.test(stack) && (best < 0 || better.compare(stack, inventory.getItem(best)) > 0)) {
				best = slot;
			}
		}
		return best;
	}

	/**
	 * Puts the first inventory item matching {@code matches} into the main hand.
	 *
	 * @return false if no such item is carried
	 */
	public static boolean holdItem(Minecraft mc, Predicate<ItemStack> matches) {
		LocalPlayer player = mc.player;
		if (player == null) {
			return false;
		}
		int slot = findSlot(player, matches);
		if (slot < 0) {
			return false;
		}
		moveToHand(mc, slot);
		return matches.test(player.getInventory().getSelectedItem());
	}

	/** Selects a hotbar slot, or swaps a main-inventory slot into the selected hotbar slot. */
	public static void moveToHand(Minecraft mc, int inventorySlot) {
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null) {
			return;
		}
		Inventory inventory = player.getInventory();
		if (inventorySlot < HOTBAR_SIZE) {
			inventory.setSelectedSlot(inventorySlot);
			return;
		}
		ensureNoContainerOpen(mc);
		int hotbarSlot = firstEmptyHotbarSlot(inventory);
		if (hotbarSlot < 0) {
			hotbarSlot = inventory.getSelectedSlot();
		}
		mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, inventorySlot, hotbarSlot, ContainerInput.SWAP, player);
		inventory.setSelectedSlot(hotbarSlot);
	}

	private static int firstEmptyHotbarSlot(Inventory inventory) {
		for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
			if (inventory.getItem(slot).isEmpty()) {
				return slot;
			}
		}
		return -1;
	}

	/** Index of an inventory slot inside the player's own {@code InventoryMenu}. */
	public static int inventoryMenuSlot(int inventorySlot) {
		return inventorySlot < HOTBAR_SIZE ? INVENTORY_MENU_HOTBAR_START + inventorySlot : inventorySlot;
	}

	public static void ensureNoContainerOpen(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player != null && player.containerMenu != player.inventoryMenu) {
			player.closeContainer();
		}
	}

	public static void click(Minecraft mc, AbstractContainerMenu menu, int slot, int button, ContainerInput input) {
		if (mc.player != null && mc.gameMode != null) {
			mc.gameMode.handleContainerInput(menu.containerId, slot, button, input, mc.player);
		}
	}

	/**
	 * Right-clicks a face of a block, e.g. to place the held item against it or to open it.
	 */
	public static InteractionResult useOn(Minecraft mc, BlockPos pos, Direction face) {
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null) {
			return InteractionResult.FAIL;
		}
		Vec3 hit = center(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		lookAt(player, hit);
		InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
		if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
			swingUse(player);
		}
		return result;
	}

	/**
	 * A point on {@code pos} the player can see, so a ray from the eyes reaches it before anything else.
	 * Fluids count as targets, which is what bucket and bottle use needs.
	 *
	 * @return null when every candidate point is hidden behind another block
	 */
	public static @Nullable Vec3 visiblePoint(Minecraft mc, BlockPos pos) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return null;
		}
		Vec3 eye = player.getEyePosition();
		Vec3 center = center(pos);
		List<Vec3> candidates = new ArrayList<>();
		candidates.add(center);
		for (Direction direction : Direction.values()) {
			candidates.add(center.add(direction.getStepX() * 0.45, direction.getStepY() * 0.45, direction.getStepZ() * 0.45));
		}
		candidates.sort(Comparator.comparingDouble(eye::distanceToSqr));
		for (Vec3 point : candidates) {
			Vec3 beyond = eye.add(point.subtract(eye).scale(1.02));
			BlockHitResult hit = mc.level.clip(new ClipContext(eye, beyond, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
			if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
				return point;
			}
		}
		return null;
	}

	public static void lookAtInstantly(LocalPlayer player, Vec3 target) {
		Vec3 eye = player.getEyePosition();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
		float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
		player.setYRot(yaw);
		player.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
	}

	/**
	 * Right-clicks while looking at {@code aim}, the same way vanilla handles the use key: use the item on the block
	 * under the crosshair, and if that does nothing, use the held item itself. The fallback is what fills buckets,
	 * since the server traces the look direction to find the fluid.
	 */
	public static InteractionResult rightClick(Minecraft mc, Vec3 aim) {
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null) {
			return InteractionResult.FAIL;
		}
		lookAtInstantly(player, aim);
		HitResult crosshair = player.pick(reach(player), 1.0F, false);
		if (crosshair instanceof BlockHitResult blockHit && crosshair.getType() == HitResult.Type.BLOCK) {
			InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, blockHit);
			if (result instanceof InteractionResult.Success success) {
				if (success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
					swingUse(player);
				}
				return result;
			}
			if (result instanceof InteractionResult.Fail) {
				return result;
			}
		}
		if (player.getMainHandItem().isEmpty()) {
			return InteractionResult.PASS;
		}
		InteractionResult result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
		if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
			swingUse(player);
		}
		return result;
	}

	public static void swingAttack(LocalPlayer player) {
		player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getAttackAnimation(), false);
	}

	public static void swingUse(LocalPlayer player) {
		player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getInteractAnimation(), false);
	}

	public static int countItems(LocalPlayer player, Predicate<ItemStack> matches) {
		Inventory inventory = player.getInventory();
		int total = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && matches.test(stack)) {
				total += stack.getCount();
			}
		}
		ItemStack offhand = player.getOffhandItem();
		if (!offhand.isEmpty() && matches.test(offhand)) {
			total += offhand.getCount();
		}
		return total;
	}
}
