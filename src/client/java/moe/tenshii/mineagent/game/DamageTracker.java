package moe.tenshii.mineagent.game;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Records the damage the player takes during one tool call: how much health was lost and what dealt each hit.
 * Client thread only.
 */
public final class DamageTracker {
	private static boolean tracking;
	private static float lastHealth;
	private static float healthLost;
	private static final Map<String, Integer> HITS = new LinkedHashMap<>();

	private DamageTracker() {
	}

	public static void begin(Minecraft mc) {
		tracking = true;
		healthLost = 0.0F;
		HITS.clear();
		lastHealth = mc.player == null ? 0.0F : mc.player.getHealth();
		AirReflex.beginAction();
	}

	/** Sums health decreases tick by tick, so healing in between does not hide earlier hits. */
	public static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (!tracking || player == null) {
			return;
		}
		float health = player.getHealth();
		if (health < lastHealth && player.isAlive()) {
			healthLost += lastHealth - health;
		}
		lastHealth = health;
	}

	public static void onDamage(Minecraft mc, DamageSource source) {
		if (!tracking) {
			return;
		}
		HITS.merge(describe(source), 1, Integer::sum);
	}

	/**
	 * Stops tracking and summarises the damage taken and any pauses to surface for air, or returns null if neither
	 * happened.
	 */
	public static @Nullable String end(Minecraft mc) {
		tick(mc);
		tracking = false;
		int surfacings = AirReflex.endAction();
		String damage = damageSummary(mc);
		String air = surfacings == 0 ? null
			: "Paused " + surfacings + (surfacings == 1 ? " time" : " times") + " to swim up for air during this action.";
		if (damage == null) {
			return air;
		}
		return air == null ? damage : damage + " " + air;
	}

	private static @Nullable String damageSummary(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (HITS.isEmpty() && healthLost <= 0.0F || player == null) {
			return null;
		}
		StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "Took %.0f damage during this action", healthLost));
		if (!HITS.isEmpty()) {
			text.append(" (");
			boolean first = true;
			for (Map.Entry<String, Integer> hit : HITS.entrySet()) {
				text.append(first ? "" : ", ").append(hit.getKey()).append(" x").append(hit.getValue());
				first = false;
			}
			text.append(')');
		}
		return text.append(String.format(Locale.ROOT, "; health now %.0f/%.0f.", player.getHealth(), player.getMaxHealth())).toString();
	}

	private static String describe(DamageSource source) {
		Entity attacker = source.getEntity();
		if (attacker == null) {
			attacker = source.getDirectEntity();
		}
		if (attacker instanceof Player other) {
			return "player " + other.getName().getString() + " #" + other.getId();
		}
		if (attacker != null) {
			return Lookup.name(attacker.getType()) + " #" + attacker.getId();
		}
		return source.typeHolder().unwrapKey().map(key -> key.identifier().getPath()).orElse("unknown");
	}
}
