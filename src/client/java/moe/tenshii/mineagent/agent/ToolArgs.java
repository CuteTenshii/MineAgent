package moe.tenshii.mineagent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import moe.tenshii.mineagent.game.TaskFailure;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Typed, validated access to a tool call's JSON input.
 */
final class ToolArgs {
	private final Map<String, Object> values;

	ToolArgs(Map<String, Object> values) {
		this.values = values;
	}

	String string(String key) throws TaskFailure {
		String value = optString(key);
		if (value == null || value.isBlank()) {
			throw new TaskFailure("Missing required string parameter '" + key + "'.");
		}
		return value;
	}

	@Nullable String optString(String key) throws TaskFailure {
		Object value = values.get(key);
		if (value == null) {
			return null;
		}
		if (value instanceof String text) {
			return text;
		}
		throw new TaskFailure("Parameter '" + key + "' must be a string.");
	}

	/** A list of strings, or an empty list when the parameter is absent. */
	List<String> optStrings(String key) throws TaskFailure {
		Object value = values.get(key);
		if (value == null) {
			return List.of();
		}
		if (!(value instanceof List<?> list)) {
			throw new TaskFailure("Parameter '" + key + "' must be a list of strings.");
		}
		List<String> strings = new ArrayList<>(list.size());
		for (Object item : list) {
			if (!(item instanceof String text)) {
				throw new TaskFailure("Parameter '" + key + "' must be a list of strings.");
			}
			strings.add(text);
		}
		return strings;
	}

	int integer(String key) throws TaskFailure {
		Object value = values.get(key);
		if (value == null) {
			throw new TaskFailure("Missing required integer parameter '" + key + "'.");
		}
		return toInt(key, value);
	}

	int optInt(String key, int fallback, int min, int max) throws TaskFailure {
		Object value = values.get(key);
		int result = value == null ? fallback : toInt(key, value);
		if (result < min || result > max) {
			throw new TaskFailure("Parameter '" + key + "' must be between " + min + " and " + max + ".");
		}
		return result;
	}

	boolean has(String key) {
		return values.get(key) != null;
	}

	boolean optBool(String key, boolean fallback) throws TaskFailure {
		Object value = values.get(key);
		if (value == null) {
			return fallback;
		}
		if (value instanceof Boolean flag) {
			return flag;
		}
		if (value instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
			return Boolean.parseBoolean(text);
		}
		throw new TaskFailure("Parameter '" + key + "' must be true or false.");
	}

	BlockPos pos() throws TaskFailure {
		return new BlockPos(integer("x"), integer("y"), integer("z"));
	}

	private static int toInt(String key, Object value) throws TaskFailure {
		if (value instanceof Number number) {
			return (int) Math.floor(number.doubleValue());
		}
		if (value instanceof String text) {
			try {
				return (int) Math.floor(Double.parseDouble(text.strip()));
			} catch (NumberFormatException ignored) {
				// Reported below with the parameter name.
			}
		}
		throw new TaskFailure("Parameter '" + key + "' must be a number.");
	}
}
