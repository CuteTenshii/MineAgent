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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import moe.tenshii.mineagent.agent.AgentController;
import moe.tenshii.mineagent.agent.llm.Provider;
import moe.tenshii.mineagent.config.AgentConfig;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.jspecify.annotations.Nullable;

/**
 * A local stand-in for a model provider's HTTP API. Each route answers with a scripted reply and records what was
 * sent; closing it stops the server and restores the agent settings the test changed.
 */
final class MockModel implements AutoCloseable {
	static final String TEST_KEY = "test-key";
	private static final int GOAL_TIMEOUT_TICKS = 20 * 60;

	/** One request the agent sent. */
	record Request(String path, String body, Map<String, String> headers) {
		JsonObject json() {
			return JsonParser.parseString(body).getAsJsonObject();
		}

		/** The messages array of a chat request. */
		JsonArray messages() {
			return json().getAsJsonArray("messages");
		}

		boolean offersTool(String name) {
			return json().getAsJsonArray("tools").toString().contains("\"name\":\"" + name + "\"");
		}
	}

	record Reply(int status, String body) {
		static Reply ok(String body) {
			return new Reply(200, body);
		}
	}

	@FunctionalInterface
	interface Handler {
		/**
		 * @param turn 1 for the first request to this route, 2 for the second, and so on
		 */
		Reply answer(Request request, int turn);
	}

	private final HttpServer server;
	private final List<Request> requests = new CopyOnWriteArrayList<>();
	private final ConfigSnapshot savedConfig = new ConfigSnapshot();

	MockModel() {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		} catch (IOException e) {
			throw new AssertionError("could not start the mock model server", e);
		}
	}

	MockModel route(String path, Handler handler) {
		AtomicInteger turns = new AtomicInteger();
		server.createContext(path, exchange -> {
			String body;
			try (InputStream in = exchange.getRequestBody()) {
				body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			Map<String, String> headers = new LinkedHashMap<>();
			exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.getFirst()));
			Request request = new Request(exchange.getRequestURI().getPath(), body, headers);
			requests.add(request);
			Reply reply = handler.answer(request, turns.incrementAndGet());
			byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(reply.status(), bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		return this;
	}

	/** Serves the same reply on every request to {@code path}. */
	MockModel route(String path, String body) {
		return route(path, (request, turn) -> Reply.ok(body));
	}

	MockModel start() {
		server.start();
		return this;
	}

	String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	/** Points the agent at this server as {@code provider}, whose API lives under {@code basePath}; null keeps the default model. */
	void use(Provider provider, String basePath, @Nullable String model) {
		AgentConfig config = AgentConfig.get();
		config.provider = provider.id();
		config.apiKeys.put(provider.id(), TEST_KEY);
		config.baseUrls.put(provider.id(), url(basePath));
		if (model == null) {
			config.models.remove(provider.id());
		} else {
			config.models.put(provider.id(), model);
		}
	}

	/** Every request so far, oldest first. */
	List<Request> requests() {
		return requests;
	}

	/** Requests sent to chat endpoints (OpenAI Chat Completions or Anthropic Messages), oldest first. */
	List<Request> chatRequests() {
		return requests.stream().filter(request -> request.path().endsWith("/chat/completions") || request.path().endsWith("/messages")).toList();
	}

	/** Starts a goal without waiting for it. */
	static void startGoal(ClientGameTestContext context, String goal) {
		String error = context.computeOnClient(mc -> AgentController.get().start(goal));
		if (error != null) {
			throw new AssertionError("agent did not start: " + error);
		}
	}

	static void awaitGoalEnd(ClientGameTestContext context) {
		context.waitFor(mc -> !AgentController.get().isRunning(), GOAL_TIMEOUT_TICKS);
	}

	static void runGoal(ClientGameTestContext context, String goal) {
		startGoal(context, goal);
		awaitGoalEnd(context);
	}

	/** Runs a short goal and returns whether its first model request offered the look tool. */
	boolean goalOffersLook(ClientGameTestContext context) {
		return goalOffersTool(context, "look");
	}

	/** Runs a short goal and reports whether its first request offered the tool {@code name}. */
	boolean goalOffersTool(ClientGameTestContext context, String name) {
		int before = chatRequests().size();
		runGoal(context, "say hi");
		List<Request> chats = chatRequests();
		GameTestKit.assertTrue(chats.size() > before, "the goal should have asked the model");
		return chats.get(before).offersTool(name);
	}

	/** An OpenAI Chat Completions reply that calls one tool. */
	static String toolCall(String id, String name, String arguments) {
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

	/** An OpenAI Chat Completions reply with only text, which ends the goal. */
	static String text(String text) {
		JsonObject message = new JsonObject();
		message.addProperty("role", "assistant");
		message.addProperty("content", text);
		return completion(message, "stop");
	}

	/** An Anthropic Messages reply with the given content blocks (as JSON) and stop reason. */
	static String anthropic(String contentBlocks, String stopReason) {
		return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\",\"content\":[" + contentBlocks
			+ "],\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":50,\"output_tokens\":20}}";
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

	@Override
	public void close() {
		server.stop(0);
		savedConfig.close();
	}
}
