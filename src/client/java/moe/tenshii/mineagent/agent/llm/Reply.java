package moe.tenshii.mineagent.agent.llm;

import java.util.List;

/**
 * One model response, reduced to what the agent loop needs.
 */
public record Reply(List<String> texts, List<ToolCall> toolCalls, Stop stop, String refusalDetail, long inputTokens, long outputTokens) {
	public enum Stop {
		DONE,
		TOOL_USE,
		/** The output token limit cut the response off. */
		TRUNCATED,
		/** Server-side tool work paused; send the conversation again unchanged. */
		PAUSED,
		REFUSED,
		CONTEXT_FULL
	}
}
