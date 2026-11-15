package moe.tenshii.mineagent.mixin;

import java.util.Map;
import moe.tenshii.mineagent.agent.AgentController;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientAdvancements.class)
abstract class ClientAdvancementsMixin {
	/** Tells a running goal about each advancement the player just completed, using the same test as vanilla's toast. */
	@Inject(method = "update", at = @At("TAIL"))
	private void mineagent$onAdvancementsUpdated(ClientboundUpdateAdvancementsPacket packet, CallbackInfo ci) {
		if (packet.shouldReset()) {
			return;
		}
		ClientAdvancements self = (ClientAdvancements) (Object) this;
		for (Map.Entry<Identifier, AdvancementProgress> entry : packet.progress().entrySet()) {
			AdvancementNode node = self.tree().get(entry.getKey());
			if (node == null || !entry.getValue().isDone()) {
				continue;
			}
			DisplayInfo display = node.advancement().display().orElse(null);
			if (display != null && display.showToast()) {
				AgentController.get().addGameEvent("Advancement unlocked: " + display.title().getString() + " ("
					+ display.description().getString() + ").");
			}
		}
	}
}
