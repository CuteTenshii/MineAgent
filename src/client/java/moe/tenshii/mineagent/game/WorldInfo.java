package moe.tenshii.mineagent.game;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import moe.tenshii.mineagent.config.AgentConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Read-only descriptions of the world for the model. Client thread only.
 */
public final class WorldInfo {
	private static final int SURROUNDING_RADIUS = 8;
	private static final double ENTITY_RADIUS = 24.0;
	private static final int MAX_ENTITIES = 16;

	private WorldInfo() {
	}

	public static String observe(Minecraft mc) {
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null) {
			throw new GameThread.GameStateException("Not in a world.");
		}
		BlockPos feet = player.blockPosition();
		StringBuilder out = new StringBuilder();
		out.append(String.format(Locale.ROOT, "Position: x=%.1f y=%.1f z=%.1f (block %s), facing %s (yaw %.0f, pitch %.0f), dimension %s, biome %s%n",
			player.getX(), player.getY(), player.getZ(), feet.toShortString(), player.getDirection().getName(), Mth.wrapDegrees(player.getYRot()), player.getXRot(),
			level.dimension().identifier(), level.getBiome(feet).unwrapKey().map(key -> key.identifier().getPath()).orElse("unknown")));
		out.append(String.format(Locale.ROOT, "Health %.0f/%.0f, food %d/20, saturation %.1f, air %d/%d, armor %d, XP level %d%n",
			player.getHealth(), player.getMaxHealth(), player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel(),
			player.getAirSupply(), player.getMaxAirSupply(), player.getArmorValue(), player.experienceLevel));
		long dayTime = Math.floorMod(level.getDefaultClockTime(), 24000L);
		out.append(String.format(Locale.ROOT, "Time of day: %d/24000 (%s)%s, game mode %s%n", dayTime,
			level.isDarkOutside() ? "night, hostile mobs spawn" : "day",
			level.isThundering() ? ", thunderstorm" : level.isRaining() ? ", raining" : "",
			mc.gameMode.getPlayerMode().getName()));
		out.append("Abilities: ").append(abilities(player)).append('\n');
		out.append("Effects: ").append(effects(player)).append('\n');
		out.append("Standing on: ").append(blockName(level, feet.below()));
		if (player.isInWater()) {
			out.append(", in water");
		}
		if (player.isInLava()) {
			out.append(", IN LAVA");
		}
		if (player.isOnFire()) {
			out.append(", ON FIRE");
		}
		out.append('\n');
		HitResult hit = mc.hitResult;
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			out.append("Crosshair on: ").append(blockName(level, blockHit.getBlockPos())).append(" at ").append(blockHit.getBlockPos().toShortString()).append('\n');
		}
		out.append("Held: ").append(stackName(player.getMainHandItem()))
			.append(" (hotbar slot ").append(player.getInventory().getSelectedSlot()).append(")");
		if (!player.getOffhandItem().isEmpty()) {
			out.append(", offhand: ").append(stackName(player.getOffhandItem()));
		}
		out.append('\n');
		out.append("Armor: ").append(armor(player)).append('\n');
		out.append("Inventory: ").append(inventory(player)).append('\n');
		out.append("Nearby entities: ").append(entities(level, player)).append('\n');
		out.append("Blocks within ").append(SURROUNDING_RADIUS).append(" (type xcount, nearest): ").append(surroundings(level, feet));
		return out.toString();
	}

	/** What the creative-style tools can do right now: flight, creative items and commands. */
	public static String abilities(LocalPlayer player) {
		List<String> parts = new ArrayList<>();
		if (!Flight.canFly(player)) {
			parts.add("cannot fly");
		} else {
			parts.add(Flight.isFlying(player) ? "flying now (fly_to, fly)" : "can fly (fly_to, fly)");
		}
		parts.add(player.hasInfiniteMaterials() ? "creative items via give_item" : "no creative items");
		String level = player.permissions().hasPermission(Permissions.COMMANDS_OWNER) ? "owner"
			: player.permissions().hasPermission(Permissions.COMMANDS_ADMIN) ? "admin"
			: player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) ? "game master"
			: player.permissions().hasPermission(Permissions.COMMANDS_MODERATOR) ? "moderator"
			: null;
		String permission = level == null ? "no operator command permission" : level + " command permission";
		parts.add(permission + (AgentConfig.get().allowCommands ? ", run_command on"
			: ", run_command off (the person you work for can enable it with /agent commands on)"));
		return String.join("; ", parts);
	}

	public static String inventory(LocalPlayer player) {
		Map<net.minecraft.world.item.Item, Integer> counts = Inventories.snapshot(player);
		int free = 0;
		for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; slot++) {
			if (player.getInventory().getItem(slot).isEmpty()) {
				free++;
			}
		}
		return Inventories.describe(counts) + " (" + free + " free slots)";
	}

	/**
	 * A one-line warning when the player's condition needs attention, or null when nothing does.
	 */
	public static @Nullable String alerts(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || !player.isAlive()) {
			return null;
		}
		List<String> problems = new ArrayList<>();
		if (player.getHealth() <= player.getMaxHealth() / 2) {
			problems.add(String.format(Locale.ROOT, "health %.0f/%.0f", player.getHealth(), player.getMaxHealth()));
		}
		if (player.getFoodData().getFoodLevel() <= 10) {
			problems.add("food " + player.getFoodData().getFoodLevel() + "/20");
		}
		if (player.getAirSupply() < player.getMaxAirSupply() / 2) {
			problems.add("air " + player.getAirSupply() + "/" + player.getMaxAirSupply());
		}
		if (player.isOnFire()) {
			problems.add("on fire");
		}
		if (player.isInLava()) {
			problems.add("in lava");
		}
		boolean harmfulEffect = player.getActiveEffects().stream()
			.anyMatch(instance -> instance.getEffect().value().getCategory() == MobEffectCategory.HARMFUL);
		if (harmfulEffect) {
			problems.add("effects: " + effects(player));
		}
		return problems.isEmpty() ? null : "Warning: " + String.join(", ", problems) + ".";
	}

	/** "poison II 0:12 (harmful), speed I 2:30" or "none". */
	public static String effects(LocalPlayer player) {
		List<String> parts = new ArrayList<>();
		for (MobEffectInstance instance : player.getActiveEffects()) {
			MobEffect effect = instance.getEffect().value();
			String name = Lookup.shorten(BuiltInRegistries.MOB_EFFECT.getKey(effect));
			String level = toRoman(instance.getAmplifier() + 1);
			String time;
			if (instance.isInfiniteDuration()) {
				time = "infinite";
			} else {
				int seconds = instance.getDuration() / 20;
				time = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
			}
			String kind = switch (effect.getCategory()) {
				case HARMFUL -> " (harmful)";
				case BENEFICIAL -> "";
				case NEUTRAL -> " (neutral)";
			};
			parts.add(name + " " + level + " " + time + kind);
		}
		return parts.isEmpty() ? "none" : String.join(", ", parts);
	}

	private static String toRoman(int number) {
		return switch (number) {
			case 1 -> "I";
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			case 5 -> "V";
			default -> String.valueOf(number);
		};
	}

	private static String armor(LocalPlayer player) {
		List<String> worn = new ArrayList<>();
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack stack = player.getItemBySlot(slot);
			if (!stack.isEmpty()) {
				worn.add(Lookup.name(stack.getItem()));
			}
		}
		return worn.isEmpty() ? "none" : String.join(", ", worn);
	}

	private static String entities(ClientLevel level, LocalPlayer player) {
		List<Entity> nearby = level.getEntities(player, player.getBoundingBox().inflate(ENTITY_RADIUS), Entity::isAlive);
		if (nearby.isEmpty()) {
			return "none";
		}
		nearby.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(player)));
		List<String> parts = new ArrayList<>();
		for (Entity entity : nearby.subList(0, Math.min(MAX_ENTITIES, nearby.size()))) {
			parts.add(describeEntity(entity, player));
		}
		if (nearby.size() > MAX_ENTITIES) {
			parts.add("and " + (nearby.size() - MAX_ENTITIES) + " more");
		}
		return String.join("; ", parts);
	}

	public static String describeEntity(Entity entity, LocalPlayer player) {
		StringBuilder text = new StringBuilder();
		if (entity instanceof ItemEntity item) {
			text.append("dropped ").append(stackName(item.getItem()));
		} else if (entity instanceof Player other) {
			text.append("player ").append(other.getName().getString());
		} else {
			text.append(Lookup.name(entity.getType()));
		}
		text.append(" #").append(entity.getId());
		BlockPos pos = entity.blockPosition();
		text.append(String.format(Locale.ROOT, " at %s (%.0fm)", pos.toShortString(), Math.sqrt(entity.distanceToSqr(player))));
		if (entity instanceof LivingEntity living && !(entity instanceof Player)) {
			text.append(String.format(Locale.ROOT, " hp %.0f", living.getHealth()));
		}
		if (entity instanceof Enemy) {
			text.append(" HOSTILE");
		}
		return text.toString();
	}

	private static String surroundings(ClientLevel level, BlockPos center) {
		record Seen(int count, BlockPos nearest, double distance) {
		}
		Map<Block, Seen> seen = new LinkedHashMap<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -SURROUNDING_RADIUS; dx <= SURROUNDING_RADIUS; dx++) {
			for (int dy = -SURROUNDING_RADIUS; dy <= SURROUNDING_RADIUS; dy++) {
				for (int dz = -SURROUNDING_RADIUS; dz <= SURROUNDING_RADIUS; dz++) {
					pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					BlockState state = level.getBlockState(pos);
					if (state.isAir()) {
						continue;
					}
					double distance = pos.distSqr(center);
					Seen previous = seen.get(state.getBlock());
					if (previous == null) {
						seen.put(state.getBlock(), new Seen(1, pos.immutable(), distance));
					} else if (distance < previous.distance()) {
						seen.put(state.getBlock(), new Seen(previous.count() + 1, pos.immutable(), distance));
					} else {
						seen.put(state.getBlock(), new Seen(previous.count() + 1, previous.nearest(), previous.distance()));
					}
				}
			}
		}
		List<String> parts = new ArrayList<>();
		seen.entrySet().stream()
			.sorted(Comparator.comparingDouble(entry -> entry.getValue().distance()))
			.forEach(entry -> parts.add(Lookup.name(entry.getKey()) + " x" + entry.getValue().count() + " " + entry.getValue().nearest().toShortString()));
		return parts.isEmpty() ? "only air" : String.join("; ", parts);
	}

	public record Found(BlockPos pos, double distance, boolean exposed) {
	}

	/** Matching blocks within a cube, nearest first. Skips chunk sections that cannot contain a match. */
	public static List<Found> findBlocks(ClientLevel level, BlockPos center, int radius, Predicate<BlockState> matches, int limit) {
		List<Found> found = new ArrayList<>();
		int minY = Math.max(level.getMinY(), center.getY() - radius);
		int maxY = Math.min(level.getMaxY(), center.getY() + radius);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int chunkX = (center.getX() - radius) >> 4; chunkX <= (center.getX() + radius) >> 4; chunkX++) {
			for (int chunkZ = (center.getZ() - radius) >> 4; chunkZ <= (center.getZ() + radius) >> 4; chunkZ++) {
				if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
					continue;
				}
				LevelChunk chunk = level.getChunk(chunkX, chunkZ);
				for (int sectionY = minY >> 4; sectionY <= maxY >> 4; sectionY++) {
					LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
					if (section.hasOnlyAir() || !section.maybeHas(matches)) {
						continue;
					}
					for (int y = Math.max(minY, sectionY << 4); y <= Math.min(maxY, (sectionY << 4) + 15); y++) {
						for (int x = Math.max(center.getX() - radius, chunkX << 4); x <= Math.min(center.getX() + radius, (chunkX << 4) + 15); x++) {
							for (int z = Math.max(center.getZ() - radius, chunkZ << 4); z <= Math.min(center.getZ() + radius, (chunkZ << 4) + 15); z++) {
								BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
								if (matches.test(state)) {
									pos.set(x, y, z);
									found.add(new Found(pos.immutable(), Math.sqrt(pos.distSqr(center)), isExposed(level, pos)));
								}
							}
						}
					}
				}
			}
		}
		found.sort(Comparator.comparingDouble(Found::distance));
		return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
	}

	public static @Nullable BlockPos nearestBlock(ClientLevel level, BlockPos center, int radius, Predicate<BlockState> matches) {
		List<Found> found = findBlocks(level, center, radius, matches, 1);
		return found.isEmpty() ? null : found.getFirst().pos();
	}

	private static boolean isExposed(ClientLevel level, BlockPos pos) {
		for (Direction direction : Direction.values()) {
			BlockPos neighbor = pos.relative(direction);
			if (level.getBlockState(neighbor).getCollisionShape(level, neighbor).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	public static String blockName(ClientLevel level, BlockPos pos) {
		return Lookup.name(level.getBlockState(pos).getBlock());
	}

	public static String stackName(ItemStack stack) {
		if (stack.isEmpty()) {
			return "nothing";
		}
		String name = Lookup.name(stack.getItem());
		List<String> details = new ArrayList<>();
		PotionContents potion = stack.get(DataComponents.POTION_CONTENTS);
		if (potion != null) {
			potion.potion().flatMap(Holder::unwrapKey).ifPresent(key -> details.add(Lookup.shorten(key.identifier())));
		}
		if (stack.isDamageableItem()) {
			details.add("durability " + (stack.getMaxDamage() - stack.getDamageValue()) + "/" + stack.getMaxDamage());
		}
		addEnchantments(details, stack.get(DataComponents.ENCHANTMENTS));
		addEnchantments(details, stack.get(DataComponents.STORED_ENCHANTMENTS));
		if (!details.isEmpty()) {
			name += " (" + String.join(", ", details) + ")";
		}
		return stack.getCount() > 1 ? name + " x" + stack.getCount() : name;
	}

	private static void addEnchantments(List<String> details, @Nullable ItemEnchantments enchantments) {
		if (enchantments != null) {
			for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
				details.add(Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
			}
		}
	}

	/** Which way the player faces and the block at the center of its view. */
	public static String view(Minecraft mc, LocalPlayer player) {
		StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "facing %s (yaw %.0f, pitch %.0f; yaw 0 is south, 90 west, negative pitch looks up).",
			player.getDirection().getName(), Mth.wrapDegrees(player.getYRot()), player.getXRot()));
		HitResult hit = player.pick(64.0, 1.0F, false);
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			Vec3 eye = player.getEyePosition();
			text.append(String.format(Locale.ROOT, " The center of the view is %s at %s, %.1f blocks away.",
				blockName(mc.level, blockHit.getBlockPos()), blockHit.getBlockPos().toShortString(), eye.distanceTo(hit.getLocation())));
		} else {
			text.append(" Nothing solid is at the center of the view within 64 blocks.");
		}
		return text.toString();
	}

	public static String inspectBlock(ClientLevel level, LocalPlayer player, BlockPos pos) {
		// ClientLevel.hasChunk is always true; only the chunk cache knows which chunks the server has sent.
		if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
			return pos.toShortString() + " is not loaded.";
		}
		BlockState state = level.getBlockState(pos);
		StringBuilder text = new StringBuilder(pos.toShortString()).append(": ").append(state);
		if (!state.isAir()) {
			text.append(String.format(Locale.ROOT, "; hardness %.1f", state.getDestroySpeed(level, pos)));
			if (state.requiresCorrectToolForDrops()) {
				text.append(PlayerControl.hasCorrectTool(player, state) ? "; you have a suitable tool" : "; needs a better tool to drop items");
			}
		}
		text.append(String.format(Locale.ROOT, "; %.1f blocks from your eyes (reach %.1f)", PlayerControl.eyeDistance(player, pos), PlayerControl.reach(player)));
		return text.toString();
	}

	public static String describeEntities(ClientLevel level, LocalPlayer player, double radius, Predicate<Entity> filter, int limit) {
		AABB area = player.getBoundingBox().inflate(radius);
		List<Entity> found = level.getEntities(player, area, entity -> entity.isAlive() && filter.test(entity));
		found.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(player)));
		if (found.isEmpty()) {
			return "No matching entities within " + (int) radius + " blocks.";
		}
		List<String> parts = new ArrayList<>();
		for (Entity entity : found.subList(0, Math.min(limit, found.size()))) {
			parts.add(describeEntity(entity, player));
		}
		return String.join("\n", parts);
	}
}
