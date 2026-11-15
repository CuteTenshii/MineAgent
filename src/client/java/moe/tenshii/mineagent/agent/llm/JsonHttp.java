package moe.tenshii.mineagent.agent.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JSON POST requests with retries for transient errors and prompt cancellation, shared by the provider backends.
 */
final class JsonHttp implements AutoCloseable {
	private static final int MAX_RETRIES = 3;
	private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);
	private static final long MAX_RETRY_DELAY_MILLIS = 60_000L;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
	private final String providerName;
	private volatile boolean cancelled;
	private volatile CompletableFuture<HttpResponse<String>> pending;

	JsonHttp(String providerName) {
		this.providerName = providerName;
	}

	static URI endpoint(String providerName, String baseUrl, String path) throws ApiFailure {
		String trimmed = baseUrl.strip().replaceAll("/+$", "");
		try {
			URI uri = URI.create(trimmed + path);
			if (uri.getScheme() == null || uri.getHost() == null) {
				throw new IllegalArgumentException("missing scheme or host");
			}
			return uri;
		} catch (IllegalArgumentException e) {
			throw new ApiFailure("Invalid base URL '" + baseUrl + "' for " + providerName + ". Set one in /agent config, under Providers.");
		}
	}

	JsonObject post(URI endpoint, Map<String, String> headers, String body) throws ApiFailure, InterruptedException {
		return post(endpoint, headers, body, REQUEST_TIMEOUT);
	}

	JsonObject post(URI endpoint, Map<String, String> headers, String body, Duration timeout) throws ApiFailure, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
			.timeout(timeout)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body));
		headers.forEach(builder::header);
		return send(builder.build(), endpoint);
	}

	JsonObject get(URI endpoint, Map<String, String> headers, Duration timeout) throws ApiFailure, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(timeout).GET();
		headers.forEach(builder::header);
		return send(builder.build(), endpoint);
	}

	private JsonObject send(HttpRequest request, URI endpoint) throws ApiFailure, InterruptedException {
		for (int attempt = 0; ; attempt++) {
			HttpResponse<String> response;
			try {
				response = await(http.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
			} catch (IOException e) {
				if (attempt < MAX_RETRIES) {
					backoff(attempt, null);
					continue;
				}
				throw new ApiFailure("Network error talking to " + providerName + " (" + endpoint.getHost() + "): " + e.getMessage());
			}
			int status = response.statusCode();
			if (status / 100 == 2) {
				try {
					return JsonParser.parseString(response.body()).getAsJsonObject();
				} catch (JsonParseException | IllegalStateException e) {
					throw new ApiFailure(providerName + " returned a response that is not JSON: " + abbreviate(response.body()));
				}
			}
			boolean retryable = status == 408 || status == 409 || status == 429 || status >= 500;
			if (retryable && attempt < MAX_RETRIES) {
				backoff(attempt, response.headers().firstValue("retry-after").orElse(null));
				continue;
			}
			throw failure(status, response.body());
		}
	}

	private HttpResponse<String> await(CompletableFuture<HttpResponse<String>> request) throws IOException, InterruptedException {
		pending = request;
		try {
			while (true) {
				if (cancelled) {
					request.cancel(true);
					throw new CancellationException();
				}
				try {
					return request.get(200, TimeUnit.MILLISECONDS);
				} catch (TimeoutException ignored) {
					// Poll again so a stop request is honoured while waiting on the network.
				} catch (ExecutionException e) {
					if (e.getCause() instanceof IOException io) {
						throw io;
					}
					throw new IOException(e.getCause());
				}
			}
		} finally {
			pending = null;
		}
	}

	private void backoff(int attempt, String retryAfter) throws InterruptedException {
		long millis = 2000L << attempt;
		if (retryAfter != null) {
			try {
				millis = Math.min(MAX_RETRY_DELAY_MILLIS, Math.max(500L, (long) (Double.parseDouble(retryAfter.strip()) * 1000)));
			} catch (NumberFormatException ignored) {
				// HTTP-date form; keep the exponential delay.
			}
		}
		long until = System.currentTimeMillis() + millis;
		while (System.currentTimeMillis() < until) {
			if (cancelled) {
				throw new CancellationException();
			}
			Thread.sleep(Math.min(200L, Math.max(1L, until - System.currentTimeMillis())));
		}
	}

	private ApiFailure failure(int status, String body) {
		String detail = errorMessage(body);
		String message = switch (status) {
			case 401 -> providerName + " rejected the API key (401). Set a valid key with /agent key.";
			case 402 -> providerName + " reports a billing problem (402): " + detail;
			case 403 -> providerName + " denied access (403): " + detail;
			case 404 -> providerName + " returned 404: " + detail + ". Check the model id and base URL in /agent config, under Providers.";
			case 429 -> providerName + " rate limit or quota exceeded (429) even after retries: " + detail;
			default -> status >= 500
				? providerName + " had a server error (" + status + ") even after retries: " + detail
				: providerName + " rejected the request (" + status + "): " + detail;
		};
		return new ApiFailure(message, status);
	}

	private static String errorMessage(String body) {
		try {
			JsonElement parsed = JsonParser.parseString(body);
			if (parsed.isJsonObject()) {
				JsonObject object = parsed.getAsJsonObject();
				if (object.has("error") && object.get("error").isJsonObject()) {
					JsonElement message = object.getAsJsonObject("error").get("message");
					if (message != null && message.isJsonPrimitive()) {
						return message.getAsString();
					}
				}
				if (object.has("error") && object.get("error").isJsonPrimitive()) {
					return object.get("error").getAsString();
				}
			}
		} catch (JsonParseException | IllegalStateException ignored) {
			// Not JSON; show the raw text below.
		}
		return abbreviate(body);
	}

	static String abbreviate(String text) {
		String flat = text.replaceAll("\\s+", " ").strip();
		return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
	}

	void cancel() {
		cancelled = true;
		CompletableFuture<HttpResponse<String>> request = pending;
		if (request != null) {
			request.cancel(true);
		}
	}

	@Override
	public void close() {
		http.close();
	}
}
