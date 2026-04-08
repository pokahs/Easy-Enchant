// io.github.pokahs.easyenchant.mixin.client.HandledScreenAccessor.java
package io.github.pokahs.easyenchant.mixin.client;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractContainerScreen.class)
public interface HandledScreenInvoker {
    @Invoker("getHoveredSlot")
    public Slot easyEnchant$invokeGetSlotAt(double mouseX, double mouseY);
}
