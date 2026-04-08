package io.github.pokahs.easyenchant;

import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.resources.Identifier;

public class EasyEnchantClient implements ClientModInitializer {

    public static KeyMapping SELECT_ITEM_KEY;
    
private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("easyenchant", "ldm"));

	@Override
	public void onInitializeClient() {

		SELECT_ITEM_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.easyenchant.select_item",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_SPACE,
                CATEGORY
        ));


        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (screen instanceof AnvilScreen) {
                net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
                    .allowKeyPress(screen)
                    .register((scr, key) -> {
                        if (EasyEnchantClient.SELECT_ITEM_KEY.matches(key)) {
                            double scale = mc.getWindow().getGuiScale(); // this prob wrong
                            double mx = mc.mouseHandler.xpos() / scale;
                            double my = mc.mouseHandler.ypos() / scale;
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