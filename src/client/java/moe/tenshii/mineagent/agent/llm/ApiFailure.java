package moe.tenshii.mineagent.agent.llm;

/**
 * A provider error turned into a message the player can act on.
 */
public final class ApiFailure extends Exception {
	public ApiFailure(String message) {
		super(message, null, false, false);
	}
}
