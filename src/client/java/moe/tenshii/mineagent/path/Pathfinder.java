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
	// Ticks per block walking (4.3 blocks per second) and sprinting (5.6).
	private static final double WALK = 4.6;
	private static final double SPRINT = 3.6;
	private static final double ASCEND = 7.0;
	private static final double FALL_PER_BLOCK = 1.5;
	private static final double SWIM_MULTIPLIER = 2.5;
	private static final double PILLAR = 18.0;
	private static final double SUBMERGED_MULTIPLIER = 4.0;
	// Ladders are climbed at about 2.4 blocks per second and slid down at 3.
	private static final double CLIMB_UP = 8.5;
	private static final double CLIMB_DOWN = 6.7;
	// Stopping to open a door or gate.
	private static final double OPEN_DOOR = 6.0;
	// Roughly half the player's 15 seconds of air at swimming speed, leaving margin to come back up.
	private static final int MAX_SUBMERGED_STEPS = 10;
	private static final int MAX_DROP = 3;
	// Landing in water cancels fall damage, so a drop into it can be much longer.
	private static final int MAX_WATER_DROP = 32;
	private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

	private final PathWorld world;
	private final int maxNodes;
	private final double walk;

	public Pathfinder(PathWorld world, int maxNodes) {
		this.world = world;
		this.maxNodes = maxNodes;
		this.walk = world.canSprint() ? SPRINT : WALK;
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

	/** Starts a search from {@code start}; call {@link PathSearch#advance()} each tick until it ends. */
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
			super("walk search", maxNodes);
			this.goal = goal;
			first = new Node(start, goal.distance(start) * walk);
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
					next = new Node(step.dest(), goal.distance(step.dest()) * walk);
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
			case WALK -> walk;
			case DIAGONAL -> walk * Math.sqrt(2.0);
			case ASCEND -> ASCEND;
			case DESCEND -> walk + FALL_PER_BLOCK * (step.from().getY() - step.dest().getY());
			case DIG_DOWN -> walk;
			case PILLAR -> PILLAR;
			case CLIMB_UP -> CLIMB_UP;
			case CLIMB_DOWN -> CLIMB_DOWN;
			case FLY -> Double.POSITIVE_INFINITY;
		};
		if (world.isDoor(step.dest())) {
			cost += OPEN_DOOR;
		}
		for (BlockPos pos : step.toBreak()) {
			cost += world.enterCost(pos);
		}
		if (world.isWater(step.dest())) {
			cost *= SWIM_MULTIPLIER;
		}
		return cost;
	}

	private List<PathStep> neighbors(BlockPos from) {
		List<PathStep> steps = new ArrayList<>(14);
		if (world.isDoor(from) || world.isDoor(from.above())) {
			// Inside a doorway the panel blocks every way out except straight through.
			for (Direction direction : HORIZONTAL) {
				if (throughDoor(from, direction.getAxis()) && throughDoor(from.above(), direction.getAxis())) {
					addWalk(steps, from, from.relative(direction), direction.getAxis());
				}
			}
			return steps;
		}
		for (Direction direction : HORIZONTAL) {
			BlockPos side = from.relative(direction);
			addWalk(steps, from, side, direction.getAxis());
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
		addClimb(steps, from);
		return steps;
	}

	/** Clear to stand or move in without breaking anything; ladders and vines count, since the player passes through them. */
	private boolean open(BlockPos pos) {
		return world.passable(pos) || world.climbable(pos);
	}

	/** Feet can stay here: on a floor, swimming, or holding on to a ladder or vine. */
	private boolean standable(BlockPos feet) {
		return world.supported(feet) || world.climbable(feet);
	}

	private boolean enterable(BlockPos pos) {
		return open(pos) || world.breakable(pos);
	}

	private boolean throughDoor(BlockPos pos, Direction.Axis axis) {
		return !world.isDoor(pos) || world.doorway(pos, axis);
	}

	private void addWalk(List<PathStep> steps, BlockPos from, BlockPos dest, Direction.Axis axis) {
		if (standable(dest) && (enterable(dest) || world.doorway(dest, axis)) && (enterable(dest.above()) || world.doorway(dest.above(), axis))) {
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
		if (!open(side) || !open(side.above()) || standable(side)) {
			return;
		}
		BlockPos landing = side;
		for (int drop = 1; drop <= MAX_WATER_DROP; drop++) {
			landing = landing.below();
			if (!open(landing)) {
				return;
			}
			boolean water = world.isWater(landing);
			if (water || standable(landing)) {
				if (water || drop <= MAX_DROP) {
					steps.add(new PathStep(from, landing, MoveType.DESCEND, List.of()));
				}
				return;
			}
		}
	}

	private void addDiagonal(List<PathStep> steps, BlockPos from, int dx, int dz) {
		BlockPos dest = from.offset(dx, 0, dz);
		BlockPos sideA = from.offset(dx, 0, 0);
		BlockPos sideB = from.offset(0, 0, dz);
		if (standable(dest)
			&& open(dest) && open(dest.above())
			&& open(sideA) && open(sideA.above())
			&& open(sideB) && open(sideB.above())) {
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
		if (!world.canPillar() || world.isWater(from) || world.climbable(from) || !world.supported(from)) {
			return;
		}
		BlockPos dest = from.above();
		if (enterable(from.above(2))) {
			steps.add(new PathStep(from, dest, MoveType.PILLAR, clearList(from.above(2))));
		}
	}

	private void addClimb(List<PathStep> steps, BlockPos from) {
		if (world.climbable(from) && world.climbable(from.above()) && open(from.above(2))) {
			steps.add(new PathStep(from, from.above(), MoveType.CLIMB_UP, List.of()));
		}
		if (world.climbable(from.below())) {
			steps.add(new PathStep(from, from.below(), MoveType.CLIMB_DOWN, List.of()));
		}
	}

	/** Blocks among {@code positions} that must be broken to pass: not already open, and never doors or gates. */
	private List<BlockPos> clearList(BlockPos... positions) {
		List<BlockPos> list = new ArrayList<>(positions.length);
		for (BlockPos pos : positions) {
			if (!open(pos) && !world.isDoor(pos)) {
				list.add(pos);
			}
		}
		return list;
	}
}
