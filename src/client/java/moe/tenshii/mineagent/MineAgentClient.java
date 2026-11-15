package moe.tenshii.mineagent;

import moe.tenshii.mineagent.agent.AgentController;
import moe.tenshii.mineagent.command.AgentCommands;
import moe.tenshii.mineagent.game.DamageTracker;
import moe.tenshii.mineagent.game.ServerTps;
import moe.tenshii.mineagent.game.TaskRunner;
import moe.tenshii.mineagent.game.task.CommandTask;
import moe.tenshii.mineagent.ui.AgentHud;
import moe.tenshii.mineagent.ui.PathRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MineAgentClient implements ClientModInitializer {
	public static final String MOD_ID = "mineagent";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> AgentCommands.register(dispatcher));
		ClientTickEvents.END_CLIENT_TICK.register(TaskRunner::tick);
		ClientTickEvents.END_CLIENT_TICK.register(DamageTracker::tick);
		ClientTickEvents.END_CLIENT_TICK.register(PathRenderer::tick);
		ClientTickEvents.END_CLIENT_TICK.register(client -> AgentController.get().tick(client));
		ClientReceiveMessageEvents.GAME.register(CommandTask::onGameMessage);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			AgentController.get().stop();
			ServerTps.reset();
		});
		// Another server's world time would otherwise blend into the TPS estimate.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ServerTps.reset());
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "status"), new AgentHud());
	}
}
