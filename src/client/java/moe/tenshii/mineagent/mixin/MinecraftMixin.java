package moe.tenshii.mineagent.mixin;

import moe.tenshii.mineagent.game.PlayerControl;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	// Vanilla aborts block breaking every tick the attack key is up, which would reset the agent's mining progress.
	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void mineagent$letAgentMine(boolean down, CallbackInfo ci) {
		if (PlayerControl.isMiningOverride()) {
			ci.cancel();
		}
	}
}
