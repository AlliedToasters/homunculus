package dev.toast.homunculus.mixin;

import dev.toast.homunculus.WurstHud;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels Wurst's single HUD-render entry point when {@link WurstHud} says hide.
 * IngameHUD.onRenderGUI draws the logo/version, the active-hacks list, and the
 * TabGui — cancelling at HEAD suppresses all of them for clean recordings.
 *
 * Targeted by string so homunculus keeps zero compile-time dependency on Wurst
 * (mirrors the reflection-only bridge in Wurst.java). "onRenderGUI" is a Wurst
 * method name and passes through mapping unchanged; the GuiGraphics param is the
 * only token that gets remapped (-> class_332) to match the runtime descriptor.
 * {@code HomunculusMixinPlugin} keeps this dormant when Wurst is absent.
 */
@Mixin(targets = "net.wurstclient.hud.IngameHUD")
public class WurstIngameHudMixin {
    @Inject(method = "onRenderGUI", at = @At("HEAD"), cancellable = true)
    private void homunculus$suppressHud(GuiGraphics graphics, float partialTicks, CallbackInfo ci) {
        if (WurstHud.isHidden()) {
            ci.cancel();
        }
    }
}
