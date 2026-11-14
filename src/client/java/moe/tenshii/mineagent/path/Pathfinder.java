package moe.tenshii.mineagent.path;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

/**
 * A* over player feet positions. Costs are roughly in ticks so breaking and walking compare fairly.
 */
public final class Pathfinder {
	private static final double WALK = 4.6;
	private static final double DIAGONAL = WALK * Math.sqrt(2.0);
	private static final double ASCEND = 7.0;
	private static final double FALL_PER_BLOCK = 1.5;
	private static final double SWIM_MULTIPLIER = 2.5;
	private static final double PILLAR = 18.0;
	private static final double SUBMERGED_MULTIPLIER = 4.0;
	// Roughly half the player's 15 seconds of air at swimming speed, leaving margin to come back up.
	private static final int MAX_SUBMERGED_STEPS = 10;
	private static final int MAX_DROP = 3;
	private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

	private final PathWorld world;
	private final int maxNodes;

	public Pathfinder(PathWorld world, int maxNodes) {
		this.world = world;
		this.maxNodes = maxNodes;
	}

	private static final class Node {
		final BlockPos pos;
		double g;
		final double h;
		@Nullable Node parent;
		@Nullable PathStep step;
		int submerged;
		boolean closed;

		Node(BlockPos pos, double h) {
			this.pos = pos;
			this.h = h;
		}

		double f() {
			return g + h;
		}
	}

	public @Nullable Path find(BlockPos start, Goal goal) {
		Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
		Node first = new Node(start, goal.distance(start) * WALK);
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
			for (PathStep step : neighbors(node.pos)) {
				double cost = stepCost(step);
				if (!Double.isFinite(cost)) {
					continue;
				}
				int submerged = world.isWater(step.dest().above()) ? node.submerged + 1 : 0;
				if (submerged > MAX_SUBMERGED_STEPS) {
					continue;
				}
				if (submerged > 0) {
					cost *= SUBMERGED_MULTIPLIER;
				}
				long key = step.dest().asLong();
				Node next = nodes.get(key);
				if (next == null) {
					next = new Node(step.dest(), goal.distance(step.dest()) * WALK);
					next.g = Double.POSITIVE_INFINITY;
					nodes.put(key, next);
				}
				double g = node.g + cost;
				if (!next.closed && g < next.g) {
					next.g = g;
					next.parent = node;
					next.step = step;
					next.submerged = submerged;
					open.add(next);
				}
			}
		}
		if (closest == first) {
			return null;
		}
		return new Path(build(closest), false);
	}

	private static List<PathStep> build(Node end) {
		List<PathStep> steps = new ArrayList<>();
		for (Node node = end; node.step != null; node = node.parent) {
			steps.add(node.step);
		}
		Collections.reverse(steps);
		return steps;
	}

	private double stepCost(PathStep step) {
		double cost = switch (step.type()) {
			case WALK -> WALK;
			case DIAGONAL -> DIAGONAL;
			case ASCEND -> ASCEND;
			case DESCEND -> WALK + FALL_PER_BLOCK * (step.from().getY() - step.dest().getY());
			case DIG_DOWN -> WALK;
			case PILLAR -> PILLAR;
		};
		for (BlockPos pos : step.toBreak()) {
			cost += world.enterCost(pos);
		}
		if (world.isWater(step.dest())) {
			cost *= SWIM_MULTIPLIER;
		}
		return cost;
	}

	private List<PathStep> neighbors(BlockPos from) {
		List<PathStep> steps = new ArrayList<>(12);
		for (Direction direction : HORIZONTAL) {
			BlockPos side = from.relative(direction);
			addWalk(steps, from, side);
			addAscend(steps, from, side);
			addDescend(steps, from, side);
		}
		for (int dx = -1; dx <= 1; dx += 2) {
			for (int dz = -1; dz <= 1; dz += 2) {
				addDiagonal(steps, from, dx, dz);
			}
		}
		addDigDown(steps, from);
		addPillar(steps, from);
		return steps;
	}

	private boolean enterable(BlockPos pos) {
		return world.passable(pos) || world.breakable(pos);
	}

	private void addWalk(List<PathStep> steps, BlockPos from, BlockPos dest) {
		if (world.supported(dest) && enterable(dest) && enterable(dest.above())) {
			steps.add(new PathStep(from, dest, MoveType.WALK, clearList(dest.above(), dest)));
		}
	}

	private void addAscend(List<PathStep> steps, BlockPos from, BlockPos side) {
		BlockPos dest = side.above();
		if (world.isWater(from) && !world.supported(from)) {
			return;
		}
		if (world.solidFloor(side) && enterable(from.above(2)) && enterable(dest) && enterable(dest.above())) {
			steps.add(new PathStep(from, dest, MoveType.ASCEND, clearList(from.above(2), dest.above(), dest)));
		}
	}

	private void addDescend(List<PathStep> steps, BlockPos from, BlockPos side) {
		if (!world.passable(side) || !world.passable(side.above()) || world.supported(side)) {
			return;
		}
		BlockPos landing = side;
		for (int drop = 1; drop <= MAX_DROP; drop++) {
			landing = landing.below();
			if (!world.passable(landing)) {
				return;
			}
			if (world.supported(landing)) {
				steps.add(new PathStep(from, landing, MoveType.DESCEND, List.of()));
				return;
			}
		}
	}

	private void addDiagonal(List<PathStep> steps, BlockPos from, int dx, int dz) {
		BlockPos dest = from.offset(dx, 0, dz);
		BlockPos sideA = from.offset(dx, 0, 0);
		BlockPos sideB = from.offset(0, 0, dz);
		if (world.supported(dest)
			&& world.passable(dest) && world.passable(dest.above())
			&& world.passable(sideA) && world.passable(sideA.above())
			&& world.passable(sideB) && world.passable(sideB.above())) {
			steps.add(new PathStep(from, dest, MoveType.DIAGONAL, List.of()));
		}
	}

	private void addDigDown(List<PathStep> steps, BlockPos from) {
		BlockPos dest = from.below();
		if (world.breakable(dest) && world.solidFloor(dest.below())) {
			steps.add(new PathStep(from, dest, MoveType.DIG_DOWN, List.of(dest)));
		}
	}

	private void addPillar(List<PathStep> steps, BlockPos from) {
		if (!world.canPillar() || world.isWater(from) || !world.supported(from)) {
			return;
		}
		BlockPos dest = from.above();
		if (enterable(from.above(2))) {
			steps.add(new PathStep(from, dest, MoveType.PILLAR, clearList(from.above(2))));
		}
	}

	/** Blocks among {@code positions} that are not already passable, in the order given. */
	private List<BlockPos> clearList(BlockPos... positions) {
		List<BlockPos> list = new ArrayList<>(positions.length);
		for (BlockPos pos : positions) {
			if (!world.passable(pos)) {
				list.add(pos);
			}
		}
		return list;
	}
}
