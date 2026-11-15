package moe.tenshii.mineagent.agent.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Claude through the Messages API over plain HTTP.
 */
public final class AnthropicBackend implements ChatBackend {
	private static final String API_VERSION = "2026-10-21";
	// Models that accept the server-side refusal fallback; others would reject the parameter.
	private static final Set<String> FALLBACK_MODELS = Set.of(
		"claude-opus-5-5", "claude-opus-5", "claude-fable-5-2", "claude-fable-5-1", "claude-sonnet-5-5");
	private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
	private static final Gson GSON = new Gson();
	private static final String IMAGE_REMOVED = "[An older screenshot was removed from the conversation to save tokens.]";
	private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
	}.getType();

	private final JsonHttp http = new JsonHttp("Anthropic");
	private final URI endpoint;
	private final Map<String, String> headers = new LinkedHashMap<>();
	private final String model;
	private final String effort;
	private final int maxTokens;
	private final String system;
	private final JsonArray tools = new JsonArray();
	private final JsonArray messages = new JsonArray();

	public AnthropicBackend(String apiKey, String baseUrl, String model, String effort, int maxTokens, String system, List<ToolSpec> specs)
		throws ApiFailure {
		this.endpoint = JsonHttp.endpoint("Anthropic", baseUrl, "/v1/messages");
		this.model = model;
		this.effort = effort;
		this.maxTokens = maxTokens;
		this.system = system;
		headers.put("x-api-key", apiKey);
		headers.put("anthropic-version", API_VERSION);
		if (FALLBACK_MODELS.contains(model)) {
			headers.put("anthropic-beta", FALLBACK_BETA);
		}
		for (ToolSpec spec : specs) {
			JsonObject schema = new JsonObject();
			schema.addProperty("type", "object");
			schema.add("properties", GSON.toJsonTree(spec.properties()));
			schema.add("required", GSON.toJsonTree(spec.required()));
			JsonObject tool = new JsonObject();
			tool.addProperty("name", spec.name());
			tool.addProperty("description", spec.description());
			tool.add("input_schema", schema);
			tools.add(tool);
		}
	}

	@Override
	public void addUserText(String text) {
		JsonArray content = new JsonArray();
		content.add(textBlock(text));
		addMessage("user", content);
	}

	@Override
	public Reply next() throws ApiFailure, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("max_tokens", maxTokens);
		body.addProperty("system", system);
		body.add("messages", messages);
		body.add("tools", tools);
		JsonObject outputConfig = new JsonObject();
		outputConfig.addProperty("effort", effort);
		body.add("output_config", outputConfig);
		JsonObject cacheControl = new JsonObject();
		cacheControl.addProperty("type", "ephemeral");
		body.add("cache_control", cacheControl);
		if (FALLBACK_MODELS.contains(model)) {
			body.addProperty("fallbacks", "default");
		}
		JsonObject response = http.post(endpoint, headers, GSON.toJson(body));

		JsonArray content = response.has("content") && response.get("content").isJsonArray() ? response.getAsJsonArray("content") : new JsonArray();
		// Content goes back into history exactly as received so thinking blocks and their signatures replay unchanged.
		// An empty assistant turn would be rejected on the next request, so it is left out.
		if (!content.isEmpty()) {
			addMessage("assistant", content);
		}

		List<String> texts = new ArrayList<>();
		List<ToolCall> calls = new ArrayList<>();
		for (JsonElement element : content) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject block = element.getAsJsonObject();
			switch (OpenAiBackend.stringOr(block, "type", "")) {
				case "text" -> texts.add(OpenAiBackend.stringOr(block, "text", ""));
				case "tool_use" -> {
					JsonElement input = block.get("input");
					Map<String, Object> arguments = input != null && input.isJsonObject() ? GSON.fromJson(input, MAP_TYPE) : null;
					calls.add(new ToolCall(OpenAiBackend.stringOr(block, "id", ""), OpenAiBackend.stringOr(block, "name", ""), arguments));
				}
				default -> {
				}
			}
		}

		String reason = OpenAiBackend.stringOr(response, "stop_reason", "end_turn");
		String refusal = "";
		Reply.Stop stop = switch (reason) {
			case "refusal" -> {
				refusal = refusalCategory(response);
				yield Reply.Stop.REFUSED;
			}
			case "model_context_window_exceeded" -> Reply.Stop.CONTEXT_FULL;
			case "pause_turn" -> Reply.Stop.PAUSED;
			case "max_tokens" -> Reply.Stop.TRUNCATED;
			default -> calls.isEmpty() ? Reply.Stop.DONE : Reply.Stop.TOOL_USE;
		};
		long input = 0;
		long output = 0;
		if (response.has("usage") && response.get("usage").isJsonObject()) {
			JsonObject usage = response.getAsJsonObject("usage");
			input = OpenAiBackend.longOr(usage, "input_tokens")
				+ OpenAiBackend.longOr(usage, "cache_read_input_tokens")
				+ OpenAiBackend.longOr(usage, "cache_creation_input_tokens");
			output = OpenAiBackend.longOr(usage, "output_tokens");
		}
		return new Reply(texts, calls, stop, refusal, input, output);
	}

	private static String refusalCategory(JsonObject response) {
		if (response.has("stop_details") && response.get("stop_details").isJsonObject()) {
			return OpenAiBackend.stringOr(response.getAsJsonObject("stop_details"), "category", "unspecified");
		}
		return "unspecified";
	}

	@Override
	public void addToolResults(List<ToolResult> results, List<String> extraUserTexts) {
		JsonArray content = new JsonArray();
		for (ToolResult result : results) {
			JsonObject block = new JsonObject();
			block.addProperty("type", "tool_result");
			block.addProperty("tool_use_id", result.callId());
			if (result.image() == null) {
				block.addProperty("content", result.content());
			} else {
				JsonArray parts = new JsonArray();
				parts.add(textBlock(result.content()));
				parts.add(imageBlock(result.image()));
				block.add("content", parts);
			}
			block.addProperty("is_error", result.error());
			content.add(block);
		}
		extraUserTexts.forEach(extra -> content.add(textBlock(extra)));
		if (!content.isEmpty()) {
			addMessage("user", content);
		}
		if (results.stream().anyMatch(result -> result.image() != null)) {
			pruneImages();
		}
	}

	/** Replaces all but the newest screenshots with a note, since every request resends the whole history. */
	private void pruneImages() {
		List<JsonArray> holders = new ArrayList<>();
		for (JsonElement message : messages) {
			for (JsonElement block : message.getAsJsonObject().getAsJsonArray("content")) {
				JsonObject object = block.getAsJsonObject();
				if ("tool_result".equals(OpenAiBackend.stringOr(object, "type", "")) && object.get("content").isJsonArray()) {
					JsonArray parts = object.getAsJsonArray("content");
					if (containsImage(parts)) {
						holders.add(parts);
					}
				}
			}
		}
		for (int i = 0; i < holders.size() - ChatBackend.MAX_IMAGES_KEPT; i++) {
			JsonArray parts = holders.get(i);
			for (int index = 0; index < parts.size(); index++) {
				if ("image".equals(OpenAiBackend.stringOr(parts.get(index).getAsJsonObject(), "type", ""))) {
					parts.set(index, textBlock(IMAGE_REMOVED));
				}
			}
		}
	}

	private static boolean containsImage(JsonArray parts) {
		for (JsonElement part : parts) {
			if ("image".equals(OpenAiBackend.stringOr(part.getAsJsonObject(), "type", ""))) {
				return true;
			}
		}
		return false;
	}

	private static JsonObject imageBlock(ToolResult.Image image) {
		JsonObject source = new JsonObject();
		source.addProperty("type", "base64");
		source.addProperty("media_type", image.mediaType());
		source.addProperty("data", image.base64());
		JsonObject block = new JsonObject();
		block.addProperty("type", "image");
		block.add("source", source);
		return block;
	}

	private void addMessage(String role, JsonArray content) {
		JsonObject message = new JsonObject();
		message.addProperty("role", role);
		message.add("content", content);
		messages.add(message);
	}

	private static JsonObject textBlock(String text) {
		JsonObject block = new JsonObject();
		block.addProperty("type", "text");
		block.addProperty("text", text);
		return block;
	}

	@Override
	public void cancel() {
		http.cancel();
	}

	@Override
	public void close() {
		http.close();
	}
}
