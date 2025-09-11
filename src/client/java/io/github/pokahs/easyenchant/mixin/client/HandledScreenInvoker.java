// io.github.pokahs.easyenchant.mixin.client.HandledScreenAccessor.java
package io.github.pokahs.easyenchant.mixin.client;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(HandledScreen.class)
public interface HandledScreenInvoker {
    @Invoker("getSlotAt")
    public Slot easyEnchant$invokeGetSlotAt(double mouseX, double mouseY);
}
