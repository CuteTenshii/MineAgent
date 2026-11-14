package moe.tenshii.mineagent.mixin;

import moe.tenshii.mineagent.agent.AgentController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
	@Inject(method = "handlePlayerCombatKill", at = @At("TAIL"))
	private void mineagent$onPlayerDeath(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && packet.playerId() == mc.player.getId()) {
			AgentController.get().onPlayerDeath(mc, packet.message());
		}
	}
}
