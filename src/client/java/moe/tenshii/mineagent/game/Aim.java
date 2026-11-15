package moe.tenshii.mineagent.game;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Where the player should look: a block, an entity, or an angle.
 */
public sealed interface Aim {
	/** Yaw and pitch in degrees, as Minecraft stores them: yaw 0 faces south, pitch -90 looks straight up. */
	record Rotation(float yaw, float pitch) {
	}

	/** The rotation that faces the target from where the player is now. */
	Rotation rotation(Minecraft mc, LocalPlayer player) throws TaskFailure;

	String label();

	record AtBlock(BlockPos pos) implements Aim {
		@Override
		public Rotation rotation(Minecraft mc, LocalPlayer player) {
			return towards(player, Vec3.atCenterOf(pos));
		}

		@Override
		public String label() {
			return "block " + pos.toShortString();
		}
	}

	record AtEntity(int id) implements Aim {
		@Override
		public Rotation rotation(Minecraft mc, LocalPlayer player) throws TaskFailure {
			Entity entity = mc.level.getEntity(id);
			if (entity == null || entity.isRemoved()) {
				throw new TaskFailure("Entity #" + id + " is not loaded nearby; use find_entities for current ids.");
			}
			return towards(player, entity.getBoundingBox().getCenter());
		}

		@Override
		public String label() {
			return "entity #" + id;
		}
	}

	/** A fixed angle; a null part keeps the player's current yaw or pitch. */
	record Angle(@Nullable Float yaw, @Nullable Float pitch) implements Aim {
		@Override
		public Rotation rotation(Minecraft mc, LocalPlayer player) {
			return new Rotation(yaw == null ? player.getYRot() : yaw, pitch == null ? player.getXRot() : Mth.clamp(pitch, -90.0F, 90.0F));
		}

		@Override
		public String label() {
			if (yaw == null) {
				return String.format(Locale.ROOT, "pitch %.0f", pitch);
			}
			return pitch == null ? String.format(Locale.ROOT, "yaw %.0f", yaw) : String.format(Locale.ROOT, "yaw %.0f, pitch %.0f", yaw, pitch);
		}
	}

	/** north, south, east, west (level), or up and down (keeping the yaw). */
	static Aim direction(String name) throws TaskFailure {
		return switch (name.toLowerCase(Locale.ROOT)) {
			case "south" -> new Angle(0.0F, 0.0F);
			case "west" -> new Angle(90.0F, 0.0F);
			case "north" -> new Angle(180.0F, 0.0F);
			case "east" -> new Angle(-90.0F, 0.0F);
			case "up" -> new Angle(null, -90.0F);
			case "down" -> new Angle(null, 90.0F);
			default -> throw new TaskFailure("direction must be north, south, east, west, up or down.");
		};
	}

	/**
	 * Turns one tick's worth toward the target.
	 *
	 * @return whether the player already faced it within {@code tolerance} degrees before this tick
	 */
	static boolean turnStep(Minecraft mc, LocalPlayer player, Aim aim, float tolerance) throws TaskFailure {
		Rotation wanted = aim.rotation(mc, player);
		boolean facing = Math.abs(Mth.wrapDegrees(wanted.yaw() - player.getYRot())) <= tolerance
			&& Math.abs(wanted.pitch() - player.getXRot()) <= tolerance;
		if (!facing) {
			PlayerControl.turnTowards(player, wanted.yaw(), wanted.pitch());
		}
		return facing;
	}

	private static Rotation towards(LocalPlayer player, Vec3 target) {
		Vec3 eye = player.getEyePosition();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
		float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
		return new Rotation(yaw, pitch);
	}
}
