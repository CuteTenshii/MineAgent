package moe.tenshii.mineagent.game.task;

import moe.tenshii.mineagent.game.Task;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

public final class WaitTask implements Task {
	private final int ticks;
	private int elapsed;

	public WaitTask(int ticks) {
		this.ticks = ticks;
	}

	@Override
	public @Nullable String tick(Minecraft mc) {
		return ++elapsed >= ticks ? "Waited " + ticks / 20.0 + " seconds." : null;
	}

	@Override
	public int timeoutTicks() {
		return ticks + 20;
	}

	@Override
	public String describe() {
		return "waiting";
	}
}
