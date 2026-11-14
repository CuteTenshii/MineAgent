package moe.tenshii.mineagent.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Where a path should end. Positions are the block the player's feet occupy.
 */
public interface Goal {
	boolean reached(BlockPos feet);

	/** Estimated remaining distance in blocks; must not hugely overestimate. */
	double distance(BlockPos feet);

	/** Feet within {@code range} blocks of {@code target}. */
	static Goal near(BlockPos target, double range) {
		return new Goal() {
			@Override
			public boolean reached(BlockPos feet) {
				return Math.sqrt(feet.distSqr(target)) <= range + 1.0E-3;
			}

			@Override
			public double distance(BlockPos feet) {
				return Math.max(0.0, Math.sqrt(feet.distSqr(target)) - range);
			}
		};
	}

	/** Eyes within {@code reach} of the block's center, without standing inside it. */
	static Goal withinReach(BlockPos block, double reach) {
		Vec3 center = Vec3.atCenterOf(block);
		return new Goal() {
			@Override
			public boolean reached(BlockPos feet) {
				if (feet.equals(block) || feet.above().equals(block)) {
					return false;
				}
				return eyes(feet).distanceTo(center) <= reach;
			}

			@Override
			public double distance(BlockPos feet) {
				return Math.max(0.0, eyes(feet).distanceTo(center) - reach);
			}
		};
	}

	private static Vec3 eyes(BlockPos feet) {
		return new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
	}
}
