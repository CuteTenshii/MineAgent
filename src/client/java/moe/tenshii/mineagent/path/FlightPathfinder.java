package moe.tenshii.mineagent.path;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * A* through open air for a flying player: every cell the feet and head pass through must be clear.
 * Costs are in blocks, so the plain distance is an admissible heuristic.
 */
public final class FlightPathfinder {
	private static final double DIAGONAL = Math.sqrt(2.0);
	// Flying through water is several times slower than through air.
	private static final double WATER_MULTIPLIER = 4.0;
	// Mild greed keeps searches across open sky small; paths stay close to the shortest.
	private static final double HEURISTIC_WEIGHT = 1.2;
	private static final int[][] STRAIGHT = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};

	private final PathWorld world;
	private final int maxNodes;

	public FlightPathfinder(PathWorld world, int maxNodes) {
		this.world = world;
		this.maxNodes = maxNodes;
	}

	private static final class Node {
		final BlockPos pos;
		double g = Double.POSITIVE_INFINITY;
		final double h;
		@Nullable Node parent;
		boolean closed;

		Node(BlockPos pos, double h) {
			this.pos = pos;
			this.h = h;
		}

		double f() {
			return g + HEURISTIC_WEIGHT * h;
		}
	}

	/** Feet and head both fit in the cell. */
	public boolean fits(BlockPos feet) {
		return world.passable(feet) && world.passable(feet.above());
	}

	/**
	 * @return a path whose steps are the cells to fly through, partial when the search ran out of nodes or loaded
	 *     chunks, or null when no cell closer to the goal can be reached
	 */
	public @Nullable Path find(BlockPos start, Goal goal) {
		Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
		Node first = new Node(start, goal.distance(start));
		first.g = 0.0;
		nodes.put(start.asLong(), first);
		open.add(first);
		Node closest = first;
		int expanded = 0;

		while (!open.isEmpty() && expanded < maxNodes) {
			Node node = open.poll();
			if (node.closed) {
				continue;
			}
			node.closed = true;
			expanded++;
			if (goal.reached(node.pos)) {
				return new Path(build(node), true);
			}
			if (node.h < closest.h) {
				closest = node;
			}
			for (BlockPos next : neighbors(node.pos)) {
				double step = next.getX() != node.pos.getX() && next.getZ() != node.pos.getZ() ? DIAGONAL : 1.0;
				if (world.isWater(next) || world.isWater(next.above())) {
					step *= WATER_MULTIPLIER;
				}
				long key = next.asLong();
				Node neighbor = nodes.get(key);
				if (neighbor == null) {
					neighbor = new Node(next, goal.distance(next));
					nodes.put(key, neighbor);
				}
				double g = node.g + step;
				if (!neighbor.closed && g < neighbor.g) {
					neighbor.g = g;
					neighbor.parent = node;
					open.add(neighbor);
				}
			}
		}
		if (closest == first) {
			return null;
		}
		return new Path(build(closest), false);
	}

	private List<BlockPos> neighbors(BlockPos from) {
		List<BlockPos> result = new ArrayList<>(10);
		for (int[] offset : STRAIGHT) {
			BlockPos next = from.offset(offset[0], offset[1], offset[2]);
			if (fits(next)) {
				result.add(next);
			}
		}
		for (int dx = -1; dx <= 1; dx += 2) {
			for (int dz = -1; dz <= 1; dz += 2) {
				BlockPos next = from.offset(dx, 0, dz);
				// Both corner cells must be open too, or the player's hitbox clips the edge between them.
				if (fits(next) && fits(from.offset(dx, 0, 0)) && fits(from.offset(0, 0, dz))) {
					result.add(next);
				}
			}
		}
		return result;
	}

	private static List<PathStep> build(Node end) {
		List<PathStep> steps = new ArrayList<>();
		for (Node node = end; node.parent != null; node = node.parent) {
			steps.add(new PathStep(node.parent.pos, node.pos, MoveType.FLY, List.of()));
		}
		Collections.reverse(steps);
		return steps;
	}
}
