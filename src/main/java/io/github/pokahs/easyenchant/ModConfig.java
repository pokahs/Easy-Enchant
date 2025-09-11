package io.github.pokahs.easyenchant;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.autoconfig.annotation.ConfigEntry.Gui.EnumHandler.EnumDisplayOption;

public @Config(name = "easyenchant")
class ModConfig implements ConfigData {

    public static enum Mode {
        LEVELS,
        XP
    };
    
    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.Gui.EnumHandler(option = EnumDisplayOption.BUTTON)
    public Mode mode = Mode.LEVELS;

    
    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.ColorPicker(allowAlpha = true)
    public int selectedItemFillColor = 0x55_8A2BE2;

    
    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.ColorPicker(allowAlpha = true)
    public int selectedItemBorderColor = 0xFF_5E1DB0;


    @ConfigEntry.Gui.Tooltip(count = 4)
    public int packetTickDelay = 5;

    
    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean allowRenaming = false;

    
    @ConfigEntry.Gui.Tooltip(count = 3)
    public boolean instantEnchant = false;
    
}