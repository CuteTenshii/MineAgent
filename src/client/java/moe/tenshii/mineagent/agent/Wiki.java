package moe.tenshii.mineagent.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import moe.tenshii.mineagent.MineAgentClient;
import moe.tenshii.mineagent.game.TaskFailure;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * Looks things up on minecraft.wiki through its MediaWiki API: a search, then the best page as plain text, either its
 * introduction or one section.
 */
public final class Wiki {
	public static final String SITE = "https://minecraft.wiki";
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final int SEARCH_RESULTS = 5;
	// Enough for a full "Obtaining" or "Usage" section without filling the model's context with one page.
	private static final int MAX_SECTION_CHARS = 4000;
	private static final int MAX_INTRO_CHARS = 2500;
	private static final String QUERY = "query";

	private final String baseUrl;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();

	/** @param baseUrl the wiki's address without a trailing slash; tests point it at a local server */
	public Wiki(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	private record Heading(int level, String name, int start, int end) {
	}

	/**
	 * Finds the page that best matches {@code query} and returns its introduction and section names, or the text of
	 * {@code section} when one is given.
	 */
	public String lookup(String query, @Nullable String section) throws TaskFailure, InterruptedException {
		try {
			return read(query, section);
		} catch (NullPointerException | ClassCastException | IllegalStateException | IndexOutOfBoundsException e) {
			// A reply missing the fields every MediaWiki answer has: likely an error page or a changed API.
			throw new TaskFailure("minecraft.wiki sent an unexpected reply.");
		}
	}

	private String read(String query, @Nullable String section) throws TaskFailure, InterruptedException {
		JsonArray hits = get(new Query()
				.add("action", QUERY)
				.add("list", "search")
				.add("srnamespace", "0")
				.add("srlimit", Integer.toString(SEARCH_RESULTS))
				.add("srsearch", query))
			.getAsJsonObject(QUERY).getAsJsonArray("search");
		if (hits.isEmpty()) {
			throw new TaskFailure("No minecraft.wiki page matches \"" + query + "\". Try other words, such as the item or mob name.");
		}
		List<String> titles = new ArrayList<>();
		for (JsonElement hit : hits) {
			titles.add(hit.getAsJsonObject().get("title").getAsString());
		}
		JsonObject page = get(new Query()
				.add("action", QUERY)
				.add("prop", "extracts")
				.add("explaintext", "1")
				.add("exsectionformat", "wiki")
				.add("redirects", "1")
				.add("titles", titles.getFirst()))
			.getAsJsonObject(QUERY).getAsJsonArray("pages").get(0).getAsJsonObject();
		if (page.has("missing") || !page.has("extract")) {
			throw new TaskFailure("The minecraft.wiki page \"" + titles.getFirst() + "\" could not be read.");
		}
		String title = page.get("title").getAsString();
		String text = page.get("extract").getAsString();
		List<Heading> headings = headings(text);
		String others = titles.size() > 1 ? "\nOther matching pages: " + String.join(", ", titles.subList(1, titles.size())) + "." : "";
		String source = "\nSource: " + baseUrl + "/w/" + title.replace(' ', '_') + " (minecraft.wiki, CC BY-NC-SA 3.0).";
		List<String> names = headings.stream().filter(heading -> heading.level() == 2).map(Heading::name).toList();
		if (section == null) {
			String intro = tidy(headings.isEmpty() ? text : text.substring(0, headings.getFirst().start()));
			return "minecraft.wiki: " + title + "\n" + cap(intro, MAX_INTRO_CHARS)
				+ (names.isEmpty() ? "" : "\nSections (pass one as section to read it): " + String.join(", ", names) + ".") + others + source;
		}
		Heading match = find(headings, section);
		if (match == null) {
			throw new TaskFailure("The page \"" + title + "\" has no section \"" + section + "\""
				+ (names.isEmpty() ? "." : ". Its sections: " + String.join(", ", names) + "."));
		}
		return "minecraft.wiki: " + title + ", section " + match.name() + "\n" + cap(tidy(sectionText(text, headings, match)), MAX_SECTION_CHARS)
			+ others + source;
	}

	/** The section's text up to the next heading at its level or above, so subsections stay included. */
	private static String sectionText(String text, List<Heading> headings, Heading match) {
		for (Heading heading : headings) {
			if (heading.start() > match.start() && heading.level() <= match.level()) {
				return text.substring(match.end(), heading.start());
			}
		}
		return text.substring(match.end());
	}

	/** Plain-text extracts mark headings as whole lines like "== Obtaining ==", deeper levels with more signs. */
	private static List<Heading> headings(String text) {
		List<Heading> headings = new ArrayList<>();
		int lineStart = 0;
		while (lineStart < text.length()) {
			int lineEnd = text.indexOf('\n', lineStart);
			if (lineEnd < 0) {
				lineEnd = text.length();
			}
			String line = text.substring(lineStart, lineEnd).strip();
			int level = 0;
			while (level < line.length() && line.charAt(level) == '=') {
				level++;
			}
			String marks = "=".repeat(level);
			if (level >= 2 && line.length() > level * 2 && line.endsWith(marks)) {
				String name = line.substring(level, line.length() - level).strip();
				if (!name.isEmpty()) {
					headings.add(new Heading(level, name, lineStart, lineEnd));
				}
			}
			lineStart = lineEnd + 1;
		}
		return headings;
	}

	/** An exact name first, then one that contains the request, so "obtain" finds "Obtaining". */
	private static @Nullable Heading find(List<Heading> headings, String section) {
		String wanted = section.strip().toLowerCase(Locale.ROOT);
		for (Heading heading : headings) {
			if (heading.name().toLowerCase(Locale.ROOT).equals(wanted)) {
				return heading;
			}
		}
		for (Heading heading : headings) {
			if (heading.name().toLowerCase(Locale.ROOT).contains(wanted)) {
				return heading;
			}
		}
		return null;
	}

	private static String tidy(String text) {
		String compact = text.replaceAll("\\n{3,}", "\n\n").strip();
		return compact.isEmpty() ? "(This part of the page has no text, only tables or images.)" : compact;
	}

	private static String cap(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max) + "… (cut; ask for a narrower section)";
	}

	/** Query parameters in order, each name and value percent-encoded when the URI is built. */
	private static final class Query {
		private final Map<String, String> parameters = new LinkedHashMap<>();

		Query add(String name, String value) {
			parameters.put(name, value);
			return this;
		}

		URI toUri(String endpoint) {
			StringJoiner query = new StringJoiner("&", endpoint + "?", "");
			parameters.forEach((name, value) -> query.add(encode(name) + "=" + encode(value)));
			return URI.create(query.toString());
		}

		private static String encode(String value) {
			return URLEncoder.encode(value, StandardCharsets.UTF_8);
		}
	}

	private JsonObject get(Query query) throws TaskFailure, InterruptedException {
		URI uri = query.add("format", "json").add("formatversion", "2").toUri(baseUrl + "/api.php");
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(TIMEOUT)
			.header("User-Agent", userAgent())
			.GET()
			.build();
		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new TaskFailure("Could not reach minecraft.wiki: " + e.getMessage());
		}
		if (response.statusCode() != 200) {
			throw new TaskFailure("minecraft.wiki answered with HTTP " + response.statusCode() + ".");
		}
		try {
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			if (json.has("error")) {
				throw new TaskFailure("minecraft.wiki refused the lookup: " + json.getAsJsonObject("error").get("info").getAsString());
			}
			return json;
		} catch (JsonParseException | IllegalStateException e) {
			throw new TaskFailure("minecraft.wiki sent an unexpected reply.");
		}
	}

	// The wiki asks API clients to identify themselves.
	private static String userAgent() {
		String version = FabricLoader.getInstance().getModContainer(MineAgentClient.MOD_ID)
			.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("dev");
		return "MineAgent/" + version + " (https://github.com/CuteTenshii/MineAgent)";
	}
}
