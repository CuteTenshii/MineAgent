package moe.tenshii.mineagent.path;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
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
	// Enough open air to stand for any room or cave; a bigger pocket is left for the full search to judge.
	private static final int POCKET_LIMIT = 4096;
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

	/**
	 * Why no flight can reach {@code target}, found without a full search: the target cell is solid, or it sits in a
	 * pocket of open air that does not connect to {@code start}. A full search would expand every node it may before
	 * giving up.
	 *
	 * @return the reason, or null when the target may be reachable
	 */
	public @Nullable String unreachable(BlockPos target, int range, BlockPos start) {
		// With a range, a goal cell outside the walls may still count, so only an exact target is checked.
		if (range != 0 || world.state(target) == null || world.state(target.above()) == null) {
			return null;
		}
		if (!fits(target)) {
			return "the feet and head blocks there are not both open, so the player cannot be there.";
		}
		LongOpenHashSet seen = new LongOpenHashSet();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		seen.add(target.asLong());
		queue.add(target);
		while (!queue.isEmpty()) {
			if (seen.size() > POCKET_LIMIT) {
				return null;
			}
			BlockPos cell = queue.poll();
			if (cell.equals(start)) {
				return null;
			}
			for (int[] offset : STRAIGHT) {
				BlockPos next = cell.offset(offset[0], offset[1], offset[2]);
				if (fits(next) && seen.add(next.asLong())) {
					queue.add(next);
				}
			}
		}
		return "it is enclosed: the " + seen.size() + " open blocks around it have no way out to the player.";
	}

	/** Feet and head both fit in the cell. */
	public boolean fits(BlockPos feet) {
		return world.passable(feet) && world.passable(feet.above());
	}

	/**
	 * Starts a search from {@code start}; call {@link PathSearch#advance()} each tick until it ends. The result's
	 * steps are the cells to fly through.
	 */
	public PathSearch start(BlockPos start, Goal goal) {
		return new Search(start, goal);
	}

	private final class Search extends PathSearch {
		private final Goal goal;
		private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
		private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
		private final Node first;
		private Node closest;
		private @Nullable Node reached;

		Search(BlockPos start, Goal goal) {
			super("flight search", maxNodes);
			this.goal = goal;
			first = new Node(start, goal.distance(start));
			first.g = 0.0;
			nodes.put(start.asLong(), first);
			open.add(first);
			closest = first;
		}

		@Override
		protected boolean expandNext() {
			Node node;
			do {
				node = open.poll();
				if (node == null) {
					return false;
				}
			} while (node.closed);
			node.closed = true;
			if (goal.reached(node.pos)) {
				reached = node;
				return false;
			}
			if (node.h < closest.h) {
				closest = node;
				// The way closer runs into chunks not loaded yet: end this leg here instead of searching the whole loaded area.
				if (world.atLoadedEdge(node.pos)) {
					return false;
				}
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
			return true;
		}

		@Override
		protected @Nullable Path finish() {
			if (reached != null) {
				return new Path(build(reached), true);
			}
			return closest == first ? null : new Path(build(closest), false);
		}
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
