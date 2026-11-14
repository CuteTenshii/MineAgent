package moe.tenshii.mineagent;

import moe.tenshii.mineagent.agent.AgentController;
import moe.tenshii.mineagent.command.AgentCommands;
import moe.tenshii.mineagent.game.TaskRunner;
import moe.tenshii.mineagent.ui.AgentHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> AgentController.get().stop());
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "status"), new AgentHud());
	}
}
