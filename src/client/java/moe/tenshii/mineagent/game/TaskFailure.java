package moe.tenshii.mineagent.game;

public final class TaskFailure extends Exception {
	public TaskFailure(String message) {
		super(message, null, false, false);
	}
}
