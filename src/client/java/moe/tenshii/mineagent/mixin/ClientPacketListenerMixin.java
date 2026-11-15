package moe.tenshii.mineagent.mixin;

import moe.tenshii.mineagent.agent.AgentController;
import moe.tenshii.mineagent.game.CombatReflex;
import moe.tenshii.mineagent.game.DamageTracker;
import moe.tenshii.mineagent.game.ServerTps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.world.damagesource.DamageSource;
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

	@Inject(method = "handleSetTime", at = @At("TAIL"))
	private void mineagent$onSetTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
		ServerTps.onTimeUpdate(packet.gameTime());
	}

	@Inject(method = "handleDamageEvent", at = @At("TAIL"))
	private void mineagent$onDamage(ClientboundDamageEventPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.level != null && packet.entityId() == mc.player.getId()) {
			DamageSource source = packet.getSource(mc.level);
			DamageTracker.onDamage(mc, source);
			CombatReflex.onHit(mc, source);
		}
	}
}
