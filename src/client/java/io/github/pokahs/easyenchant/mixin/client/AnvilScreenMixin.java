package io.github.pokahs.easyenchant.mixin.client;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.authlib.minecraft.client.MinecraftClient;
import com.terraformersmc.modmenu.ModMenu;

import io.github.pokahs.easyenchant.AutoEnchanter;
import io.github.pokahs.easyenchant.EasyEnchantAnvil;
import io.github.pokahs.easyenchant.EasyEnchantClient;
import io.github.pokahs.easyenchant.EnchantOptimizer;
import io.github.pokahs.easyenchant.EnchantOptimizer.Instruction;
import io.github.pokahs.easyenchant.EnchantOptimizer.Plan;
import io.github.pokahs.easyenchant.SelectedItemManager;
import io.github.pokahs.easyenchant.SelectedItemManager.AddResult;
import io.github.pokahs.easyenchant.ModConfig;
import io.github.pokahs.easyenchant.ModConfig.Mode;
import io.github.pokahs.easyenchant.StatusManager;
import io.github.pokahs.easyenchant.StatusManager.TextColor;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.AutoConfigClient;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.ItemCombinerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin extends ItemCombinerScreen<AnvilMenu> implements EasyEnchantAnvil {

    
    ModConfig cfg = AutoConfig.getConfigHolder(ModConfig.class).getConfig();
    
    @Shadow @Final static private Identifier ANVIL_LOCATION;

    public AnvilScreenMixin(AnvilMenu handler, Inventory inventory, Component title) {
      super(handler, inventory, title, ANVIL_LOCATION);
    }
	

	private Button enchantFullyButton;
	private Button enchantStepButton;

    private final int BUTTON_WIDTH = imageWidth / 2;
    private final int BUTTON_HEIGHT = 20;
    private final int STATUS_PADDING = 5;

    private CycleButton<Mode> modeButton;

    private StatusManager statusManager;
    
    private SelectedItemManager selectedItemManager = SelectedItemManager.getInstance();
    
    private Plan plan; // null when no valid plan

    private AutoEnchanter enchanter; // null when idle

    private ItemStack outputSave = ItemStack.EMPTY;

    
    @Inject(method = "subInit", at = @At("TAIL"))
    protected void setup(CallbackInfo ci) {

        
        cfg = AutoConfig.getConfigHolder(ModConfig.class).getConfig();

        enchantFullyButton = addRenderableWidget(Button.builder(Component.translatable("easyenchant.button.enchant_fully"), b -> {
            enchanter = new AutoEnchanter(menu, plan.instructions, cfg.packetTickDelay, cfg.allowRenaming, cfg.instantEnchant);
            plan = null;
            handleEnchanterInitiation();
        })
            .bounds(leftPos, topPos + imageHeight, BUTTON_WIDTH, BUTTON_HEIGHT)
            .build());


        enchantStepButton = addRenderableWidget(Button.builder(Component.translatable("easyenchant.button.enchant_step"), b -> {
            boolean noMoreInstructions = plan.instructions.size() == 1;
            enchanter = new AutoEnchanter(menu, plan.instructions.getFirst(), cfg.packetTickDelay, cfg.allowRenaming, cfg.instantEnchant, noMoreInstructions);
            plan = noMoreInstructions ? null : new Plan(plan.instructions.subList(1, plan.instructions.size()));
            handleEnchanterInitiation();
        })
            .bounds(leftPos + BUTTON_WIDTH, topPos + imageHeight, BUTTON_WIDTH, BUTTON_HEIGHT)
            .build());

        // CycleButton.builder(null, null).
        
        modeButton = addRenderableWidget(
            CycleButton.builder((Mode mode) ->
                    mode == Mode.LEVELS ? Component.translatable("easyenchant.button.levels") : Component.translatable("easyenchant.button.xp"), cfg.mode)
                .withValues(Mode.LEVELS, Mode.XP)
                .displayOnlyValue()
                .create(
                    leftPos, topPos + imageHeight + BUTTON_HEIGHT, BUTTON_WIDTH, BUTTON_HEIGHT,
                    Component.empty(),
                    (btn, mode) -> {
                        tryOptimize();
                        cfg.mode = mode;
                    }
                )
            );
        

        addRenderableWidget(Button.builder(Component.translatable("easyenchant.button.config"), b -> {
            // Minecraft.getInstance().setScreen(AutoConfig.getConfigHolder(ModConfig.class).getConfig());
            // Minecraft.getInstance().setScreen(ConfigBuilder.create().setParentScreen(Minecraft.getInstance().screen).build());
            // Minecraft.getInstance().setScreen(ModMenu.getConfigScreen(AutoConfig.getConfigHolder(ModConfig.class).getConfig()).apply(Minecraft.getInstance().screen));
            Minecraft.getInstance().setScreen(AutoConfigClient.getConfigScreen(ModConfig.class, Minecraft.getInstance().screen).get());

        })
            .bounds(leftPos + BUTTON_WIDTH, topPos + imageHeight + BUTTON_HEIGHT, BUTTON_WIDTH, BUTTON_HEIGHT)
            .build());

        statusManager = new StatusManager(font, imageWidth / 2 + leftPos, imageHeight + 2 * BUTTON_HEIGHT + STATUS_PADDING + topPos, Minecraft.getInstance().getWindow().getGuiScaledWidth());
        
        handleItemSelectionChange(); // Handles if differences in saved manager vs current inventory

        // statusManager.updateStatusTo("Screen loaded!", TextColor.SUCCESS);

        // Intro message if manager empty, guides user how to start
        if (!selectedItemManager.hasGear() && !selectedItemManager.hasBooks()) statusManager.updateStatusTo(Component.translatable("easyenchant.status.select_by_pressing", EasyEnchantClient.SELECT_ITEM_KEY.getTranslatedKeyMessage()).getString(), TextColor.DEFAULT, 5000);

    }

    private void setButtonsAvailability(boolean available) {
        // Note unless enchanter running than they might get enabled again instantly
        enchantFullyButton.active = available;
        enchantStepButton.active = available;
    }


    public void toggleSelectedItem(double mouseX, double mouseY) {

        // Do not toggle anything while automation is running
        if (enchanting()) return;

        // If focused on text field, ignore key trigger
        if (this.getFocused() instanceof EditBox tf && tf.canConsumeInput()) return;

        // Grab slot at mouse position
        Slot hovered = ((HandledScreenInvoker) this)
                .easyEnchant$invokeGetSlotAt(mouseX, mouseY);

        // If not actually a slot or not an inventory slot, ignore
        if (hovered == null) return;
        if (!(hovered.container instanceof Inventory)) return;

        // Get id (id = index number of slot in inventory), get stack, if empty, ignore
        int id = hovered.index;
        ItemStack stack = hovered.getItem();
        if (stack.isEmpty()) return;

        boolean alreadySelected = selectedItemManager.hasId(id);

        if (alreadySelected) selectedItemManager.removeItem(id);
        else {
            AddResult result = selectedItemManager.tryAddItem(id, stack);

            if (!result.successful()) {
                statusManager.updateStatusTo(result);
                return;
            }
        }

        // At this point, we know 100% selected items has changed, so should recompute plan and update status if needed
        handleItemSelectionChange();

    }

    public void handleItemSelectionChange() {
        if (selectedItemManager.isValid()) tryOptimize();
        else {
            // Selected items not valid, disable button and update text accordingly
            setButtonsAvailability(false);
            plan = null;

            boolean hasBooks = selectedItemManager.hasBooks();
            boolean hasGear = selectedItemManager.hasGear();

            if (hasBooks && !hasGear) {

                setButtonsTooltip(Tooltip.create(Component.translatable("easyenchant.button.select_gear")));
            } else if (!hasBooks && hasGear) {
                
                setButtonsTooltip(Tooltip.create(Component.translatable("easyenchant.button.select_books")));
            } else {
                
                setButtonsTooltip(Tooltip.create(Component.translatable("easyenchant.button.select_items")));
            }
        }
    }

    public void handleEnchanterInitiation() {
        statusManager.updateStatusTo(Component.translatable("easyenchant.status.running_optimizer").getString(), TextColor.PROCESSING, 100000);
        setButtonsTooltip(Tooltip.create(Component.empty()));
        setButtonsAvailability(false);
    }

    private void tryOptimize() {

        //long t0 = System.nanoTime();
        plan = EnchantOptimizer.optimize(selectedItemManager, modeButton.getValue(), cfg.allowRenaming);
        //long nano = System.nanoTime() - t0;
        //System.out.println("optimize() took " + nano + " nano");

        if (!planReady()) {
            setButtonsAvailability(false);
            if (selectedItemManager.isValid()) setButtonsTooltip(Tooltip.create(Component.translatable("easyenchant.button.too_expensive"))); // plan is null but selected items valid if plan was deemed impossible without triggering "Too expensive!"
        } else {
            if (plan.lowestTotalXP == plan.highestTotalXP) enchantFullyButton.setTooltip(Tooltip.create(Component.translatable("easyenchant.button.enchant_for_levels_and_xp", plan.totalLevels, plan.lowestTotalXP)));
            else enchantFullyButton.setTooltip(Tooltip.create(Component.translatable("easyenchant.button.enchant_for_levels_and_xp_range", plan.totalLevels, plan.lowestTotalXP, plan.highestTotalXP)));

            Instruction first = plan.instructions.getFirst();
            enchantStepButton.setTooltip(Tooltip.create(Component.translatable("easyenchant.button.enchant_for_levels_and_xp", first.levelCost, first.xpCost)));

        }
    }


    @Override
    public void removed() {
        super.removed(); // Also need to check if its last instruction, otherwise reselects completely done product
        if (enchanting() && !enchanter.runningLastStep && outputSave == menu.getCarried()) {
            // MinecraftClient.getInstance().player.sendMessage(Text.of("Anvil frickin broke!"), false);
            
            // Hotbar: [30, size)
            for (int i = 30; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                if (slot.getItem().isEmpty()) {
                    selectedItemManager.tryAddItem(slot.index, outputSave);
                    return; // done
                }
            }

            // Inventory: [3, 30), ignore slot 0, 1, 2 as anvil slots
            for (int i = 3; i < 30; i++) {
                Slot slot = menu.slots.get(i);
                if (slot.getItem().isEmpty()) {
                    selectedItemManager.tryAddItem(slot.index, outputSave);
                    return;
                }
            }
        }
    }

    
    
	@Inject(method = "containerTick", at = @At("TAIL"))
    public void handledScreenTick(CallbackInfo ci) {

        if (enchanting()) updateEnchanter();
        else {
            selectedItemManager.checkContradictions(menu);
            if (planReady()) updateButtons();
        }

    }

    public boolean enchanting() {
        return enchanter != null;
    }

    public boolean planReady() {
        return plan != null;
    }

	@Inject(method = "extractBackground", at = @At("TAIL"))
    public void drawForeground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a, CallbackInfo ci) {
        
        for (int id : selectedItemManager.getItemIds()) {
            renderHighlight(graphics, menu.getSlot(id));
        }

        // Render status message
        if (statusManager != null) statusManager.tryRender(graphics);
    }


    private void renderHighlight(GuiGraphicsExtractor ctx, Slot slot) {
        ctx.fill(slot.x + leftPos, slot.y + topPos, slot.x + leftPos + 16, slot.y + topPos + 16, cfg.selectedItemFillColor);
        ctx.outline(slot.x + leftPos, slot.y + topPos, 16, 16, cfg.selectedItemBorderColor);
        
    }

    public void setButtonsTooltip(Tooltip tooltip) {
        enchantFullyButton.setTooltip(tooltip);
        enchantStepButton.setTooltip(tooltip);
    }

    public void updateEnchanter() {

        if (enchanter != null) { // If enchanter exists, it means we are running enchanting automation
            AutoEnchanter.TickStatus status = enchanter.tick();
            // System.out.println("Enchanter status: " + status);

            switch (status) {
                case OBSTRUCTION_ERROR, TIMEOUT_ERROR, FULLY_DONE:
                    setButtonsTooltip(Tooltip.create(Component.translatable("easyenchant.button.select_items")));
                    setButtonsAvailability(false);
                    
                    switch (status) {
                        case OBSTRUCTION_ERROR: statusManager.updateStatusTo(Component.translatable("easyenchant.status.item_obstruction").getString(), TextColor.ERROR, 5000);
                        case TIMEOUT_ERROR: statusManager.updateStatusTo(Component.translatable("easyenchant.status.timeout").getString(), TextColor.ERROR, 5000);
                        case FULLY_DONE: statusManager.updateStatusTo(Component.translatable("easyenchant.status.enchanting_complete").getString(), TextColor.SUCCESS);
                        default: break;
                    }

                    enchanter = null;
                    return;
                case LEFT_INPUT_INSERTED, RIGHT_INPUT_INSERTED:
                    selectedItemManager.checkContradictions(menu);
                    break;
                case OUTPUT_PICKED_UP:
                    outputSave = menu.getCarried();
                    break;
                case STEP_DONE:
                    selectedItemManager.updateUpgradedItem(enchanter.currentInstruction.leftId, menu.getSlot(enchanter.currentInstruction.leftId).getItem());
                    if (enchanter.isStepMode) {
                        statusManager.updateStatusTo(Component.translatable("easyenchant.status.step_complete").getString(), TextColor.SUCCESS);
                            
                        tryOptimize(); // plan changed, update lvl tooltips etc
                        enchanter = null; // step mode, auto enchanting should be marked done even if enchanting not fully done
                        return;
                    }
                    break;
                default:
                    break;
            }
            
            if (enchanter.instant) updateEnchanter();
        }

    }

    public void updateButtons() {

        LocalPlayer player = Minecraft.getInstance().player;
    
        boolean inCreative = player.getAbilities().instabuild;

        int playerLevels = player.experienceLevel;

        enchantFullyButton.active = plan.totalLevels <= playerLevels || inCreative;
        enchantStepButton.active = plan.instructions.getFirst().levelCost <= playerLevels || inCreative;

    }

}