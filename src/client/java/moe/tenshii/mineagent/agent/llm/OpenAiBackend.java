package moe.tenshii.mineagent.agent.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import moe.tenshii.mineagent.MineAgentClient;

/**
 * Any provider that implements the OpenAI Chat Completions API: OpenAI, DeepSeek, Gemini, OpenRouter, Groq, Mistral,
 * xAI, Ollama, or a custom base URL.
 */
public final class OpenAiBackend implements ChatBackend {
	private static final Gson GSON = new Gson();
	private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
	}.getType();

	private final JsonHttp http;
	private final URI endpoint;
	private final Map<String, String> headers;
	private final String model;
	private final String providerName;
	private final JsonArray tools = new JsonArray();
	private final JsonArray messages = new JsonArray();

	public OpenAiBackend(String providerName, String baseUrl, String apiKey, String model, String system, List<ToolSpec> specs) throws ApiFailure {
		this.providerName = providerName;
		this.endpoint = JsonHttp.endpoint(providerName, baseUrl, "/chat/completions");
		this.http = new JsonHttp(providerName);
		this.headers = apiKey.isBlank() ? Map.of() : Map.of("Authorization", "Bearer " + apiKey);
		this.model = model;
		for (ToolSpec spec : specs) {
			JsonObject parameters = new JsonObject();
			parameters.addProperty("type", "object");
			parameters.add("properties", GSON.toJsonTree(spec.properties()));
			parameters.add("required", GSON.toJsonTree(spec.required()));
			JsonObject function = new JsonObject();
			function.addProperty("name", spec.name());
			function.addProperty("description", spec.description());
			function.add("parameters", parameters);
			JsonObject tool = new JsonObject();
			tool.addProperty("type", "function");
			tool.add("function", function);
			tools.add(tool);
		}
		JsonObject systemMessage = new JsonObject();
		systemMessage.addProperty("role", "system");
		systemMessage.addProperty("content", system);
		messages.add(systemMessage);
	}

	@Override
	public void addUserText(String text) {
		JsonObject message = new JsonObject();
		message.addProperty("role", "user");
		message.addProperty("content", text);
		messages.add(message);
	}

	@Override
	public Reply next() throws ApiFailure, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.add("messages", messages);
		body.add("tools", tools);
		body.addProperty("tool_choice", "auto");
		JsonObject response = http.post(endpoint, headers, GSON.toJson(body));

		JsonArray choices = response.has("choices") && response.get("choices").isJsonArray() ? response.getAsJsonArray("choices") : new JsonArray();
		if (choices.isEmpty()) {
			throw new ApiFailure(providerName + " returned no choices: " + JsonHttp.abbreviate(response.toString()));
		}
		JsonObject choice = choices.get(0).getAsJsonObject();
		JsonObject message = choice.has("message") && choice.get("message").isJsonObject() ? choice.getAsJsonObject("message") : new JsonObject();
		String finish = stringOr(choice, "finish_reason", "stop");

		List<String> texts = new ArrayList<>();
		String content = stringOr(message, "content", "");
		if (!content.isBlank()) {
			texts.add(content);
		}
		List<ToolCall> calls = new ArrayList<>();
		JsonArray echoedCalls = new JsonArray();
		if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
			JsonArray rawCalls = message.getAsJsonArray("tool_calls");
			for (int i = 0; i < rawCalls.size(); i++) {
				JsonObject raw = rawCalls.get(i).getAsJsonObject();
				JsonObject function = raw.has("function") ? raw.getAsJsonObject("function") : new JsonObject();
				String id = stringOr(raw, "id", "");
				if (id.isBlank()) {
					id = "call_" + messages.size() + "_" + i;
				}
				String name = stringOr(function, "name", "");
				String arguments = stringOr(function, "arguments", "{}");
				calls.add(new ToolCall(id, name, parseArguments(arguments)));
				JsonObject echoFunction = new JsonObject();
				echoFunction.addProperty("name", name);
				echoFunction.addProperty("arguments", arguments);
				JsonObject echo = new JsonObject();
				echo.addProperty("id", id);
				echo.addProperty("type", "function");
				echo.add("function", echoFunction);
				echoedCalls.add(echo);
			}
		}

		JsonObject assistant = new JsonObject();
		assistant.addProperty("role", "assistant");
		if (content.isEmpty() && !echoedCalls.isEmpty()) {
			assistant.add("content", JsonNull.INSTANCE);
		} else {
			assistant.addProperty("content", content);
		}
		if (!echoedCalls.isEmpty()) {
			assistant.add("tool_calls", echoedCalls);
		}
		// DeepSeek's thinking models expect their reasoning back alongside tool calls; other providers never send it.
		if (message.has("reasoning_content") && !message.get("reasoning_content").isJsonNull()) {
			assistant.add("reasoning_content", message.get("reasoning_content"));
		}
		messages.add(assistant);

		Reply.Stop stop = switch (finish) {
			case "length" -> Reply.Stop.TRUNCATED;
			case "content_filter" -> Reply.Stop.REFUSED;
			default -> calls.isEmpty() ? Reply.Stop.DONE : Reply.Stop.TOOL_USE;
		};
		long input = 0;
		long output = 0;
		if (response.has("usage") && response.get("usage").isJsonObject()) {
			JsonObject usage = response.getAsJsonObject("usage");
			input = longOr(usage, "prompt_tokens");
			output = longOr(usage, "completion_tokens");
		}
		return new Reply(texts, calls, stop, stop == Reply.Stop.REFUSED ? "content_filter" : "", input, output);
	}

	@Override
	public void addToolResults(List<ToolResult> results, List<String> extraUserTexts) {
		JsonArray images = new JsonArray();
		for (ToolResult result : results) {
			String text = result.error() ? "Error: " + result.content() : result.content();
			if (result.image() != null) {
				// Tool messages cannot carry images in this API, so the picture follows in a user message.
				text += "\n(The screenshot is attached in the next user message.)";
				images.add(textPart("Screenshot from tool call " + result.callId() + ":"));
				images.add(imagePart(result.image()));
			}
			JsonObject message = new JsonObject();
			message.addProperty("role", "tool");
			message.addProperty("tool_call_id", result.callId());
			message.addProperty("content", text);
			messages.add(message);
		}
		if (!images.isEmpty()) {
			JsonObject message = new JsonObject();
			message.addProperty("role", "user");
			message.add("content", images);
			messages.add(message);
			pruneImages();
		}
		extraUserTexts.forEach(this::addUserText);
	}

	/** Replaces all but the newest screenshots with a note, since every request resends the whole history. */
	private void pruneImages() {
		List<JsonArray> holders = new ArrayList<>();
		for (JsonElement message : messages) {
			JsonElement content = message.getAsJsonObject().get("content");
			if (content != null && content.isJsonArray()) {
				for (JsonElement part : content.getAsJsonArray()) {
					if (isImagePart(part)) {
						holders.add(content.getAsJsonArray());
						break;
					}
				}
			}
		}
		for (int i = 0; i < holders.size() - ChatBackend.MAX_IMAGES_KEPT; i++) {
			JsonArray parts = holders.get(i);
			for (int index = 0; index < parts.size(); index++) {
				if (isImagePart(parts.get(index))) {
					parts.set(index, textPart("[An older screenshot was removed from the conversation to save tokens.]"));
				}
			}
		}
	}

	private static boolean isImagePart(JsonElement part) {
		return part.isJsonObject() && "image_url".equals(stringOr(part.getAsJsonObject(), "type", ""));
	}

	private static JsonObject textPart(String text) {
		JsonObject part = new JsonObject();
		part.addProperty("type", "text");
		part.addProperty("text", text);
		return part;
	}

	private static JsonObject imagePart(ToolResult.Image image) {
		JsonObject url = new JsonObject();
		url.addProperty("url", "data:" + image.mediaType() + ";base64," + image.base64());
		JsonObject part = new JsonObject();
		part.addProperty("type", "image_url");
		part.add("image_url", url);
		return part;
	}

	private static Map<String, Object> parseArguments(String arguments) {
		try {
			JsonElement parsed = JsonParser.parseString(arguments.isBlank() ? "{}" : arguments);
			return parsed.isJsonObject() ? GSON.fromJson(parsed, MAP_TYPE) : null;
		} catch (JsonParseException e) {
			MineAgentClient.LOGGER.debug("Unparseable tool arguments: {}", arguments);
			return null;
		}
	}

	static String stringOr(JsonObject object, String key, String fallback) {
		JsonElement value = object.get(key);
		return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? fallback : value.getAsString();
	}

	static long longOr(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value == null || !value.isJsonPrimitive() ? 0L : value.getAsLong();
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
