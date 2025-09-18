package io.github.pokahs.easyenchant;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

public class EasyEnchantClient implements ClientModInitializer {

    public static KeyBinding SELECT_ITEM_KEY;

	@Override
	public void onInitializeClient() {

		SELECT_ITEM_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.easyenchant.select_item",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_SPACE,
                "category.easyenchant"
        ));

        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (screen instanceof AnvilScreen) {
                net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
                    .allowKeyPress(screen)
                    .register((scr, key, scancode, modifiers) -> {
                        if (EasyEnchantClient.SELECT_ITEM_KEY.matchesKey(key, scancode)) {
                            double scale = mc.getWindow().getScaleFactor();
                            double mx = mc.mouse.getX() / scale;
                            double my = mc.mouse.getY() / scale;
                            ((EasyEnchantAnvil)screen).toggleSelectedItem(mx, my);  // <-- your method on the screen
                            return false; // block vanilla (prevents typing that key into the name field)
                        }
                        return true; // allow all other keys
                    });
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            SelectedItemManager.clearSave();
        });
	}
}