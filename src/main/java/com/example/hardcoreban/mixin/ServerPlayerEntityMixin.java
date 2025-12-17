package com.example.hardcoreban.mixin;

import com.example.hardcoreban.HardcoreBanMod;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {

    @Inject(method = "onDeath", at = @At("TAIL"))
    private void hardcoreban$onDeath(DamageSource source, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;

        String deathMessage = player.getDamageTracker()
                .getDeathMessage()
                .getString();

        int x = player.getBlockPos().getX();
        int y = player.getBlockPos().getY();
        int z = player.getBlockPos().getZ();

        String reason = HardcoreBanMod.formatReason(deathMessage, x, y, z);

        HardcoreBanMod.queueTempBan(player.getName().getString(), player.getName().getString() + " has died");
    }
}
