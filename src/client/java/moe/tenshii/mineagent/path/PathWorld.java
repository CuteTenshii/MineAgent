package moe.tenshii.mineagent.path;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Cached, read-only view of the world for one path search. Client thread only.
 */
public final class PathWorld {
	private static final float MAX_BREAK_HARDNESS = 30.0F;
	private static final double MIN_FLOOR_HEIGHT = 0.8;

	private final ClientLevel level;
	private final LocalPlayer player;
	private final boolean allowBreaking;
	private final boolean canPillar;
	private final Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap<>();
	private final Map<BlockState, Double> breakTicks = new HashMap<>();

	public PathWorld(ClientLevel level, LocalPlayer player, boolean allowBreaking, boolean canPillar) {
		this.level = level;
		this.player = player;
		this.allowBreaking = allowBreaking;
		this.canPillar = canPillar;
	}

	public boolean canPillar() {
		return canPillar;
	}

	public @Nullable BlockState state(BlockPos pos) {
		long key = pos.asLong();
		BlockState cached = states.get(key);
		if (cached != null) {
			return cached;
		}
		if (pos.getY() < level.getMinY() || pos.getY() > level.getMaxY() || !level.hasChunkAt(pos)) {
			return null;
		}
		BlockState state = level.getBlockState(pos);
		states.put(key, state);
		return state;
	}

	public boolean passable(BlockPos pos) {
		BlockState state = state(pos);
		return state != null
			&& state.getCollisionShape(level, pos).isEmpty()
			&& !isHazard(state)
			&& !state.getFluidState().is(FluidTags.LAVA);
	}

	public boolean isWater(BlockPos pos) {
		BlockState state = state(pos);
		return state != null && state.getFluidState().is(FluidTags.WATER);
	}

	public boolean solidFloor(BlockPos pos) {
		BlockState state = state(pos);
		if (state == null || isHazard(state)) {
			return false;
		}
		VoxelShape shape = state.getCollisionShape(level, pos);
		if (shape.isEmpty()) {
			return false;
		}
		double top = shape.max(Direction.Axis.Y);
		return top >= MIN_FLOOR_HEIGHT && top <= 1.0;
	}

	/** Feet can rest here: something solid underneath, or swimming. */
	public boolean supported(BlockPos feet) {
		return solidFloor(feet.below()) || isWater(feet);
	}

	public boolean breakable(BlockPos pos) {
		if (!allowBreaking) {
			return false;
		}
		BlockState state = state(pos);
		if (state == null || state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity() || isHazard(state)) {
			return false;
		}
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0.0F || hardness > MAX_BREAK_HARDNESS) {
			return false;
		}
		// Opening a block next to a fluid would flood the path.
		for (Direction direction : Direction.values()) {
			BlockState neighbor = state(pos.relative(direction));
			if (neighbor == null || !neighbor.getFluidState().isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/** Either already clear or clearable; adds the break time to {@code cost}. */
	public double enterCost(BlockPos pos) {
		if (passable(pos)) {
			return 0.0;
		}
		if (breakable(pos)) {
			return estimateBreakTicks(pos);
		}
		return Double.POSITIVE_INFINITY;
	}

	private double estimateBreakTicks(BlockPos pos) {
		BlockState state = state(pos);
		if (state == null) {
			return Double.POSITIVE_INFINITY;
		}
		return breakTicks.computeIfAbsent(state, s -> {
			float hardness = s.getDestroySpeed(level, pos);
			if (hardness == 0.0F) {
				return 2.0;
			}
			Inventory inventory = player.getInventory();
			float bestSpeed = 1.0F;
			boolean harvestable = !s.requiresCorrectToolForDrops();
			for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
				ItemStack stack = inventory.getItem(slot);
				bestSpeed = Math.max(bestSpeed, stack.getDestroySpeed(s));
				harvestable |= stack.isCorrectToolForDrops(s);
			}
			// Matches vanilla's per-tick progress: speed / hardness / (30 or 100).
			double ticks = hardness * (harvestable ? 30.0 : 100.0) / bestSpeed;
			return Math.ceil(ticks) + 6.0;
		});
	}

	private static boolean isHazard(BlockState state) {
		return state.is(BlockTags.FIRE)
			|| state.is(BlockTags.CAMPFIRES)
			|| state.is(Blocks.LAVA)
			|| state.is(Blocks.MAGMA_BLOCK)
			|| state.is(Blocks.CACTUS)
			|| state.is(Blocks.SWEET_BERRY_BUSH)
			|| state.is(Blocks.POWDER_SNOW)
			|| state.is(Blocks.WITHER_ROSE)
			|| state.is(Blocks.COBWEB)
			|| state.is(Blocks.POINTED_DRIPSTONE);
	}
}
