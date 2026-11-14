package moe.tenshii.mineagent.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import moe.tenshii.mineagent.agent.llm.ToolSpec;
import moe.tenshii.mineagent.game.GameThread;
import moe.tenshii.mineagent.game.InventoryOps;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.Recipes;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.WorldInfo;
import moe.tenshii.mineagent.game.task.AttackTask;
import moe.tenshii.mineagent.game.task.CollectTask;
import moe.tenshii.mineagent.game.task.ContainerTask;
import moe.tenshii.mineagent.game.task.CraftTask;
import moe.tenshii.mineagent.game.task.EatTask;
import moe.tenshii.mineagent.game.task.GatherTask;
import moe.tenshii.mineagent.game.task.MineTask;
import moe.tenshii.mineagent.game.task.PlaceTask;
import moe.tenshii.mineagent.game.task.SmeltTask;
import moe.tenshii.mineagent.game.task.UseBlockTask;
import moe.tenshii.mineagent.game.task.WaitTask;
import moe.tenshii.mineagent.game.task.WalkTask;
import moe.tenshii.mineagent.path.Goal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

/**
 * The tools offered to the model and their implementations.
 */
final class Tools {
	private static final int MAX_SEARCH_RADIUS = 64;

	interface Context {
		String runTask(Task task) throws TaskFailure, InterruptedException;

		<T> T onGame(Supplier<T> action) throws TaskFailure, InterruptedException;
	}

	@FunctionalInterface
	private interface Handler {
		String run(ToolArgs args, Context context) throws TaskFailure, InterruptedException;
	}

	private record Definition(ToolSpec spec, Handler handler) {
	}

	private final Map<String, Definition> definitions = new LinkedHashMap<>();

	Tools() {
		register(new Schema("observe",
				"Get the player's current state: position, health, hunger, time of day, held item, armor, full inventory, "
					+ "nearby entities, and every block type within 8 blocks with its nearest position. Call this first and whenever unsure."),
			(args, ctx) -> ctx.onGame(() -> WorldInfo.observe(Minecraft.getInstance())));

		register(new Schema("find_blocks",
				"Search loaded chunks for blocks by id (minecraft:iron_ore) or tag (#minecraft:logs). Returns positions nearest first, "
					+ "with distance and whether each block touches air (exposed blocks are cheaper to reach).")
				.str("block", "Block id or #tag", true)
				.integer("radius", "Search radius in blocks, default 48, max " + MAX_SEARCH_RADIUS, false)
				.integer("limit", "Maximum results, default 10, max 50", false),
			this::findBlocks);

		register(new Schema("inspect_block", "Describe the block at a position: full block state, hardness, tool requirement and distance.")
				.position(),
			(args, ctx) -> {
				BlockPos pos = args.pos();
				return ctx.onGame(() -> WorldInfo.inspectBlock(Minecraft.getInstance().level, Minecraft.getInstance().player, pos));
			});

		register(new Schema("find_entities", "List living entities, players and dropped items near the player, nearest first, with their numeric ids.")
				.str("type", "Optional entity type id such as minecraft:cow; omit to list everything", false)
				.integer("radius", "Search radius in blocks, default 32, max 64", false),
			this::findEntities);

		register(new Schema("recipes",
				"List the unlocked crafting recipes that make an item, their ingredients, whether a crafting table is needed, "
					+ "and whether the current inventory can craft them.")
				.str("item", "Item id, e.g. minecraft:stone_pickaxe", true),
			this::recipes);

		register(new Schema("walk_to",
				"Pathfind to a position and walk there. Breaks blocks in the way and pillars up with dirt/cobblestone/planks when needed. "
					+ "Long trips are fine; the path is re-planned as chunks load.")
				.position()
				.integer("range", "How close the feet must get, in blocks; default 0 (exact block), max 32", false)
				.bool("allow_breaking", "Whether the path may break blocks; default true. Set false near builds you must not damage.", false),
			this::walkTo);

		register(new Schema("mine_block",
				"Walk into reach of one block, break it with the best tool carried, and pick up the drops. "
					+ "Fails if the block needs a better tool to drop anything unless force is true.")
				.position()
				.bool("collect_drops", "Pick up the drops afterwards; default true", false)
				.bool("force", "Break the block even if it will drop nothing; default false", false),
			(args, ctx) -> ctx.runTask(new MineTask(args.pos(), args.optBool("collect_drops", true), args.optBool("force", false))));

		register(new Schema("gather",
				"Find and mine several nearby blocks of one kind (nearest and exposed first), picking up drops. "
					+ "Use this instead of many mine_block calls, e.g. to get 5 logs or 12 cobblestone.")
				.str("block", "Block id or #tag, e.g. #minecraft:logs or minecraft:stone", true)
				.integer("count", "How many blocks to break, 1-64", true)
				.integer("radius", "Search radius, default 32, max " + MAX_SEARCH_RADIUS, false),
			(args, ctx) -> ctx.runTask(new GatherTask(Lookup.block(args.string("block")), args.optInt("count", 1, 1, 64),
				args.optInt("radius", 32, 4, MAX_SEARCH_RADIUS))));

		register(new Schema("place_block", "Place a block item from the inventory at a position. The position must be empty and next to a solid block.")
				.str("item", "Block item id, e.g. minecraft:crafting_table", true)
				.position(),
			(args, ctx) -> ctx.runTask(new PlaceTask(Lookup.item(args.string("item")), args.pos())));

		register(new Schema("craft",
				"Craft an item through the recipe book. Small recipes use the inventory grid; 3x3 recipes walk to a crafting table "
					+ "within 24 blocks. count is how many result items you want (recipes that make several per craft may overshoot).")
				.str("item", "Item id, e.g. minecraft:oak_planks", true)
				.integer("count", "How many items to end up with, 1-256; default 1", false),
			(args, ctx) -> ctx.runTask(new CraftTask(Lookup.exactItem(args.string("item")), args.optInt("count", 1, 1, 256))));

		register(new Schema("smelt",
				"Smelt or cook items in a furnace, smoker or blast furnace: loads exactly count items plus enough fuel, waits, and collects "
					+ "the output. Uses the nearest furnace within 24 blocks unless x, y and z are given. This blocks until done (10s per item in a furnace).")
				.str("item", "Input item id or #tag, e.g. minecraft:raw_iron", true)
				.integer("count", "How many items to smelt, 1-64", true)
				.str("fuel", "Optional fuel item id; by default the longest-burning fuel carried", false)
				.integer("x", "Optional furnace x", false)
				.integer("y", "Optional furnace y", false)
				.integer("z", "Optional furnace z", false),
			this::smelt);

		register(new Schema("container",
				"Open a chest, barrel, shulker box or other container block and list it, or move whole stacks of an item in or out.")
				.position()
				.enumeration("action", "list, take, or put", List.of("list", "take", "put"), true)
				.str("item", "Item id or #tag to move (required for take/put)", false)
				.integer("count", "Stop after at least this many items moved; default 64", false),
			this::container);

		register(new Schema("use_block",
				"Right-click a block with the held item, aiming at it with a clear line of sight: open or close doors, press buttons, "
					+ "pull levers, sleep in beds, and use items on blocks, such as filling a bucket from a water or lava source "
					+ "(target the fluid block), placing water, bone meal, hoes or flint and steel. Hold the item first with equip. "
					+ "The result lists the inventory change, so check it to see whether the use worked.")
				.position(),
			(args, ctx) -> ctx.runTask(new UseBlockTask(args.pos(), null)));

		register(new Schema("inventory",
				"See and rearrange the inventory slot by slot. list: every slot's contents. move: put an item (by id/tag, or by its "
					+ "current slot with from) into a slot; whatever was there swaps places. tidy: merge partial stacks and put weapon, "
					+ "pickaxe, axe, shovel, food, torches, building blocks and water bucket in hotbar 1-8. Slots are named hotbar 1-9, "
					+ "inv 1-27 and offhand (e.g. move a shield or torches to offhand).")
				.enumeration("action", "list, move or tidy", List.of("list", "move", "tidy"), true)
				.str("item", "For move: item id or #tag to move", false)
				.str("from", "For move: the slot to move from, instead of item", false)
				.str("to", "For move: destination slot, e.g. hotbar 1, inv 5, offhand", false),
			this::inventory);

		register(new Schema("equip",
				"Hold an item in the main hand. Armor, elytra and shields are worn/equipped instead of just held.")
				.str("item", "Item id or #tag", true),
			this::equip);

		register(new Schema("use_item", "Right-click the air with the held item (throw, drink, place-free use).")
				, this::useItem);

		register(new Schema("eat", "Eat food to restore hunger. Picks the most nourishing safe food unless an item is given.")
				.str("item", "Optional food item id", false),
			(args, ctx) -> ctx.runTask(new EatTask(args.has("item") ? Lookup.item(args.string("item")) : null)));

		register(new Schema("attack",
				"Chase and melee-attack living entities with the best sword or axe carried until count of them are dead. "
					+ "Target either an entity id from observe/find_entities, an entity type, or hostile=true for any hostile mob.")
				.integer("entity_id", "Numeric id of one specific entity", false)
				.str("type", "Entity type id, e.g. minecraft:zombie or minecraft:cow", false)
				.bool("hostile", "Attack the nearest hostile mobs of any type", false)
				.integer("count", "How many to kill, 1-32; default 1", false),
			this::attack);

		register(new Schema("collect_items", "Walk over dropped items near the player to pick them up.")
				.integer("radius", "Radius around the player, default 8, max 32", false)
				.str("item", "Optional item id or #tag to pick up only that", false),
			this::collectItems);

		register(new Schema("drop_item", "Throw items out of the inventory, e.g. to free space or hand them to another player.")
				.str("item", "Item id or #tag", true)
				.integer("count", "How many to drop; default all", false),
			this::dropItem);


		register(new Schema("wait", "Do nothing for a while, e.g. while crops grow or night passes.")
				.integer("seconds", "1-60", true),
			(args, ctx) -> ctx.runTask(new WaitTask(args.optInt("seconds", 1, 1, 60) * 20)));
	}

	private void register(Schema schema, Handler handler) {
		definitions.put(schema.name, new Definition(schema.build(), handler));
	}

	List<ToolSpec> specs() {
		List<ToolSpec> specs = new ArrayList<>();
		definitions.values().forEach(definition -> specs.add(definition.spec()));
		return specs;
	}

	String execute(String name, Map<String, Object> input, Context context) throws TaskFailure, InterruptedException {
		Definition definition = definitions.get(name);
		if (definition == null) {
			throw new TaskFailure("Unknown tool '" + name + "'.");
		}
		return definition.handler().run(new ToolArgs(input), context);
	}

	private String findBlocks(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		Lookup.BlockQuery query = Lookup.block(args.string("block"));
		int radius = args.optInt("radius", 48, 1, MAX_SEARCH_RADIUS);
		int limit = args.optInt("limit", 10, 1, 50);
		return ctx.onGame(() -> {
			Minecraft mc = Minecraft.getInstance();
			List<WorldInfo.Found> found = WorldInfo.findBlocks(mc.level, mc.player.blockPosition(), radius, query.matches(), limit);
			if (found.isEmpty()) {
				return "No " + query.label() + " within " + radius + " blocks of " + mc.player.blockPosition().toShortString()
					+ " (only loaded chunks are searched).";
			}
			StringBuilder text = new StringBuilder("Nearest ").append(query.label()).append(" (player at ")
				.append(mc.player.blockPosition().toShortString()).append("):");
			for (WorldInfo.Found entry : found) {
				text.append(String.format(Locale.ROOT, "%n%s %s %.1fm%s", WorldInfo.blockName(mc.level, entry.pos()), entry.pos().toShortString(),
					entry.distance(), entry.exposed() ? " exposed" : " buried"));
			}
			return text.toString();
		});
	}

	private String findEntities(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		EntityType<?> type = args.has("type") ? Lookup.entityType(args.string("type")) : null;
		int radius = args.optInt("radius", 32, 1, 64);
		return ctx.onGame(() -> {
			Minecraft mc = Minecraft.getInstance();
			return WorldInfo.describeEntities(mc.level, mc.player, radius, entity -> type == null || entity.getType() == type, 25);
		});
	}

	private String recipes(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		Item item = Lookup.exactItem(args.string("item"));
		return ctx.onGame(() -> {
			LocalPlayer player = Minecraft.getInstance().player;
			List<Recipes.CraftingRecipe> recipes = Recipes.craftingRecipesFor(player, item);
			if (recipes.isEmpty()) {
				return "No unlocked crafting recipe makes " + Lookup.name(item) + ". It may need smelting, may be found rather than crafted, "
					+ "or its recipe unlocks after you obtain one of its ingredients.";
			}
			StringBuilder text = new StringBuilder("Recipes for ").append(Lookup.name(item)).append(':');
			for (Recipes.CraftingRecipe recipe : recipes) {
				text.append("\n- makes ").append(recipe.result().getCount()).append(" from ").append(Recipes.describeIngredients(player, recipe))
					.append(recipe.needsTable() ? "; needs a crafting table" : "; fits the inventory grid")
					.append(Recipes.canCraft(player, recipe) ? "; craftable now" : "; missing ingredients");
			}
			return text.toString();
		});
	}

	private String walkTo(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		BlockPos target = args.pos();
		int range = args.optInt("range", 0, 0, 32);
		boolean breaking = args.optBool("allow_breaking", true);
		double distance = ctx.onGame(() -> Math.sqrt(Minecraft.getInstance().player.blockPosition().distSqr(target)));
		int timeout = (int) Math.min(20 * 900, 20 * (45 + distance * 2.0));
		return ctx.runTask(new WalkTask(Goal.near(target, range), target.toShortString(), breaking, true, timeout));
	}

	private String smelt(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		Lookup.ItemQuery input = Lookup.item(args.string("item"));
		Lookup.ItemQuery fuel = args.has("fuel") ? Lookup.item(args.string("fuel")) : null;
		int count = args.optInt("count", 1, 1, 64);
		BlockPos pos = args.has("x") || args.has("y") || args.has("z") ? args.pos() : null;
		return ctx.runTask(new SmeltTask(input, fuel, count, pos));
	}

	private String container(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		ContainerTask.Action action = switch (args.string("action").toLowerCase(Locale.ROOT)) {
			case "list" -> ContainerTask.Action.LIST;
			case "take" -> ContainerTask.Action.TAKE;
			case "put" -> ContainerTask.Action.PUT;
			default -> throw new TaskFailure("action must be list, take or put.");
		};
		Lookup.ItemQuery item = null;
		if (action != ContainerTask.Action.LIST) {
			item = Lookup.item(args.string("item"));
		}
		return ctx.runTask(new ContainerTask(args.pos(), action, item, args.optInt("count", 64, 1, 2304)));
	}

	private String inventory(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		String action = args.string("action").toLowerCase(Locale.ROOT);
		switch (action) {
			case "list" -> {
				return ctx.onGame(() -> InventoryOps.list(Minecraft.getInstance().player));
			}
			case "tidy" -> {
				return ctx.onGame(() -> InventoryOps.tidy(Minecraft.getInstance()));
			}
			case "move" -> {
				InventoryOps.SlotRef to = InventoryOps.parseSlot(args.string("to"));
				InventoryOps.SlotRef from = args.has("from") ? InventoryOps.parseSlot(args.string("from")) : null;
				Lookup.ItemQuery item = from == null ? Lookup.item(args.string("item")) : null;
				return ctx.onGame(() -> {
					Minecraft mc = Minecraft.getInstance();
					LocalPlayer player = mc.player;
					InventoryOps.SlotRef source = from != null ? from : InventoryOps.find(player, item.matches());
					if (source == null) {
						throw new GameThread.GameStateException("No " + item.label() + " in the inventory.");
					}
					ItemStack moving = source.stack(player).copy();
					ItemStack displaced = to.stack(player).copy();
					if (moving.isEmpty()) {
						throw new GameThread.GameStateException(source.label() + " is empty.");
					}
					InventoryOps.swap(mc, source, to);
					return "Moved " + WorldInfo.stackName(moving) + " from " + source.label() + " to " + to.label()
						+ (displaced.isEmpty() ? "." : "; " + WorldInfo.stackName(displaced) + " went to " + source.label() + ".");
				});
			}
			default -> throw new TaskFailure("action must be list, move or tidy.");
		}
	}

	private String equip(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		Lookup.ItemQuery query = Lookup.item(args.string("item"));
		return ctx.onGame(() -> {
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer player = mc.player;
			if (!PlayerControl.holdItem(mc, query.matches())) {
				throw new GameThread.GameStateException("No " + query.label() + " in the inventory.");
			}
			ItemStack held = player.getMainHandItem();
			Equippable equippable = held.get(DataComponents.EQUIPPABLE);
			if (equippable != null && equippable.slot() != EquipmentSlot.MAINHAND) {
				String name = Lookup.name(held.getItem());
				mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
				return "Equipped " + name + " in the " + equippable.slot().getName() + " slot.";
			}
			return "Now holding " + WorldInfo.stackName(held) + ".";
		});
	}

	private String useItem(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		return ctx.onGame(() -> {
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer player = mc.player;
			String held = WorldInfo.stackName(player.getMainHandItem());
			InteractionResult result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
			if (result instanceof InteractionResult.Success success) {
				if (success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
					PlayerControl.swingUse(player);
				}
				return "Used " + held + ".";
			}
			return "Using " + held + " had no effect.";
		});
	}

	private String attack(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		int count = args.optInt("count", 1, 1, 32);
		Predicate<Entity> filter;
		String label;
		if (args.has("entity_id")) {
			int id = args.integer("entity_id");
			filter = entity -> entity.getId() == id;
			label = "entity #" + id;
			count = 1;
		} else if (args.has("type")) {
			EntityType<?> type = Lookup.entityType(args.string("type"));
			filter = entity -> entity.getType() == type;
			label = Lookup.name(type);
		} else if (args.optBool("hostile", false)) {
			filter = entity -> entity instanceof Enemy;
			label = "hostile mobs";
		} else {
			throw new TaskFailure("Give entity_id, type, or hostile=true.");
		}
		return ctx.runTask(new AttackTask(filter, label, count));
	}

	private String collectItems(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		int radius = args.optInt("radius", 8, 1, 32);
		Lookup.ItemQuery item = args.has("item") ? Lookup.item(args.string("item")) : null;
		BlockPos center = ctx.onGame(() -> Minecraft.getInstance().player.blockPosition());
		return ctx.runTask(new CollectTask(center, radius, item == null ? null : item.matches(), 20 * 60));
	}

	private String dropItem(ToolArgs args, Context ctx) throws TaskFailure, InterruptedException {
		Lookup.ItemQuery query = Lookup.item(args.string("item"));
		int wanted = args.optInt("count", Integer.MAX_VALUE, 1, Integer.MAX_VALUE);
		return ctx.onGame(() -> {
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer player = mc.player;
			PlayerControl.ensureNoContainerOpen(mc);
			int dropped = 0;
			for (int slot = 0; slot < Inventory.INVENTORY_SIZE && dropped < wanted; slot++) {
				ItemStack stack = player.getInventory().getItem(slot);
				if (stack.isEmpty() || !query.matches().test(stack)) {
					continue;
				}
				int menuSlot = PlayerControl.inventoryMenuSlot(slot);
				if (wanted - dropped >= stack.getCount()) {
					dropped += stack.getCount();
					PlayerControl.click(mc, player.inventoryMenu, menuSlot, 1, ContainerInput.THROW);
				} else {
					while (dropped < wanted && !player.getInventory().getItem(slot).isEmpty()) {
						PlayerControl.click(mc, player.inventoryMenu, menuSlot, 0, ContainerInput.THROW);
						dropped++;
					}
				}
			}
			if (dropped == 0) {
				throw new GameThread.GameStateException("No " + query.label() + " in the inventory.");
			}
			return "Dropped " + dropped + " " + query.label() + ".";
		});
	}

	/**
	 * Small builder for a tool's JSON schema.
	 */
	private static final class Schema {
		private final String name;
		private final String description;
		private final Map<String, Object> properties = new LinkedHashMap<>();
		private final List<String> required = new ArrayList<>();

		Schema(String name, String description) {
			this.name = name;
			this.description = description;
		}

		Schema str(String key, String text, boolean isRequired) {
			return property(key, Map.of("type", "string", "description", text), isRequired);
		}

		Schema integer(String key, String text, boolean isRequired) {
			return property(key, Map.of("type", "integer", "description", text), isRequired);
		}

		Schema bool(String key, String text, boolean isRequired) {
			return property(key, Map.of("type", "boolean", "description", text), isRequired);
		}

		Schema enumeration(String key, String text, List<String> values, boolean isRequired) {
			return property(key, Map.of("type", "string", "description", text, "enum", values), isRequired);
		}

		Schema position() {
			return integer("x", "Block x", true).integer("y", "Block y", true).integer("z", "Block z", true);
		}

		private Schema property(String key, Map<String, Object> schema, boolean isRequired) {
			properties.put(key, schema);
			if (isRequired) {
				required.add(key);
			}
			return this;
		}

		ToolSpec build() {
			return new ToolSpec(name, description, Collections.unmodifiableMap(new LinkedHashMap<>(properties)), List.copyOf(required));
		}
	}
}
