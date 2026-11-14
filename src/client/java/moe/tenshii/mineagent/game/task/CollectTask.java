package moe.tenshii.mineagent.game.task;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import moe.tenshii.mineagent.game.Inventories;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.SubTask;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.path.Goal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Walks over dropped items near a point until none are left or time runs out.
 */
public final class CollectTask implements Task {
	private static final int RETARGET_TICKS = 40;

	private final BlockPos center;
	private final double radius;
	private final @Nullable Predicate<ItemStack> filter;
	private final int timeout;
	private final SubTask sub = new SubTask();
	private @Nullable Map<Item, Integer> before;
	private @Nullable ItemEntity target;
	private int ticks;
	private int targetTicks;
	private int skipped;

	public CollectTask(BlockPos center, double radius, @Nullable Predicate<ItemStack> filter, int timeoutTicks) {
		this.center = center.immutable();
		this.radius = radius;
		this.filter = filter;
		this.timeout = timeoutTicks;
	}

	@Override
	public @Nullable String tick(Minecraft mc) throws TaskFailure {
		LocalPlayer player = mc.player;
		PlayerControl.takeControl(mc);
		if (before == null) {
			before = Inventories.snapshot(player);
		}
		ticks++;
		if (ticks >= timeout - 2) {
			return result(player);
		}
		if (target != null && (target.isRemoved() || ++targetTicks > RETARGET_TICKS * 4)) {
			if (!target.isRemoved()) {
				skipped++;
			}
			target = null;
			sub.clear(mc);
		}
		if (target == null) {
			target = nearest(mc, player);
			targetTicks = 0;
			if (target == null) {
				// Fresh drops can take a moment to appear on the client.
				return ticks < 10 ? null : result(player);
			}
		}
		if (targetTicks % RETARGET_TICKS == 0) {
			sub.set(mc, new WalkTask(Goal.near(target.blockPosition(), 0.0), "item", true, false, 20 * 30));
		}
		try {
			sub.tick(mc);
		} catch (TaskFailure unreachable) {
			skipped++;
			target = null;
		}
		return null;
	}

	private @Nullable ItemEntity nearest(Minecraft mc, LocalPlayer player) {
		Vec3 origin = Vec3.atCenterOf(center);
		List<ItemEntity> items = mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(radius),
			item -> item.isAlive() && item.position().distanceTo(origin) <= radius && (filter == null || filter.test(item.getItem())));
		return items.stream().min(Comparator.comparingDouble(item -> item.distanceToSqr(player))).orElse(null);
	}

	private String result(LocalPlayer player) {
		String text = "Collected items: " + Inventories.diff(before, Inventories.snapshot(player));
		return skipped > 0 ? text + " (" + skipped + " item stacks could not be reached)." : text + ".";
	}

	@Override
	public void stop(Minecraft mc) {
		sub.clear(mc);
		PlayerControl.idle(mc);
	}

	@Override
	public int timeoutTicks() {
		return timeout;
	}

	@Override
	public String describe() {
		return "collecting items";
	}
}
