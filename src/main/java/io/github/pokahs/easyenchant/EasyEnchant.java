package io.github.pokahs.easyenchant;

import net.fabricmc.api.ModInitializer;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;

public class EasyEnchant implements ModInitializer {
	public static final String MOD_ID = "easyenchant";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	//public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		
		AutoConfig.register(ModConfig.class, GsonConfigSerializer::new);
	}
}
