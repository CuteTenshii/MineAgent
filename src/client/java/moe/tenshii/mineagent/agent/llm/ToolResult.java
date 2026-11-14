package moe.tenshii.mineagent.agent.llm;

public record ToolResult(String callId, String content, boolean error) {
}
