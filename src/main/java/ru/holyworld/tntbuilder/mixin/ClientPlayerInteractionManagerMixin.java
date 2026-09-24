package ru.holyworld.tntbuilder.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.holyworld.tntbuilder.Macro;

/**
 * Ванильный клиент каждый тик вызывает cancelBlockBreaking(), если кнопка атаки не зажата,
 * и это сбрасывает прогресс ломания блока. Пока макрос ломает пушку - подавляем этот вызов.
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {

    @Inject(method = "cancelBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void tntbuilder$keepBreakingProgress(CallbackInfo ci) {
        if (Macro.suppressCancel) {
            ci.cancel();
        }
    }
}
