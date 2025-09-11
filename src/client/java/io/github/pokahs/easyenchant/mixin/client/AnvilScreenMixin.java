package io.github.pokahs.easyenchant.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.screen.ingame.ForgingScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin extends ForgingScreen<AnvilScreenHandler> implements EasyEnchantAnvil {

    
    ModConfig cfg = AutoConfig.getConfigHolder(ModConfig.class).getConfig();
    
    @Shadow @Final static private Identifier TEXTURE;

    public AnvilScreenMixin(AnvilScreenHandler handler, PlayerInventory inventory, Text title) {
      super(handler, inventory, title, TEXTURE);
    }
	

	private ButtonWidget enchantFullyButton;
	private ButtonWidget enchantStepButton;

    private final int BUTTON_WIDTH = backgroundWidth / 2;
    private final int BUTTON_HEIGHT = 20;
    private final int STATUS_PADDING = 5;


    private CyclingButtonWidget<Mode> modeButton;

    private StatusManager statusManager;
    
    private SelectedItemManager selectedItemManager = SelectedItemManager.getInstance();
    
    private Plan plan = null; // null when no valid plan

    private AutoEnchanter enchanter = null; // null when idle

    @Inject(method = "setup", at = @At("TAIL"))
    protected void setup(CallbackInfo ci) {
        
        cfg = AutoConfig.getConfigHolder(ModConfig.class).getConfig();

        enchantFullyButton = addDrawableChild(ButtonWidget.builder(Text.translatable("easyenchant.button.enchant_fully"), b -> {
            enchanter = new AutoEnchanter(plan.instructions, cfg.packetTickDelay, cfg.allowRenaming, cfg.instantEnchant);
            plan = null;
            handleEnchanterInitiation();
        })
            .dimensions(x, y + backgroundHeight, BUTTON_WIDTH, BUTTON_HEIGHT)
            .build());


        enchantStepButton = addDrawableChild(ButtonWidget.builder(Text.translatable("easyenchant.button.enchant_step"), b -> {
            boolean noMoreInstructions = plan.instructions.size() == 1;
            enchanter = new AutoEnchanter(plan.instructions.getFirst(), cfg.packetTickDelay, cfg.allowRenaming, cfg.instantEnchant, noMoreInstructions);
            plan = noMoreInstructions ? null : new Plan(plan.instructions.subList(1, plan.instructions.size()));
            handleEnchanterInitiation();
        })
            .dimensions(x + BUTTON_WIDTH, y + backgroundHeight, BUTTON_WIDTH, BUTTON_HEIGHT)
            .build());
        

        modeButton = addDrawableChild(
            CyclingButtonWidget.<Mode>builder((Mode mode) ->
                    mode == Mode.LEVELS ? Text.translatable("easyenchant.button.levels") : Text.translatable("easyenchant.button.xp"))
                .values(Mode.LEVELS, Mode.XP)
                .initially(cfg.mode)
                .omitKeyText()
                .build(
                    x, y + backgroundHeight + BUTTON_HEIGHT, BUTTON_WIDTH, BUTTON_HEIGHT,
                    Text.empty(),
                    (btn, mode) -> {
                        tryOptimize();
                        cfg.mode = mode;
                    }
                )
            );
        

        addDrawableChild(ButtonWidget.builder(Text.translatable("easyenchant.button.config"), b -> {
            MinecraftClient.getInstance().setScreen(AutoConfig.getConfigScreen(ModConfig.class, this).get());
        })
            .dimensions(x + BUTTON_WIDTH, y + backgroundHeight + BUTTON_HEIGHT, BUTTON_WIDTH, BUTTON_HEIGHT)
            .build());

        statusManager = new StatusManager(textRenderer, x + backgroundWidth / 2, y + backgroundHeight + 2 * BUTTON_HEIGHT + STATUS_PADDING, backgroundWidth, MinecraftClient.getInstance().getWindow().getScaledWidth());
        
        handleItemSelectionChange(); // Handles if differences in saved manager vs current inventory

        // statusManager.updateStatusTo("Screen loaded!", TextColor.SUCCESS);

        // Intro message if manager empty, guides user how to start
        if (!selectedItemManager.hasGear() && !selectedItemManager.hasBooks()) statusManager.updateStatusTo(Text.translatable("easyenchant.status.select_by_pressing", EasyEnchantClient.SELECT_ITEM_KEY.getBoundKeyLocalizedText()).getString(), TextColor.DEFAULT, 5000);

    }

    private void setButtonsAvailability(boolean available) {
        // Note unless enchanter running than they might get enabled again instantly
        enchantFullyButton.active = available;
        enchantStepButton.active = available;
    }


    public void toggleBagItem(double mouseX, double mouseY) {

        // Do not toggle anything while automation is running
        if (enchanting()) return;

        // If focused on text field, ignore key trigger
        if (this.getFocused() instanceof TextFieldWidget tf && tf.isActive()) return;

        // Grab slot at mouse position
        Slot hovered = ((HandledScreenInvoker) this)
                .easyEnchant$invokeGetSlotAt(mouseX, mouseY);

        // If not actually a slot or not an inventory slot, ignore
        if (hovered == null) return;
        if (!(hovered.inventory instanceof PlayerInventory)) return;

        // Get id (id = index number of slot in inventory), get stack, if empty, ignore
        int id = hovered.id;
        ItemStack stack = hovered.getStack();
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

                setButtonsTooltip(Tooltip.of(Text.translatable("easyenchant.button.select_gear")));
            } else if (!hasBooks && hasGear) {
                
                setButtonsTooltip(Tooltip.of(Text.translatable("easyenchant.button.select_books")));
            } else {
                
                setButtonsTooltip(Tooltip.of(Text.translatable("easyenchant.button.select_items")));
            }
        }
    }

    public void handleEnchanterInitiation() {
        statusManager.updateStatusTo(Text.translatable("easyenchant.status.running_optimizer").getString(), TextColor.PROCESSING, 100000);
        setButtonsTooltip(Tooltip.of(Text.empty()));
        setButtonsAvailability(false);
    }

    private void tryOptimize() {

        //long t0 = System.nanoTime();
        plan = EnchantOptimizer.optimize(selectedItemManager, modeButton.getValue(), cfg.allowRenaming);
        //long nano = System.nanoTime() - t0;
        //System.out.println("optimize() took " + nano + " nano");

        if (!planReady()) {
            setButtonsAvailability(false);
            if (selectedItemManager.isValid()) setButtonsTooltip(Tooltip.of(Text.translatable("easyenchant.button.too_expensive"))); // plan is null but selected items valid if plan was deemed impossible without triggering "Too expensive!"
        } else {
            if (plan.lowestTotalXP == plan.highestTotalXP) enchantFullyButton.setTooltip(Tooltip.of(Text.translatable("easyenchant.button.enchant_for_levels_and_xp", plan.totalLevels, plan.lowestTotalXP)));
            else enchantFullyButton.setTooltip(Tooltip.of(Text.translatable("easyenchant.button.enchant_for_levels_and_xp_range", plan.totalLevels, plan.lowestTotalXP, plan.highestTotalXP)));

            Instruction first = plan.instructions.getFirst();
            enchantStepButton.setTooltip(Tooltip.of(Text.translatable("easyenchant.button.enchant_for_levels_and_xp", first.levelCost, first.xpCost)));

        }
    }

    
    @Override
    public void handledScreenTick() {
        super.handledScreenTick();

        if (enchanting()) updateEnchanter();
        else {
            selectedItemManager.checkContradictions(handler);
            if (planReady()) updateButtons();
        }

    }

    public boolean enchanting() {
        return enchanter != null;
    }

    public boolean planReady() {
        return plan != null;
    }

	@Inject(method = "renderForeground", at = @At("HEAD"))
    public void renderForeground(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        
        for (int id : selectedItemManager.getItemIds()) {
            renderHighlight(ctx, handler.getSlot(id));
        }

        // Render status message
        if (statusManager != null) statusManager.tryRender(ctx);
    }

    private void renderHighlight(DrawContext ctx, Slot slot) {
        int sx = this.x + slot.x, sy = this.y + slot.y;
        ctx.fill(sx, sy, sx + 16, sy + 16, cfg.selectedItemFillColor);
        ctx.drawBorder(sx, sy, 16, 16, cfg.selectedItemBorderColor);
    }

    public void setButtonsTooltip(Tooltip tooltip) {
        enchantFullyButton.setTooltip(tooltip);
        enchantStepButton.setTooltip(tooltip);
    }

    public void updateEnchanter() {

        if (enchanter != null) { // If enchanter exists, it means we are running enchanting automation
            AutoEnchanter.TickStatus status = enchanter.tick();
            System.out.println("Enchanter status: " + status);

            switch (status) {
                case OBSTRUCTION_ERROR, TIMEOUT_ERROR, FULLY_DONE:
                    setButtonsTooltip(Tooltip.of(Text.translatable("easyenchant.button.select_items")));
                    setButtonsAvailability(false);
                    
                    switch (status) {
                        case OBSTRUCTION_ERROR: statusManager.updateStatusTo(Text.translatable("easyenchant.status.item_obstruction").getString(), TextColor.ERROR, 5000);
                        case TIMEOUT_ERROR: statusManager.updateStatusTo(Text.translatable("easyenchant.status.timeout").getString(), TextColor.ERROR, 5000);
                        case FULLY_DONE: statusManager.updateStatusTo(Text.translatable("easyenchant.status.enchanting_complete").getString(), TextColor.SUCCESS);
                        default: break;
                    }

                    enchanter = null;
                    return;
                case LEFT_INPUT_INSERTED, RIGHT_INPUT_INSERTED:
                    selectedItemManager.checkContradictions(handler);
                    break;
                case STEP_DONE:
                    selectedItemManager.updateUpgradedItem(enchanter.currentInstruction.leftId, handler.getSlot(enchanter.currentInstruction.leftId).getStack());
                    if (enchanter.isStepMode) {
                        statusManager.updateStatusTo(Text.translatable("easyenchant.status.step_complete").getString(), TextColor.SUCCESS);
                            
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

        ClientPlayerEntity player = MinecraftClient.getInstance().player;
    
        boolean inCreative = player.getAbilities().creativeMode;

        int playerLevels = player.experienceLevel;

        enchantFullyButton.active = plan.totalLevels <= playerLevels || inCreative;
        enchantStepButton.active = plan.instructions.getFirst().levelCost <= playerLevels || inCreative;

    }

}