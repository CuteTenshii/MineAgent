package moe.tenshii.mineagent.game.task;

import java.util.Comparator;
import java.util.function.Predicate;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.SubTask;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.path.Goal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Chases and melee-attacks living entities until the requested number are dead.
 */
public final class AttackTask implements Task {
	private static final double SEARCH_RADIUS = 32.0;
	private static final int REPATH_TICKS = 15;
	private static final int TARGET_PATIENCE_TICKS = 20 * 45;

	private final Predicate<Entity> filter;
	private final String label;
	private final int wanted;
	private final SubTask sub = new SubTask();
	private @Nullable LivingEntity target;
	private int kills;
	private int targetTicks;
	private boolean weaponChosen;

	public AttackTask(Predicate<Entity> filter, String label, int count) {
		this.filter = filter;
		this.label = label;
		this.wanted = count;
	}

	@Override
	public @Nullable String tick(Minecraft mc) throws TaskFailure {
		LocalPlayer player = mc.player;
		PlayerControl.takeControl(mc);
		if (!weaponChosen) {
			weaponChosen = true;
			int slot = PlayerControl.findBestSlot(player, AttackTask::isWeapon, Comparator.comparingInt(AttackTask::weaponRank));
			if (slot >= 0) {
				PlayerControl.moveToHand(mc, slot);
			}
		}
		if (target != null && (!target.isAlive() || target.isRemoved())) {
			if (target.isDeadOrDying()) {
				kills++;
			}
			target = null;
			sub.clear(mc);
			if (kills >= wanted) {
				return "Killed " + kills + " " + label + ".";
			}
		}
		if (target == null) {
			target = findTarget(mc, player);
			targetTicks = 0;
			if (target == null) {
				if (kills == 0) {
					throw new TaskFailure("No " + label + " within " + (int) SEARCH_RADIUS + " blocks.");
				}
				return "Killed " + kills + " " + label + "; no more found nearby.";
			}
		}
		if (++targetTicks > TARGET_PATIENCE_TICKS) {
			throw new TaskFailure("Could not finish off the " + Lookup.name(target.getType()) + " in time (killed " + kills + ").");
		}
		double distance = player.getEyePosition().distanceTo(closestPoint(target, player));
		if (distance > player.entityInteractionRange() - 0.3) {
			if (!sub.active() || targetTicks % REPATH_TICKS == 0) {
				sub.set(mc, new WalkTask(Goal.near(target.blockPosition(), 1.5), Lookup.name(target.getType()), true, false, 20 * 30));
			}
			try {
				sub.tick(mc);
			} catch (TaskFailure unreachable) {
				throw new TaskFailure("Cannot reach the " + Lookup.name(target.getType()) + ": " + unreachable.getMessage());
			}
			return null;
		}
		sub.clear(mc);
		PlayerControl.input().clear();
		PlayerControl.lookAt(player, target.getBoundingBox().getCenter());
		if (player.getAttackStrengthScale(0.5F) >= 0.95F && PlayerControl.isFacing(player, target.getBoundingBox().getCenter(), 25.0F)) {
			mc.gameMode.attack(player, target);
			PlayerControl.swingAttack(player);
		}
		return null;
	}

	private @Nullable LivingEntity findTarget(Minecraft mc, LocalPlayer player) {
		AABB area = player.getBoundingBox().inflate(SEARCH_RADIUS);
		return mc.level.getEntitiesOfClass(LivingEntity.class, area,
				entity -> entity != player && entity.isAlive() && !(entity instanceof Player && entity.isSpectator()) && filter.test(entity))
			.stream()
			.min(Comparator.comparingDouble(entity -> entity.distanceToSqr(player)))
			.orElse(null);
	}

	private static Vec3 closestPoint(Entity entity, LocalPlayer player) {
		AABB box = entity.getBoundingBox();
		Vec3 eye = player.getEyePosition();
		return new Vec3(
			Math.clamp(eye.x, box.minX, box.maxX), Math.clamp(eye.y, box.minY, box.maxY), Math.clamp(eye.z, box.minZ, box.maxZ));
	}

	private static boolean isWeapon(ItemStack stack) {
		return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES);
	}

	private static int weaponRank(ItemStack stack) {
		int tier = stack.getMaxDamage();
		return (stack.is(ItemTags.SWORDS) ? 100_000 : 0) + tier;
	}

	@Override
	public void stop(Minecraft mc) {
		sub.clear(mc);
		PlayerControl.idle(mc);
	}

	@Override
	public int timeoutTicks() {
		return 20 * 60 * 3;
	}

	@Override
	public String describe() {
		return target == null ? "hunting " + label : "attacking " + Lookup.name(target.getType());
	}
}
