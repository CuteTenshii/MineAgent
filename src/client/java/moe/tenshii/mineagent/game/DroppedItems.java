package moe.tenshii.mineagent.game;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

/**
 * Remembers what the agent threw away and where, so automatic item pickup does not grab it straight back.
 * Client thread only.
 */
public final class DroppedItems {
	// Dropped items despawn after five minutes, so older entries no longer matter.
	private static final long REMEMBER_MILLIS = 5 * 60 * 1000;
	private static final double RADIUS = 6.0;

	private record Drop(Item item, Vec3 where, long at) {
	}

	private static final List<Drop> DROPS = new ArrayList<>();

	private DroppedItems() {
	}

	public static void record(Item item, Vec3 where) {
		DROPS.add(new Drop(item, where, System.currentTimeMillis()));
	}

	public static boolean isOwnDrop(ItemEntity entity) {
		long now = System.currentTimeMillis();
		DROPS.removeIf(drop -> now - drop.at() > REMEMBER_MILLIS);
		for (Drop drop : DROPS) {
			if (entity.getItem().is(drop.item()) && entity.position().distanceTo(drop.where()) <= RADIUS) {
				return true;
			}
		}
		return false;
	}
}
