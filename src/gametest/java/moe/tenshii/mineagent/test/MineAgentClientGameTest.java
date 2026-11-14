package moe.tenshii.mineagent.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import moe.tenshii.mineagent.agent.AgentController;
import moe.tenshii.mineagent.agent.llm.Provider;
import moe.tenshii.mineagent.config.AgentConfig;
import moe.tenshii.mineagent.game.AgentInput;
import moe.tenshii.mineagent.game.DamageTracker;
import moe.tenshii.mineagent.game.Lookup;
import moe.tenshii.mineagent.game.PlayerControl;
import moe.tenshii.mineagent.game.Task;
import moe.tenshii.mineagent.game.TaskFailure;
import moe.tenshii.mineagent.game.TaskRunner;
import moe.tenshii.mineagent.game.WorldInfo;
import moe.tenshii.mineagent.game.task.AttackTask;
import moe.tenshii.mineagent.game.task.ContainerTask;
import moe.tenshii.mineagent.game.task.CraftTask;
import moe.tenshii.mineagent.game.task.EatTask;
import moe.tenshii.mineagent.game.task.GatherTask;
import moe.tenshii.mineagent.game.task.MineTask;
import moe.tenshii.mineagent.game.task.PlaceTask;
import moe.tenshii.mineagent.game.task.SmeltTask;
import moe.tenshii.mineagent.game.task.UseBlockTask;
import moe.tenshii.mineagent.game.task.WalkTask;
import moe.tenshii.mineagent.path.Goal;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Drives the real tasks in a singleplayer world, then runs the whole agent loop against a scripted
 * OpenAI-compatible server so no API key or network access is needed.
 */
public final class MineAgentClientGameTest implements FabricClientGameTest {
	private static final int TASK_TIMEOUT_TICKS = 20 * 120;

	@Override
	public void runTest(ClientGameTestContext context) {
		try {
			scenario(context);
		} catch (TaskFailure badLookup) {
			throw new AssertionError(badLookup.getMessage());
		}
	}

	private static void scenario(ClientGameTestContext context) throws TaskFailure {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitFor(mc -> mc.player != null && mc.level != null && mc.player.onGround(), 20 * 30);
			world.getServer().runCommand("gamemode survival @a");
			world.getServer().runCommand("difficulty peaceful");
			world.getServer().runCommand("time set day");
			world.getServer().runCommand("clear @a");
			world.getServer().runCommand("recipe give @a *");
			context.waitTicks(10);

			BlockPos start = context.computeOnClient(mc -> mc.player.blockPosition());
			int x = start.getX();
			int y = start.getY();
			int z = start.getZ();
			log("Player starts at " + start.toShortString() + " standing on "
				+ context.computeOnClient(mc -> Lookup.name(mc.level.getBlockState(start.below()).getBlock())));

			// Clear a working area and give it a solid floor so the layout does not depend on the generated terrain.
			world.getServer().runCommand(String.format("fill %d %d %d %d %d %d air", x - 12, y, z - 12, x + 12, y + 6, z + 12));
			world.getServer().runCommand(String.format("fill %d %d %d %d %d %d stone", x - 12, y - 1, z - 12, x + 12, y - 1, z + 12));
			world.getServer().runCommand(String.format("fill %d %d %d %d %d %d stone", x + 3, y, z - 3, x + 3, y + 2, z + 3));
			context.waitTicks(5);

			BlockPos walkTarget = new BlockPos(x + 6, y, z);
			String walk = run(context, new WalkTask(Goal.near(walkTarget, 0), walkTarget.toShortString(), true, true, TASK_TIMEOUT_TICKS));
			check(context, mc -> mc.player.blockPosition().equals(walkTarget), "walk_to should end on " + walkTarget.toShortString() + ": " + walk);

			BlockPos log = new BlockPos(x + 8, y, z);
			world.getServer().runCommand(String.format("setblock %d %d %d oak_log", log.getX(), log.getY(), log.getZ()));
			context.waitTicks(2);
			String mine = run(context, new MineTask(log, true, false));
			check(context, mc -> count(mc, Items.OAK_LOG) == 1, "mine_block should pick up the log: " + mine);

			world.getServer().runCommand("give @a oak_log 3");
			context.waitTicks(2);
			String planks = run(context, new CraftTask(Items.OAK_PLANKS, 16));
			check(context, mc -> count(mc, Items.OAK_PLANKS) == 16, "craft planks: " + planks);
			String sticks = run(context, new CraftTask(Items.STICK, 4));
			check(context, mc -> count(mc, Items.STICK) == 4, "craft sticks: " + sticks);
			String table = run(context, new CraftTask(Items.CRAFTING_TABLE, 1));
			check(context, mc -> count(mc, Items.CRAFTING_TABLE) == 1, "craft crafting table: " + table);

			BlockPos tablePos = new BlockPos(x + 6, y, z + 2);
			String placeTable = run(context, new PlaceTask(Lookup.item("minecraft:crafting_table"), tablePos));
			check(context, mc -> mc.level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE), "place crafting table: " + placeTable);

			String pickaxe = run(context, new CraftTask(Items.WOODEN_PICKAXE, 1));
			check(context, mc -> count(mc, Items.WOODEN_PICKAXE) == 1 && mc.player.containerMenu == mc.player.inventoryMenu,
				"craft wooden pickaxe at the table: " + pickaxe);

			for (int i = 0; i < 3; i++) {
				world.getServer().runCommand(String.format("setblock %d %d %d stone", x + 9, y + i % 2, z - 6 + i * 2));
			}
			context.waitTicks(2);
			String gather = run(context, new GatherTask(Lookup.block("minecraft:stone"), 3, 16));
			check(context, mc -> count(mc, Items.COBBLESTONE) >= 3, "gather stone: " + gather);

			BlockPos water = new BlockPos(x - 4, y - 1, z + 4);
			world.getServer().runCommand(String.format("setblock %d %d %d water", water.getX(), water.getY(), water.getZ()));
			world.getServer().runCommand("give @a bucket");
			context.waitTicks(5);
			context.runOnClient(mc -> PlayerControl.holdItem(mc, stack -> stack.is(Items.BUCKET)));
			String fill = run(context, new UseBlockTask(water, null));
			check(context, mc -> count(mc, Items.WATER_BUCKET) == 1 && count(mc, Items.BUCKET) == 0
				&& mc.level.getBlockState(water).getFluidState().isEmpty(), "fill a bucket from a water source: " + fill);
			BlockPos floor = new BlockPos(x - 4, y - 1, z + 2);
			context.runOnClient(mc -> PlayerControl.holdItem(mc, stack -> stack.is(Items.WATER_BUCKET)));
			String pour = run(context, new UseBlockTask(floor, null));
			check(context, mc -> count(mc, Items.BUCKET) == 1 && !mc.level.getFluidState(floor.above()).isEmpty(), "pour the water back out: " + pour);
			world.getServer().runCommand(String.format("setblock %d %d %d air", floor.getX(), floor.getY() + 1, floor.getZ()));
			world.getServer().runCommand(String.format("setblock %d %d %d stone", water.getX(), water.getY(), water.getZ()));

			testDrowningSafety(context, world, x, y, z);

			world.getServer().runCommand("give @a furnace");
			world.getServer().runCommand("give @a raw_iron 2");
			world.getServer().runCommand("give @a coal 1");
			world.getServer().runCommand("give @a chest");
			context.waitTicks(2);
			BlockPos furnacePos = new BlockPos(x + 4, y, z + 6);
			String placeFurnace = run(context, new PlaceTask(Lookup.item("minecraft:furnace"), furnacePos));
			check(context, mc -> mc.level.getBlockState(furnacePos).is(Blocks.FURNACE), "place furnace: " + placeFurnace);
			String smelt = run(context, new SmeltTask(Lookup.item("minecraft:raw_iron"), null, 2, null));
			check(context, mc -> count(mc, Items.IRON_INGOT) == 2, "smelt raw iron: " + smelt);

			BlockPos chestPos = new BlockPos(x + 6, y, z + 6);
			String placeChest = run(context, new PlaceTask(Lookup.item("minecraft:chest"), chestPos));
			check(context, mc -> mc.level.getBlockState(chestPos).is(Blocks.CHEST), "place chest: " + placeChest);
			int cobble = context.computeOnClient(mc -> count(mc, Items.COBBLESTONE));
			String put = run(context, new ContainerTask(chestPos, ContainerTask.Action.PUT, Lookup.item("minecraft:cobblestone"), 64));
			check(context, mc -> count(mc, Items.COBBLESTONE) == 0, "put cobblestone in chest: " + put);
			String take = run(context, new ContainerTask(chestPos, ContainerTask.Action.TAKE, Lookup.item("minecraft:cobblestone"), 64));
			check(context, mc -> count(mc, Items.COBBLESTONE) == cobble, "take cobblestone back: " + take);

			world.getServer().runCommand(String.format("summon pig %d %d %d", x - 6, y, z - 4));
			context.waitTicks(5);
			String attack = run(context, new AttackTask(entity -> entity instanceof Pig, "pig", 1));
			check(context, mc -> mc.level.getEntitiesOfClass(Pig.class,
				mc.player.getBoundingBox().inflate(48), pig -> pig.isAlive()).isEmpty(), "kill the pig: " + attack);

			world.getServer().runCommand("difficulty easy");
			world.getServer().runCommand("effect give @a hunger 30 255");
			context.waitFor(mc -> mc.player.getFoodData().getFoodLevel() <= 14, 20 * 60);
			world.getServer().runCommand("effect clear @a");
			world.getServer().runCommand("difficulty peaceful");
			world.getServer().runCommand("give @a bread 2");
			context.waitTicks(2);
			int foodBefore = context.computeOnClient(mc -> mc.player.getFoodData().getFoodLevel());
			String eat = run(context, new EatTask(null));
			check(context, mc -> mc.player.getFoodData().getFoodLevel() > foodBefore && count(mc, Items.BREAD) == 1, "eat bread: " + eat);

			world.getServer().runCommand("effect give @a poison 20 1");
			world.getServer().runCommand("effect give @a speed 60 0");
			context.waitTicks(5);
			String observed = context.computeOnClient(WorldInfo::observe);
			String alerts = context.computeOnClient(WorldInfo::alerts);
			log("Effects line: " + observed.lines().filter(line -> line.startsWith("Effects:")).findFirst().orElse("(missing)") + " | " + alerts);
			assertTrue(observed.contains("poison II") && observed.contains("(harmful)") && observed.contains("speed I"),
				"observe should list effects: " + observed);
			assertTrue(alerts != null && alerts.contains("poison"), "alerts should flag poison: " + alerts);
			world.getServer().runCommand("effect clear @a");
			context.waitTicks(5);
			assertTrue(context.computeOnClient(WorldInfo::alerts) == null, "no alerts once effects are cleared");

			runAgentLoopAgainstMockServer(context, world);
			runAnthropicLoopAgainstMockServer(context);
			runDamageReport(context, world);
			runDeathDuringGoal(context, world);
		}
	}

	private static void testDrowningSafety(ClientGameTestContext context, TestSingleplayerContext world, int x, int y, int z) {
		// The test world is superflat with bedrock 4 blocks down, so the pool is 3 deep: enough to be fully submerged.
		world.getServer().runCommand(String.format("fill %d %d %d %d %d %d water", x - 10, y - 3, z + 8, x - 7, y - 1, z + 11));
		String bottom = String.format("tp @a %d.5 %d %d.5", x - 9, y - 3, z + 9);
		context.waitTicks(5);

		context.runOnClient(mc -> PlayerControl.takeControl(mc));
		world.getServer().runCommand(bottom);
		context.waitTicks(20 * 25);
		String idleState = context.computeOnClient(mc -> String.format("health %.1f, air %d, y %.2f, underwater %s, input %s",
			mc.player.getHealth(), mc.player.getAirSupply(), mc.player.getY(), mc.player.isUnderWater(), mc.player.input.getClass().getSimpleName()));
		log("Idle in water: " + idleState);
		check(context, mc -> mc.player.getHealth() >= mc.player.getMaxHealth() && mc.player.getAirSupply() >= mc.player.getMaxAirSupply() / 2,
			"idle in water: should float up and take no damage (" + idleState + ")");

		world.getServer().runCommand(bottom);
		context.waitTicks(5);
		context.runOnClient(DamageTracker::begin);
		String wait = run(context, new Task() {
			private int ticks;

			// Holds the controls still like a busy action (mining, using a block) would, so only the reflex can surface.
			@Override
			public String tick(Minecraft mc) {
				PlayerControl.input().clear();
				return ++ticks >= 20 * 20 ? "Held still for 20 seconds." : null;
			}

			@Override
			public int timeoutTicks() {
				return 20 * 40;
			}

			@Override
			public String describe() {
				return "holding still underwater";
			}
		});
		String report = context.computeOnClient(DamageTracker::end);
		log("Underwater wait: " + wait + " | " + report);
		check(context, mc -> mc.player.getHealth() >= mc.player.getMaxHealth(), "the reflex should surface during a wait: " + report);
		assertTrue(report != null && report.contains("to swim up for air"), "the result should mention surfacing: " + report);

		world.getServer().runCommand(String.format("tp @a %d.5 %d %d.5", x - 5, y, z + 9));
		context.waitTicks(10);
		BlockPos across = new BlockPos(x - 12, y, z + 9);
		context.runOnClient(DamageTracker::begin);
		String cross = run(context, new WalkTask(Goal.near(across, 0), across.toShortString(), true, true, 20 * 60));
		String crossReport = context.computeOnClient(DamageTracker::end);
		log("Pool crossing: " + cross + " | " + crossReport);
		check(context, mc -> mc.player.blockPosition().equals(across) && mc.player.getHealth() >= mc.player.getMaxHealth(),
			"cross the pool without drowning: " + cross + " | " + crossReport);

		context.runOnClient(mc -> PlayerControl.releaseControl(mc));
		world.getServer().runCommand(String.format("fill %d %d %d %d %d %d stone", x - 10, y - 3, z + 8, x - 7, y - 1, z + 11));
		context.waitTicks(5);
	}

	private static void runAgentLoopAgainstMockServer(ClientGameTestContext context, TestSingleplayerContext world) {
		List<JsonObject> requests = new CopyOnWriteArrayList<>();
		AtomicInteger turn = new AtomicInteger();
		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new AssertionError("could not start mock server", e);
		}
		server.createContext("/v1/chat/completions", exchange -> {
			try (InputStream in = exchange.getRequestBody()) {
				requests.add(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
			}
			String body = switch (turn.incrementAndGet()) {
				case 1 -> toolCallResponse("call_1", "observe", "{}");
				case 2 -> toolCallResponse("call_2", "craft", "{\"item\":\"minecraft:oak_planks\",\"count\":4}");
				default -> textResponse("Crafted the planks.");
			};
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
		try {
			world.getServer().runCommand("give @a oak_log 1");
			context.waitTicks(5);
			int planksBefore = context.computeOnClient(mc -> count(mc, Items.OAK_PLANKS));

			AgentConfig config = AgentConfig.get();
			config.provider = Provider.CUSTOM.id();
			config.baseUrls.put(Provider.CUSTOM.id(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
			config.models.put(Provider.CUSTOM.id(), "mock-model");
			String error = context.computeOnClient(mc -> AgentController.get().start("make some planks"));
			if (error != null) {
				throw new AssertionError("agent did not start: " + error);
			}
			context.waitFor(mc -> !AgentController.get().isRunning(), 20 * 60);

			check(context, mc -> count(mc, Items.OAK_PLANKS) == planksBefore + 4, "agent loop should craft 4 planks");
			check(context, mc -> !(mc.player.input instanceof AgentInput) && !PlayerControl.inControl(),
				"control should return to the keyboard after the goal");
			if (requests.size() != 3) {
				throw new AssertionError("expected 3 model requests, got " + requests.size());
			}
			JsonArray second = requests.get(1).getAsJsonArray("messages");
			JsonObject observeResult = second.get(second.size() - 1).getAsJsonObject();
			assertTrue("tool".equals(observeResult.get("role").getAsString()) && observeResult.get("content").getAsString().contains("Position:"),
				"observe result should be sent back as a tool message: " + observeResult);
			JsonArray third = requests.get(2).getAsJsonArray("messages");
			JsonObject craftResult = third.get(third.size() - 1).getAsJsonObject();
			assertTrue(craftResult.get("content").getAsString().startsWith("Crafted 4 oak_planks"),
				"craft result should report success: " + craftResult);
			assertTrue(requests.getFirst().getAsJsonArray("tools").size() >= 20, "all tools should be offered");
			log("Agent loop OK; craft result: " + craftResult.get("content").getAsString());
		} finally {
			server.stop(0);
		}
	}

	private static void runAnthropicLoopAgainstMockServer(ClientGameTestContext context) {
		List<JsonObject> requests = new CopyOnWriteArrayList<>();
		List<String> betaHeaders = new CopyOnWriteArrayList<>();
		List<String> authHeaders = new CopyOnWriteArrayList<>();
		AtomicInteger turn = new AtomicInteger();
		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new AssertionError("could not start mock server", e);
		}
		server.createContext("/v1/messages", exchange -> {
			try (InputStream in = exchange.getRequestBody()) {
				requests.add(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
			}
			betaHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("anthropic-beta")));
			authHeaders.add(exchange.getRequestHeaders().getFirst("x-api-key") + " " + exchange.getRequestHeaders().getFirst("anthropic-version"));
			String body = turn.incrementAndGet() == 1
				? "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\",\"content\":["
					+ "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig-abc\"},"
					+ "{\"type\":\"text\",\"text\":\"Checking the inventory.\"},"
					+ "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"recipes\",\"input\":{\"item\":\"minecraft:stick\"}}],"
					+ "\"stop_reason\":\"tool_use\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":50,\"output_tokens\":20}}"
				: "{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\",\"content\":["
					+ "{\"type\":\"text\",\"text\":\"Sticks are craftable.\"}],"
					+ "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":80,\"output_tokens\":5}}";
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
		try {
			AgentConfig config = AgentConfig.get();
			config.provider = Provider.ANTHROPIC.id();
			config.apiKeys.put(Provider.ANTHROPIC.id(), "test-key");
			config.baseUrls.put(Provider.ANTHROPIC.id(), "http://127.0.0.1:" + server.getAddress().getPort());
			config.models.remove(Provider.ANTHROPIC.id());
			String error = context.computeOnClient(mc -> AgentController.get().start("can I make sticks?"));
			if (error != null) {
				throw new AssertionError("agent did not start: " + error);
			}
			context.waitFor(mc -> !AgentController.get().isRunning(), 20 * 60);
			if (requests.size() != 2) {
				throw new AssertionError("expected 2 Messages API requests, got " + requests.size());
			}
			JsonObject first = requests.getFirst();
			assertTrue("claude-opus-5-5".equals(first.get("model").getAsString()), "default model: " + first.get("model"));
			assertTrue("default".equals(first.get("fallbacks").getAsString()), "refusal fallback should be requested");
			assertTrue(betaHeaders.getFirst().contains("server-side-fallback-2026-07-01"), "fallback beta header: " + betaHeaders);
			assertTrue("test-key 2026-10-21".equals(authHeaders.getFirst()), "key and API version headers: " + authHeaders);
			assertTrue("medium".equals(first.getAsJsonObject("output_config").get("effort").getAsString()), "effort should be sent");
			assertTrue(first.has("cache_control"), "automatic prompt caching should be on");
			assertTrue(first.getAsJsonArray("tools").size() >= 20, "all tools should be offered");
			JsonArray messages = requests.get(1).getAsJsonArray("messages");
			JsonObject assistant = messages.get(1).getAsJsonObject();
			assertTrue(assistant.toString().contains("sig-abc"), "thinking block must be replayed unchanged: " + assistant);
			JsonObject toolTurn = messages.get(2).getAsJsonObject();
			JsonObject result = toolTurn.getAsJsonArray("content").get(0).getAsJsonObject();
			assertTrue("tool_result".equals(result.get("type").getAsString()) && "toolu_1".equals(result.get("tool_use_id").getAsString())
				&& result.toString().contains("Recipes for stick"), "tool result should answer the call: " + result);
			log("Anthropic loop OK; tool result: " + result);
		} finally {
			server.stop(0);
		}
	}

	private static void runDamageReport(ClientGameTestContext context, TestSingleplayerContext world) {
		List<JsonObject> requests = new CopyOnWriteArrayList<>();
		AtomicInteger turn = new AtomicInteger();
		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new AssertionError("could not start mock server", e);
		}
		server.createContext("/v1/chat/completions", exchange -> {
			try (InputStream in = exchange.getRequestBody()) {
				requests.add(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
			}
			String body = turn.incrementAndGet() == 1
				? toolCallResponse("call_wait", "wait", "{\"seconds\":4}")
				: textResponse("Noted the damage.");
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
		try {
			world.getServer().runCommand("difficulty easy");
			BlockPos pos = context.computeOnClient(mc -> mc.player.blockPosition());
			world.getServer().runCommand(String.format("summon zombie %d %d %d {NoAI:1b,Tags:[\"hitter\"]}", pos.getX() + 3, pos.getY(), pos.getZ()));
			context.waitTicks(2);
			AgentConfig config = AgentConfig.get();
			config.provider = Provider.CUSTOM.id();
			config.baseUrls.put(Provider.CUSTOM.id(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
			config.models.put(Provider.CUSTOM.id(), "mock-model");
			String error = context.computeOnClient(mc -> AgentController.get().start("stand still"));
			if (error != null) {
				throw new AssertionError("agent did not start: " + error);
			}
			context.waitFor(mc -> requests.size() == 1 && "waiting".equals(AgentController.get().phase()), 20 * 30);
			world.getServer().runCommand("damage @p 4 minecraft:mob_attack by @e[tag=hitter,limit=1]");
			context.waitTicks(15);
			world.getServer().runCommand("damage @p 4 minecraft:mob_attack by @e[tag=hitter,limit=1]");
			context.waitTicks(15);
			world.getServer().runCommand("damage @p 2 minecraft:fall");
			context.waitFor(mc -> !AgentController.get().isRunning(), 20 * 30);
			world.getServer().runCommand("kill @e[tag=hitter]");
			world.getServer().runCommand("effect give @a instant_health 1 5");
			world.getServer().runCommand("difficulty peaceful");
			assertTrue(requests.size() == 2, "expected 2 model requests, got " + requests.size());
			JsonArray messages = requests.get(1).getAsJsonArray("messages");
			String report = messages.get(messages.size() - 1).getAsJsonObject().get("content").getAsString();
			log("Damage report: " + report.replace("\n", " | "));
			assertTrue(report.contains("Took ") && report.matches("(?s).*zombie #\\d+ x2.*") && report.contains("fall x1")
				&& report.contains("health now"), "the wait result should report the hits: " + report);
		} finally {
			server.stop(0);
		}
	}

	private static void runDeathDuringGoal(ClientGameTestContext context, TestSingleplayerContext world) {
		List<JsonObject> requests = new CopyOnWriteArrayList<>();
		AtomicInteger turn = new AtomicInteger();
		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new AssertionError("could not start mock server", e);
		}
		server.createContext("/v1/chat/completions", exchange -> {
			try (InputStream in = exchange.getRequestBody()) {
				requests.add(JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
			}
			String body = turn.incrementAndGet() == 1
				? toolCallResponse("call_wait", "wait", "{\"seconds\":20}")
				: textResponse("I died, stopping here.");
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
		try {
			AgentConfig config = AgentConfig.get();
			config.provider = Provider.CUSTOM.id();
			config.baseUrls.put(Provider.CUSTOM.id(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
			config.models.put(Provider.CUSTOM.id(), "mock-model");
			String error = context.computeOnClient(mc -> AgentController.get().start("wait around"));
			if (error != null) {
				throw new AssertionError("agent did not start: " + error);
			}
			context.waitFor(mc -> requests.size() == 1 && "waiting".equals(AgentController.get().phase()), 20 * 30);
			world.getServer().runCommand("kill @a");
			context.waitFor(mc -> !AgentController.get().isRunning(), 20 * 30);
			check(context, mc -> mc.player.isAlive() && !(mc.gui.screen() instanceof DeathScreen), "player should be respawned with no death screen");
			assertTrue(requests.size() == 2, "expected 2 model requests, got " + requests.size());
			JsonArray messages = requests.get(1).getAsJsonArray("messages");
			String history = messages.toString();
			assertTrue(history.contains("Error: The player died"), "the interrupted wait should report the death: " + history);
			assertTrue(history.contains("Game event: You died at"), "the model should be told about the death: " + history);
			log("Death handling OK: " + messages.get(messages.size() - 1));
		} finally {
			server.stop(0);
		}
	}

	private static String toolCallResponse(String id, String name, String arguments) {
		JsonObject function = new JsonObject();
		function.addProperty("name", name);
		function.addProperty("arguments", arguments);
		JsonObject call = new JsonObject();
		call.addProperty("id", id);
		call.addProperty("type", "function");
		call.add("function", function);
		JsonArray calls = new JsonArray();
		calls.add(call);
		JsonObject message = new JsonObject();
		message.addProperty("role", "assistant");
		message.add("content", JsonNull.INSTANCE);
		message.add("tool_calls", calls);
		return completion(message, "tool_calls");
	}

	private static String textResponse(String text) {
		JsonObject message = new JsonObject();
		message.addProperty("role", "assistant");
		message.addProperty("content", text);
		return completion(message, "stop");
	}

	private static String completion(JsonObject message, String finishReason) {
		JsonObject choice = new JsonObject();
		choice.addProperty("index", 0);
		choice.add("message", message);
		choice.addProperty("finish_reason", finishReason);
		JsonArray choices = new JsonArray();
		choices.add(choice);
		JsonObject usage = new JsonObject();
		usage.addProperty("prompt_tokens", 100);
		usage.addProperty("completion_tokens", 10);
		JsonObject root = new JsonObject();
		root.add("choices", choices);
		root.add("usage", usage);
		return root.toString();
	}

	/** Runs a task the way the agent does, from a background thread, while the test keeps the client ticking. */
	private static String run(ClientGameTestContext context, Task task) {
		CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
			try {
				return TaskRunner.run(task, () -> false);
			} catch (TaskFailure failure) {
				return "FAILED: " + failure.getMessage();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return "FAILED: interrupted";
			}
		});
		context.waitFor(mc -> result.isDone(), task.timeoutTicks() + 40);
		String outcome = result.join();
		log(task.describe() + " -> " + outcome);
		if (outcome.startsWith("FAILED")) {
			throw new AssertionError(task.describe() + " failed: " + outcome);
		}
		return outcome;
	}

	private static void check(ClientGameTestContext context, Predicate<Minecraft> condition, String message) {
		context.waitTicks(2);
		if (!context.computeOnClient(condition::test)) {
			throw new AssertionError(message);
		}
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static int count(Minecraft mc, Item item) {
		return PlayerControl.countItems(mc.player, (ItemStack stack) -> stack.is(item));
	}

	private static void log(String message) {
		System.out.println("[MineAgent test] " + message);
	}
}
