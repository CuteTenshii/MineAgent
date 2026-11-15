package moe.tenshii.mineagent.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import moe.tenshii.mineagent.game.task.AttackTask;
import moe.tenshii.mineagent.game.task.WalkTask;
import moe.tenshii.mineagent.path.Goal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Fights back when a mob hits the player while the agent is in control, pausing the running action until the
 * attackers are dead or gone. Hands the decision to the model when health runs low, the fight drags on, or a ranged
 * attacker cannot be reached. Client thread only.
 */
public final class CombatReflex {
	private static final float LOW_HEALTH = 6.0F;
	private static final int FIGHT_LIMIT_TICKS = 20 * 20;
	private static final double CHASE_RANGE = 10.0;
	private static final double FORGET_RANGE = 16.0;
	private static final double NEARBY_HOSTILE_RANGE = 4.0;
	private static final int REPATH_TICKS = 15;
	private static final int CHASE_TIMEOUT_TICKS = 20 * 15;

	private record Attacker(boolean ranged) {
	}

	private static final Map<Integer, Attacker> ATTACKERS = new LinkedHashMap<>();
	private static final Map<String, String> FOUGHT = new LinkedHashMap<>();
	private static final SubTask CHASE = new SubTask();
	private static @Nullable LivingEntity target;
	private static int chaseTicks;
	private static int fightTicks;
	private static int previousSlot = -1;
	private static boolean lowHealthReported;
	private static boolean inAction;
	private static boolean passiveAction;
	private static int passiveActionsLeft;

	private CombatReflex() {
	}

	public sealed interface Outcome {
		/** Nothing to fight; the action may tick normally. */
		record None() implements Outcome {
		}

		/** The reflex drove the player this tick; skip the action's tick. */
		record Fighting() implements Outcome {
		}

		/** The model should decide what to do; the reason says why. */
		record Interrupt(String reason) implements Outcome {
		}
	}

	private static final Outcome NONE = new Outcome.None();
	private static final Outcome FIGHTING = new Outcome.Fighting();

	/** Called for every damage event on the local player. */
	public static void onHit(Minecraft mc, DamageSource source) {
		if (!PlayerControl.inControl() || mc.player == null) {
			return;
		}
		Entity attacker = source.getEntity();
		if (!(attacker instanceof LivingEntity living) || attacker == mc.player || attacker instanceof Player || !living.isAlive()) {
			return;
		}
		boolean ranged = source.getDirectEntity() != null && source.getDirectEntity() != attacker;
		ATTACKERS.merge(attacker.getId(), new Attacker(ranged), (old, hit) -> new Attacker(old.ranged() || hit.ranged()));
	}

	public static void beginAction() {
		inAction = true;
		FOUGHT.clear();
		passiveAction = passiveActionsLeft > 0;
		if (passiveAction) {
			passiveActionsLeft--;
		}
	}

	/** Who was fought during the action and how each fight ended, or null if there was no fight. */
	public static @Nullable String endAction() {
		inAction = false;
		passiveAction = false;
		if (FOUGHT.isEmpty()) {
			return null;
		}
		String summary = foughtSummary();
		FOUGHT.clear();
		return "Fought back during this action: " + summary + ".";
	}

	/**
	 * Called every tick before the running action's own tick. An {@link AttackTask} does its own fighting, so the
	 * reflex leaves it alone.
	 */
	public static Outcome tickDuringAction(Minecraft mc, Task running) {
		if (passiveAction || running instanceof AttackTask) {
			if (fightTicks > 0) {
				endFight(mc);
			}
			return NONE;
		}
		return tick(mc);
	}

	/**
	 * Called every tick while the agent controls the player and no action is running, so the player defends itself
	 * while the model is thinking. Returns true while fighting.
	 */
	public static boolean tickIdle(Minecraft mc, Consumer<String> report) {
		Outcome outcome = tick(mc);
		if (outcome instanceof Outcome.Interrupt interrupt) {
			report.accept(interrupt.reason());
			return false;
		}
		if (outcome instanceof Outcome.None && !FOUGHT.isEmpty() && !inAction) {
			LocalPlayer player = mc.player;
			report.accept(String.format(Locale.ROOT, "While no action was running, you fought back: %s. Health now %.0f/%.0f.",
				foughtSummary(), player.getHealth(), player.getMaxHealth()));
			FOUGHT.clear();
		}
		return outcome instanceof Outcome.Fighting;
	}

	private static Outcome tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null || !player.isAlive()) {
			reset(mc);
			return NONE;
		}
		if (player.getHealth() > LOW_HEALTH) {
			lowHealthReported = false;
		}
		LivingEntity next = pickTarget(mc, player);
		if (next == null) {
			if (fightTicks > 0) {
				endFight(mc);
			}
			return NONE;
		}
		if (fightTicks == 0) {
			PlayerControl.stopMining(mc);
			previousSlot = player.getInventory().getSelectedSlot();
			int weapon = PlayerControl.findBestSlot(player, AttackTask::isWeapon, Comparator.comparingInt(AttackTask::weaponRank));
			if (weapon >= 0) {
				PlayerControl.moveToHand(mc, weapon);
			}
		}
		if (next != target) {
			target = next;
			chaseTicks = 0;
			CHASE.clear(mc);
		}
		FOUGHT.putIfAbsent(label(target), "not killed");
		if (player.getHealth() <= LOW_HEALTH && !lowHealthReported) {
			lowHealthReported = true;
			return standDown(mc, String.format(Locale.ROOT, "Interrupted: health is low (%.0f/%.0f) while fighting %s at %s.",
				player.getHealth(), player.getMaxHealth(), label(target), target.blockPosition().toShortString()));
		}
		if (++fightTicks > FIGHT_LIMIT_TICKS) {
			return standDown(mc, "Interrupted: fought " + label(target) + " for " + FIGHT_LIMIT_TICKS / 20
				+ " seconds without getting rid of it. It is at " + target.blockPosition().toShortString() + ".");
		}
		return engage(mc, player, target);
	}

	private static Outcome engage(Minecraft mc, LocalPlayer player, LivingEntity foe) {
		AgentInput input = PlayerControl.input();
		Vec3 center = foe.getBoundingBox().getCenter();
		if (player.getEyePosition().distanceTo(closestPoint(foe, player)) <= player.entityInteractionRange() - 0.3) {
			CHASE.clear(mc);
			input.clear();
			input.jump = player.isInWater();
			PlayerControl.lookAt(player, center);
			if (player.getAttackStrengthScale(0.5F) >= 0.95F && PlayerControl.isFacing(player, center, 25.0F)) {
				mc.gameMode.attack(player, foe);
				PlayerControl.swingAttack(player);
			}
			return FIGHTING;
		}
		Attacker attacker = ATTACKERS.get(foe.getId());
		boolean ranged = attacker != null && attacker.ranged();
		if (player.distanceTo(foe) > CHASE_RANGE) {
			return giveUpOn(mc, foe, ranged, "is " + Math.round(player.distanceTo(foe)) + " blocks away");
		}
		if (!CHASE.active() || ++chaseTicks % REPATH_TICKS == 0) {
			CHASE.set(mc, new WalkTask(Goal.near(foe.blockPosition(), 1.5), label(foe), false, false, CHASE_TIMEOUT_TICKS));
		}
		try {
			CHASE.tick(mc);
		} catch (TaskFailure unreachable) {
			return giveUpOn(mc, foe, ranged, "cannot be reached");
		}
		return FIGHTING;
	}

	/** Drops an attacker that cannot be fought in melee; one that shoots from there is the model's call. */
	private static Outcome giveUpOn(Minecraft mc, LivingEntity foe, boolean ranged, String why) {
		ATTACKERS.remove(foe.getId());
		FOUGHT.put(label(foe), "out of reach");
		CHASE.clear(mc);
		target = null;
		if (ranged) {
			return standDown(mc, "Interrupted: being shot by " + label(foe) + " at " + foe.blockPosition().toShortString()
				+ ", which " + why + ".");
		}
		PlayerControl.input().clear();
		return FIGHTING;
	}

	private static Outcome standDown(Minecraft mc, String reason) {
		String fought = FOUGHT.isEmpty() ? "" : " Fought so far: " + foughtSummary() + ".";
		FOUGHT.clear();
		ATTACKERS.clear();
		endFight(mc);
		passiveActionsLeft = 1;
		return new Outcome.Interrupt(reason + fought
			+ " Automatic fighting is off for your next action, so you can flee, eat, or fight with the attack tool.");
	}

	private static @Nullable LivingEntity pickTarget(Minecraft mc, LocalPlayer player) {
		List<LivingEntity> candidates = new ArrayList<>();
		ATTACKERS.entrySet().removeIf(entry -> {
			Entity entity = mc.level.getEntity(entry.getKey());
			if (!(entity instanceof LivingEntity living) || !living.isAlive() || living.isRemoved()) {
				if (entity instanceof LivingEntity dead && dead.isDeadOrDying()) {
					FOUGHT.put(label(dead), "killed");
				}
				return true;
			}
			if (living.distanceTo(player) > FORGET_RANGE) {
				FOUGHT.put(label(living), "got away");
				return true;
			}
			candidates.add(living);
			return false;
		});
		if (target != null && !target.isAlive() && target.isDeadOrDying()) {
			FOUGHT.put(label(target), "killed");
		}
		if (candidates.isEmpty()) {
			return null;
		}
		AABB area = player.getBoundingBox().inflate(NEARBY_HOSTILE_RANGE);
		candidates.addAll(mc.level.getEntitiesOfClass(LivingEntity.class, area, entity -> entity instanceof Enemy
			&& !(entity instanceof NeutralMob) && entity.isAlive() && !ATTACKERS.containsKey(entity.getId())));
		return candidates.stream().min(Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).orElse(null);
	}

	private static void endFight(Minecraft mc) {
		CHASE.clear(mc);
		target = null;
		fightTicks = 0;
		chaseTicks = 0;
		PlayerControl.input().clear();
		if (previousSlot >= 0 && mc.player != null) {
			mc.player.getInventory().setSelectedSlot(previousSlot);
		}
		previousSlot = -1;
	}

	private static void reset(Minecraft mc) {
		ATTACKERS.clear();
		passiveActionsLeft = 0;
		if (fightTicks > 0) {
			endFight(mc);
		}
	}

	/** Forgets attackers and pending reports, e.g. when the agent stops or the player dies. */
	public static void clear(Minecraft mc) {
		reset(mc);
		FOUGHT.clear();
		lowHealthReported = false;
	}

	private static String foughtSummary() {
		List<String> parts = new ArrayList<>();
		FOUGHT.forEach((foe, fate) -> parts.add(foe + " (" + fate + ")"));
		return String.join(", ", parts);
	}

	private static String label(Entity entity) {
		return Lookup.name(entity.getType()) + " #" + entity.getId();
	}

	private static Vec3 closestPoint(Entity entity, LocalPlayer player) {
		AABB box = entity.getBoundingBox();
		Vec3 eye = player.getEyePosition();
		return new Vec3(
			Math.clamp(eye.x, box.minX, box.maxX), Math.clamp(eye.y, box.minY, box.maxY), Math.clamp(eye.z, box.minZ, box.maxZ));
	}
}
