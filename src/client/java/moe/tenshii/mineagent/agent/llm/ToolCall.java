package moe.tenshii.mineagent.agent.llm;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A tool call requested by the model.
 *
 * @param input parsed arguments, or null when the provider sent arguments that are not a JSON object
 */
public record ToolCall(String id, String name, @Nullable Map<String, Object> input) {
}
