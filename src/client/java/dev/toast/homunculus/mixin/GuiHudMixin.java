package dev.toast.homunculus.mixin;

import dev.toast.homunculus.HudState;
import dev.toast.homunculus.HudState.Element;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels individual vanilla HUD element renders when {@link HudState} flags them
 * hidden. Each target is a private {@code renderX} on {@code Gui}; the handlers
 * take only {@link CallbackInfo} (a HEAD inject matches the empty arg prefix), so
 * no Minecraft param types need importing. All elements default to visible.
 */
@Mixin(Gui.class)
public class GuiHudMixin {
    @Inject(method = "renderPlayerHealth", at = @At("HEAD"), cancellable = true)
    private void homunculus$health(CallbackInfo ci) {
        if (HudState.isHidden(Element.HEALTH)) ci.cancel();
    }

    @Inject(method = "renderFood", at = @At("HEAD"), cancellable = true)
    private void homunculus$food(CallbackInfo ci) {
        if (HudState.isHidden(Element.FOOD)) ci.cancel();
    }

    @Inject(method = "renderAirBubbles", at = @At("HEAD"), cancellable = true)
    private void homunculus$air(CallbackInfo ci) {
        if (HudState.isHidden(Element.AIR)) ci.cancel();
    }

    @Inject(method = "renderItemHotbar", at = @At("HEAD"), cancellable = true)
    private void homunculus$hotbar(CallbackInfo ci) {
        if (HudState.isHidden(Element.HOTBAR)) ci.cancel();
    }

    @Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
    private void homunculus$effects(CallbackInfo ci) {
        if (HudState.isHidden(Element.EFFECTS)) ci.cancel();
    }

    @Inject(method = "renderExperienceBar", at = @At("HEAD"), cancellable = true)
    private void homunculus$xpBar(CallbackInfo ci) {
        if (HudState.isHidden(Element.EXPERIENCE)) ci.cancel();
    }

    @Inject(method = "renderExperienceLevel", at = @At("HEAD"), cancellable = true)
    private void homunculus$xpLevel(CallbackInfo ci) {
        if (HudState.isHidden(Element.EXPERIENCE)) ci.cancel();
    }

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void homunculus$crosshair(CallbackInfo ci) {
        if (HudState.isHidden(Element.CROSSHAIR)) ci.cancel();
    }

    @Inject(method = "renderSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void homunculus$selectedItem(CallbackInfo ci) {
        if (HudState.isHidden(Element.SELECTED_ITEM)) ci.cancel();
    }

    @Inject(method = "renderDemoOverlay", at = @At("HEAD"), cancellable = true)
    private void homunculus$demo(CallbackInfo ci) {
        if (HudState.isHidden(Element.DEMO)) ci.cancel();
    }
}
