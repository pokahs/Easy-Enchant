package io.github.pokahs.easyenchant;

import java.util.Iterator;
import java.util.List;

import io.github.pokahs.easyenchant.EnchantOptimizer.Instruction;
//import me.shedaniel.autoconfig.AutoConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

public class AutoEnchanter {

    public final Iterator<Instruction> instructions;
    public Instruction currentInstruction;

    final int LEFT_INPUT_SLOT = 0;
    final int RIGHT_INPUT_SLOT = 1;
    final int OUTPUT_SLOT = 2;

    private enum Action {
        INPUT_LEFT_ITEM,
        INPUT_RIGHT_ITEM,
        PICKUP_OUTPUT_ITEM,
        PLACE_OUTPUT_ITEM
    }

    public enum TickStatus {
        LEFT_INPUT_INSERTED,
        RIGHT_INPUT_INSERTED,
        OUTPUT_PICKED_UP,
        STEP_DONE,
        FULLY_DONE,
        COOLING,
        OBSTRUCTION_ERROR,
        TIMEOUT_ERROR
    }

    public int packetCooldownCountdown;
    public int packetTickDelay;
    
    public int tickTimeoutDuration = 60;
    public int tickTimeoutCountdown = tickTimeoutDuration;

    public boolean instant;
    public boolean allowRenaming;
    public boolean isStepMode = false;
    public boolean runningLastStep;

    private AnvilScreenHandler handler;

    public Action currentAction = Action.INPUT_LEFT_ITEM;

    public AutoEnchanter(AnvilScreenHandler handler, List<Instruction> instructions, int packetTickDelay, boolean allowRenaming, boolean instant) {
        this.handler = handler;
        this.instructions = instructions == null ? null : instructions.iterator();
        this.packetTickDelay = packetTickDelay;
        this.allowRenaming = allowRenaming;
        this.instant = instant;
    }

    public AutoEnchanter(AnvilScreenHandler handler, Instruction instruction, int packetTickDelay, boolean allowRenaming, boolean instant, boolean runningLastStep) {
        this(handler, List.of(instruction), packetTickDelay, allowRenaming, instant);
        this.isStepMode = true;
        this.runningLastStep = runningLastStep;
    }

    public TickStatus tick() {

        if (packetCooldownCountdown > 0) {
            packetCooldownCountdown--;
            return TickStatus.COOLING;
        }

        packetCooldownCountdown = packetTickDelay;

        switch(currentAction) {
            case INPUT_LEFT_ITEM:
            
                currentInstruction = instructions.next();
                runningLastStep = isStepMode ? runningLastStep : !instructions.hasNext();

                // Before placing, clear input slot if occupied
                if (isSlotOccupied(LEFT_INPUT_SLOT)) {
                    return TickStatus.OBSTRUCTION_ERROR;
                } else {
                    clickSlot(currentInstruction.leftId, SlotActionType.QUICK_MOVE);
                    currentAction = Action.INPUT_RIGHT_ITEM;
                    return TickStatus.LEFT_INPUT_INSERTED;
                }

            case INPUT_RIGHT_ITEM:

                // Before placing, clear input slot if occupied
                if (isSlotOccupied(RIGHT_INPUT_SLOT)) {
                    return TickStatus.OBSTRUCTION_ERROR;
                } else {
                    clickSlot(currentInstruction.rightId, SlotActionType.QUICK_MOVE);
                    currentAction = Action.PICKUP_OUTPUT_ITEM;
                    return TickStatus.RIGHT_INPUT_INSERTED;
                }

            case PICKUP_OUTPUT_ITEM:
                // Before placing, clear input slot if occupied
                if (isSlotOccupied(OUTPUT_SLOT)) {
                    tickTimeoutCountdown = tickTimeoutDuration; // Reset timeout countdown
                    
                    if (allowRenaming && runningLastStep) return TickStatus.FULLY_DONE; // Stop here to allow renaming

                    clickSlot(OUTPUT_SLOT, SlotActionType.PICKUP);
                    currentAction = Action.PLACE_OUTPUT_ITEM;
                    return TickStatus.OUTPUT_PICKED_UP;
                } else {
                    tickTimeoutCountdown--;
                    if (tickTimeoutCountdown == 0) return TickStatus.TIMEOUT_ERROR;
                    else return TickStatus.COOLING;
                }

            case PLACE_OUTPUT_ITEM:

                if (isSlotOccupied(LEFT_INPUT_SLOT)) {
                    return TickStatus.OBSTRUCTION_ERROR;
                } else {   
                    clickSlot(currentInstruction.leftId, SlotActionType.PICKUP);
                }


                if (runningLastStep) return TickStatus.FULLY_DONE;

                currentAction = Action.INPUT_LEFT_ITEM; // doesnt do anything if in stepmode
                return TickStatus.STEP_DONE;

            default:
                return TickStatus.TIMEOUT_ERROR; // should not happen
        }

    }

    private void clickSlot(int slotId, SlotActionType actionType) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.interactionManager.clickSlot(handler.syncId, slotId, 0, actionType, client.player);
    }

    private boolean isSlotOccupied(int slotId) {
        ItemStack stack = handler.getSlot(slotId).getStack();
        return !stack.isEmpty();
    }
}