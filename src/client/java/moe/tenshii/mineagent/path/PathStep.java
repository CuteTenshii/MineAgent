package moe.tenshii.mineagent.path;

import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * One move of a path: optionally clear {@code toBreak}, then get the feet to {@code dest}.
 */
public record PathStep(BlockPos from, BlockPos dest, MoveType type, List<BlockPos> toBreak) {
}
