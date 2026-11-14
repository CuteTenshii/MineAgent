package moe.tenshii.mineagent.agent.llm;

import java.util.List;
import java.util.Map;

/**
 * Provider-neutral tool definition; {@code properties} holds one JSON-schema object per parameter.
 */
public record ToolSpec(String name, String description, Map<String, Object> properties, List<String> required) {
}
