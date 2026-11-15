package moe.tenshii.mineagent.agent.llm;

import org.jspecify.annotations.Nullable;

/**
 * @param image a picture the tool produced, such as a screenshot, or null
 */
public record ToolResult(String callId, String content, boolean error, @Nullable Image image) {
	public ToolResult(String callId, String content, boolean error) {
		this(callId, content, error, null);
	}

	/** An encoded image, e.g. media type image/jpeg with base64 data. */
	public record Image(String mediaType, String base64) {
	}
}
