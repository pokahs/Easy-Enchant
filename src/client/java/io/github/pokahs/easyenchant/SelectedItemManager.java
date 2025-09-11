package io.github.pokahs.easyenchant;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.text.Text;

import java.util.*;

import io.github.pokahs.easyenchant.SelectedItemManager.EnchantableItem.LeveledEnchant;
import it.unimi.dsi.fastutil.objects.Object2IntMap;

public final class SelectedItemManager {

    public static SelectedItemManager SAVED_INSTANCE;

    /** Returns the saved instance or creates one if none exists */
    public static SelectedItemManager getInstance() {
        if (SAVED_INSTANCE == null) SAVED_INSTANCE = new SelectedItemManager();
        return SAVED_INSTANCE;
    }

    private SelectedItemManager() {}

    public static void clearSave() {
        SAVED_INSTANCE = null;
    }

    // ===== Public result wrapper =====
    public record AddResult(boolean successful, String failReason) {
        public static AddResult pass() { return new AddResult(true, null); }
        public static AddResult fail(String reason) { return new AddResult(false, reason); }
        public static AddResult fail(Text reason) { return new AddResult(false, reason.getString()); }
    }

    public sealed static class EnchantableItem permits EnchantedBook, Gear {

        public record LeveledEnchant(RegistryEntry<Enchantment> enchant, int level) {
            public String toString() {
                return Enchantment.getName(enchant, level).getString();
            }
        }

        public final int id;
        public final ItemStack stack;
        public final boolean isGear;

        public Integer repairCost;
        
        public ArrayList<LeveledEnchant> enchants = new ArrayList<LeveledEnchant>();
        

        public EnchantableItem(int id, ItemStack stack, boolean isGear) {
            this.id = id;
            this.stack = stack; // .copy()
            this.isGear = isGear;

            repairCost = stack.getOrDefault(DataComponentTypes.REPAIR_COST, 0);
        }

        public EnchantableItem(boolean isGear, int id, ItemStack stack, int repairCost, ArrayList<LeveledEnchant> enchants) {
            this.isGear = isGear;
            this.id = id;
            this.stack = stack;
            this.repairCost = repairCost;
            this.enchants = enchants;
        }

        // prob should del
        @Override
        public int hashCode() {
            // Combine id, repair cost, enchants, and levels into one hash
            return Objects.hash(
                id,
                stack,
                repairCost,
                enchants
            );
        }
    }

    public non-sealed static class Gear extends EnchantableItem {

        public Gear(int id, ItemStack stack) {
            super(id, stack, true);

            ItemEnchantmentsComponent components = stack.getEnchantments();

            for (RegistryEntry<Enchantment> enchantEntry : components.getEnchantments()) {
                enchants.add(new LeveledEnchant(enchantEntry, components.getLevel(enchantEntry)));
            }

        }

        // Note this is like an unsafe implemenation
        public Gear(int id, ItemStack stack, int repairCost, ArrayList<LeveledEnchant> enchants) {
            super(true, id, stack, repairCost, enchants);
        }
    }


    public non-sealed static class EnchantedBook extends EnchantableItem {

        public EnchantedBook(int id, ItemStack stack) {
            super(id, stack, false);

            ItemEnchantmentsComponent stored =
                stack.getOrDefault(DataComponentTypes.STORED_ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);

            for (Object2IntMap.Entry<RegistryEntry<Enchantment>> e : stored.getEnchantmentEntries()) {
                this.enchants.add(new LeveledEnchant(e.getKey(), e.getIntValue()));
            }
            
        }

        // Note this is like an unsafe implemenation
        public EnchantedBook(int id, ItemStack stack, int repairCost, ArrayList<LeveledEnchant> enchants) {
            super(false, id, stack, repairCost, enchants);
        }

        public EnchantedBook culledForGear(Gear gear) {
            ArrayList<LeveledEnchant> filtered = new ArrayList<>();
            for (LeveledEnchant le : this.enchants) {
                if (le.enchant().value().isAcceptableItem(gear.stack)) {
                    filtered.add(le);
                }
            }
            return new EnchantedBook(this.id, this.stack, this.repairCost, filtered);
        }
    }


    public final HashMap<Integer, EnchantedBook> books = new HashMap<Integer, EnchantedBook>();

    public Gear gear;



    /** Check if an itemstack is a gear */
    public static boolean isGear(ItemStack stack) {
        return !isBook(stack) && (stack.isEnchantable() || stack.getEnchantments().getSize() > 0);
    }

    
    /** Check if an itemstack is an enchanted book */
    public static boolean isBook(ItemStack stack) {
        return stack.isOf(Items.ENCHANTED_BOOK);
    }


    private AddResult tryCombine(Map<LeveledEnchant, Set<Integer>> responsibilityEnchantMap, Collection<LeveledEnchant> enchants, int itemId) {

        System.out.println("Now tryna add: " + enchants);

        boolean usefulEnchantPresent = false;

        for (EnchantableItem.LeveledEnchant enchant : enchants) {
            
            boolean enchantIsNew = true;


            Iterator<LeveledEnchant> iterator = responsibilityEnchantMap.keySet().iterator();
            while (iterator.hasNext()) {

                LeveledEnchant alreadyAddedEnchant = iterator.next();

                if (enchant.enchant.equals(alreadyAddedEnchant.enchant)) {

                    System.out.println(enchant + " does EQUAL to " + alreadyAddedEnchant);

                    enchantIsNew = false;
                    
                    if (enchant.level != alreadyAddedEnchant.level) {
                        if (enchant.level > alreadyAddedEnchant.level) { // new enchant improves existing enchant
                            iterator.remove();
                            enchantIsNew = true;
                        }
                    }
                    else if (enchant.level != enchant.enchant.value().getMaxLevel()) {

                        // Allow adding a second of the same enchant at same lvl
                        if  (responsibilityEnchantMap.get(alreadyAddedEnchant).size() == 1) enchantIsNew = true;
                        else return AddResult.fail(Text.translatable("easyenchant.fail.cannot_handle_more_than_two_of_same_enchant", enchant));

                    } else responsibilityEnchantMap.get(enchant).add(itemId); // not a new enchant, but we should note that this book does also apply this max enchant

                    break;
                } else System.out.println(enchant + " does NOTTT equal to " + alreadyAddedEnchant);
            }

            if (enchantIsNew) {
                responsibilityEnchantMap.putIfAbsent(enchant, new HashSet<Integer>());
                responsibilityEnchantMap.get(enchant).add(itemId);
            }
            
            usefulEnchantPresent = usefulEnchantPresent || enchantIsNew;
        }


        if (!usefulEnchantPresent) return AddResult.fail(Text.translatable("easyenchant.fail.no_useful_enchants", books.get(itemId).enchants));
        
        return AddResult.pass();
    }

    
    public AddResult tryCombineBooks() {
        
        Map<LeveledEnchant, Set<Integer>> responsibilityEnchantMap = new HashMap<>();

        for (EnchantedBook book : books.values()) {
            AddResult result = tryCombine(responsibilityEnchantMap, book.enchants, book.id);
            if (!result.successful) return result;
        }

        // Some books might be useless if ltr books added had better lvled enchants, check for this
        for (EnchantedBook book : books.values()) {
            boolean useful = false;
            for (LeveledEnchant e : book.enchants) {
                Set<Integer> responsibleItemIds = responsibilityEnchantMap.get(e);
                if (responsibleItemIds != null && responsibleItemIds.contains(book.id) && (responsibleItemIds.size() == 1 || e.enchant.value().getMaxLevel() > e.level)) {
                    useful = true;
                    break;
                }
            }
            if (!useful) {
                return AddResult.fail(Text.translatable("easyenchant.fail.no_useful_enchants", book.enchants));
            }
        }

        return AddResult.pass(); // no gear, no need to check if enchants are valid for gear
    }


    public AddResult tryCombineGearWithBooks() {

        Map<Integer, EnchantedBook> culledBooks = new HashMap<>();
        
        for (EnchantedBook book : books.values()) {
            EnchantedBook culledBook = book.culledForGear(gear);
            if (culledBook.enchants.size() == 0) return AddResult.fail(Text.translatable("easyenchant.fail.no_valid_enchants_for_gear", books.get(culledBook.id).enchants, gear.stack.getName()));
            culledBooks.put(culledBook.id, culledBook);
        }
        
        Map<LeveledEnchant, Set<Integer>> responsibilityEnchantMap = new HashMap<>();

        for (LeveledEnchant enchant : gear.enchants) {
            responsibilityEnchantMap.putIfAbsent(enchant, new HashSet<Integer>());
            responsibilityEnchantMap.get(enchant).add(gear.id);
        }


        for (EnchantedBook culledBook : culledBooks.values()) {
            AddResult result = tryCombine(responsibilityEnchantMap, culledBook.enchants, culledBook.id);
            if (!result.successful) return result;
        }

        // Some books might be useless if ltr books added had better lvled enchants, check for this
        for (EnchantedBook culledBook : culledBooks.values()) {
            boolean useful = false;
            for (LeveledEnchant e : culledBook.enchants) {
                Set<Integer> responsibleItemIds = responsibilityEnchantMap.get(e);
                if (responsibleItemIds != null && responsibleItemIds.contains(culledBook.id) && (responsibleItemIds.size() == 1 || e.enchant.value().getMaxLevel() > e.level)) { // See if book is only item responsible for useful enchant
                    useful = true;
                    break;
                }
            }
            if (!useful) {
                return AddResult.fail(Text.translatable("easyenchant.fail.no_useful_enchants", books.get(culledBook.id).enchants));
            }
        }
        

        // Check if any planned enchants has conflicts, if so then yk bad
        for (LeveledEnchant toAddEnchant : responsibilityEnchantMap.keySet()) {
            for (LeveledEnchant gearEnchant : gear.enchants) {
                if (!Enchantment.canBeCombined(toAddEnchant.enchant, gearEnchant.enchant) && !toAddEnchant.enchant.equals(gearEnchant.enchant)) {
                    return AddResult.fail(Text.translatable("easyenchant.fail.cannot_combine_enchants", toAddEnchant, gearEnchant, gear.stack.getName()));
                }
            }
        }
        return AddResult.pass();

    }
    

    public List<Integer> getBookIds() {
        return new ArrayList<>(books.keySet());
    }

    /** Get an list of all ids currently in the manager (gear + books). */
    public List<Integer> getItemIds() {
        List<Integer> list = new ArrayList<>(books.keySet());
        if (hasGear()) list.add(gear.id);
        return list;
    }

    public boolean hasId(int id) {
        if (hasGear()) return books.containsKey(id) || gear.id == id;
        return books.containsKey(id);
    }

    
    public boolean hasGear() { return gear != null; }

    public AddResult tryAddGear(int id, ItemStack stack) {
        if (hasGear()) return AddResult.fail(Text.translatable("easyenchant.fail.gear_already_set"));

        gear = new Gear(id, stack);


        AddResult result = tryCombineGearWithBooks();
        if (!result.successful) removeGear();

        return result;
    }
    
    public void removeGear() {
        gear = null;
    }

    public int getBookCount() {
        return books.size();
    }

    public boolean hasBooks() {
        return getBookCount() > 0;
    }

    public AddResult tryAddBook(int id, ItemStack stack) {

        EnchantedBook book = new EnchantedBook(id, stack);


        books.put(id, book);

        AddResult result = hasGear() ? tryCombineGearWithBooks() : tryCombineBooks();

        if (!result.successful) books.remove(id);
        
        return result;
    }

    /**Remove an enchanted book by id*/
    public void removeBook(int id) {
        books.remove(id);
    }

    public boolean isValid() {
        return hasGear() && hasBooks();
    }

    public void checkContradictions(AnvilScreenHandler handler) {

        ArrayList<Integer> toRemove = new ArrayList<>();

        for (int bookId : books.keySet()) {
            ItemStack newStack = handler.getSlot(bookId).getStack();
            if (newStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(newStack, books.get(bookId).stack)) toRemove.add(bookId);
        }

        for (int bookId : toRemove) removeBook(bookId);


        if (gear != null) {
            ItemStack newStack = handler.getSlot(gear.id).getStack();
            if (newStack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(newStack, gear.stack)) removeGear();
        }
        
    }


    public AddResult tryAddItem(int id, ItemStack stack) {

        // If this is a book, try to add it as a book
        if (isBook(stack)) return tryAddBook(id, stack);
        else if (isGear(stack)) return tryAddGear(id, stack);

        return AddResult.fail(Text.translatable("easyenchant.fail.item_not_valid"));
    }

    public void removeItem(int id) {
        if (books.containsKey(id)) removeBook(id);
        else if (hasGear() && gear.id == id) removeGear();
    }

    public void updateUpgradedItem(int id, ItemStack stack) {
        removeItem(id);
        tryAddItem(id, stack);
    }
}