package io.github.pokahs.easyenchant;

import java.util.*;
import io.github.pokahs.easyenchant.SelectedItemManager.EnchantableItem.LeveledEnchant;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

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
        public static AddResult fail(Component reason) { return new AddResult(false, reason.getString()); }
    }

    public sealed static class EnchantableItem permits EnchantedBook, Gear {

        public record LeveledEnchant(Holder<Enchantment> enchant, int level) {
            public String toString() {
                return Enchantment.getFullname(enchant, level).getString();
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

            repairCost = stack.getOrDefault(DataComponents.REPAIR_COST, 0);
        }

        public EnchantableItem(boolean isGear, int id, ItemStack stack, int repairCost, ArrayList<LeveledEnchant> enchants) {
            this.isGear = isGear;
            this.id = id;
            this.stack = stack;
            this.repairCost = repairCost;
            this.enchants = enchants;
        }

        public String toString() {
            return (isGear ? stack.getHoverName().getString() + " " : "Book ") + enchants.toString();
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

            ItemEnchantments components = stack.getEnchantments();

            for (Holder<Enchantment> enchantEntry : components.keySet()) {
                enchants.add(new LeveledEnchant(enchantEntry, components.getLevel(enchantEntry)));
            }

        }

        // Note this is like an unsafe implemenation
        public Gear(int id, ItemStack stack, int repairCost, ArrayList<LeveledEnchant> enchants) {
            super(true, id, stack, repairCost, enchants);
        }

        public boolean enchantAppliesToGear(LeveledEnchant enchant) {
            return enchant.enchant().value().canEnchant(stack);
        }

    }


    public non-sealed static class EnchantedBook extends EnchantableItem {

        public EnchantedBook(int id, ItemStack stack) {
            super(id, stack, false);

            ItemEnchantments stored =
                stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);

            for (Object2IntMap.Entry<Holder<Enchantment>> e : stored.entrySet()) {
                this.enchants.add(new LeveledEnchant(e.getKey(), e.getIntValue()));
            }
            
        }

        // Note this is like an unsafe implemenation
        public EnchantedBook(int id, ItemStack stack, int repairCost, ArrayList<LeveledEnchant> enchants) {
            super(false, id, stack, repairCost, enchants);
        }

    }


    public final HashMap<Integer, EnchantedBook> books = new HashMap<Integer, EnchantedBook>();

    public Gear gear;



    /** Check if an itemstack is a gear */
    public static boolean isGear(ItemStack stack) {
        return !isBook(stack) && (stack.isEnchantable() || stack.getEnchantments().size() > 0);
    }

    
    /** Check if an itemstack is an enchanted book */
    public static boolean isBook(ItemStack stack) {
        return stack.is(Items.ENCHANTED_BOOK);
    }

    private AddResult tryCombineItemsOneDirection(List<EnchantableItem> newItems) {

        Map<LeveledEnchant, Set<Integer>> responsibilityEnchantMap = new HashMap<>();

        Optional<Gear> gearItem = newItems.stream().filter(i -> i.isGear).findFirst().map(i -> (Gear) i);

        for (EnchantableItem newItem : newItems) {
        
            boolean usefulEnchantPresent = false;
            Optional<LeveledEnchant> conflictingEnchant = Optional.empty();

            for (EnchantableItem.LeveledEnchant newEnchant : newItem.enchants) {

                if (gearItem.isPresent() && !gearItem.get().enchantAppliesToGear(newEnchant)) {
                    continue; // if enchant doesn't apply to gear, just ignore it in the combining logic. This allows for books with some enchants that dont apply to the gear, as long as they have at least one that does apply.
                }
                
                boolean newEnchantIsAccNew = true;

                Iterator<LeveledEnchant> alreadyAddedEnchantsIterator = responsibilityEnchantMap.keySet().iterator();
                while (alreadyAddedEnchantsIterator.hasNext()) {

                    LeveledEnchant alreadyAddedEnchant = alreadyAddedEnchantsIterator.next();

                    if (newEnchant.enchant.equals(alreadyAddedEnchant.enchant)) {

                        newEnchantIsAccNew = false;
                        
                        if (newEnchant.level != alreadyAddedEnchant.level) {
                            if (newEnchant.level > alreadyAddedEnchant.level) { // new enchant improves existing enchant
                                alreadyAddedEnchantsIterator.remove();
                                
                                newEnchantIsAccNew = true;
                            }
                        } else if (newEnchant.level != newEnchant.enchant.value().getMaxLevel() && responsibilityEnchantMap.get(alreadyAddedEnchant).size() == 1) {

                            newEnchantIsAccNew = true; // new enchant is same level as existing enchant, but they can be combined to get a higher level enchant.

                        } else responsibilityEnchantMap.get(newEnchant).add(newItem.id); // not a new enchant, but we should note that this book does also apply this max enchant

                        break;
                    } else if (!Enchantment.areCompatible(newEnchant.enchant, alreadyAddedEnchant.enchant)) {
                        newEnchantIsAccNew = false;
                        conflictingEnchant = Optional.of(alreadyAddedEnchant);
                    }
                }

                if (newEnchantIsAccNew) {
                    responsibilityEnchantMap.putIfAbsent(newEnchant, new HashSet<Integer>());
                    responsibilityEnchantMap.get(newEnchant).add(newItem.id);
                }
                
                usefulEnchantPresent = usefulEnchantPresent || newEnchantIsAccNew;
            }

            if (newItem.enchants.isEmpty()) continue; // allow adding items with no enchants, just ignore them in the combining logic. This allows for gear with no enchants.

            if (!usefulEnchantPresent) {
                if (conflictingEnchant.isPresent()) return AddResult.fail(Component.translatable("easyenchant.fail.conflicting_enchants", newItem, conflictingEnchant.get()));
                else return AddResult.fail(Component.translatable("easyenchant.fail.no_useful_enchants", newItem));
            }

        }
        
        return AddResult.pass();
    }

    
    public AddResult tryCombineBooks() {

        List<EnchantableItem> bookList = List.copyOf(books.values());

        AddResult result = tryCombineItemsOneDirection(bookList);
        if (!result.successful) return result;

        return tryCombineItemsOneDirection(bookList.reversed());
    }


    public AddResult tryCombineGearWithBooks() {

        List<EnchantableItem> items = new ArrayList<>();

        items.add(gear);
        items.addAll(books.values());

        AddResult result = tryCombineItemsOneDirection(items);
        if (!result.successful) return result;

        items.clear();
        items.addAll(books.values());
        items = items.reversed();
        items.addFirst(gear);

        return tryCombineItemsOneDirection(items);
        
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
        if (hasGear()) return AddResult.fail(Component.translatable("easyenchant.fail.gear_already_set"));

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

    public void checkContradictions(AnvilMenu handler) {

        ArrayList<Integer> toRemove = new ArrayList<>();

        for (int bookId : books.keySet()) {
            ItemStack newStack = handler.getSlot(bookId).getItem();
            if (newStack.isEmpty() || !ItemStack.isSameItemSameComponents(newStack, books.get(bookId).stack)) toRemove.add(bookId);
        }

        for (int bookId : toRemove) removeBook(bookId);


        if (gear != null) {
            ItemStack newStack = handler.getSlot(gear.id).getItem();
            if (newStack.isEmpty() || !ItemStack.isSameItemSameComponents(newStack, gear.stack)) removeGear();
        }
        
    }


    public AddResult tryAddItem(int id, ItemStack stack) {

        // If this is a book, try to add it as a book
        if (isBook(stack)) return tryAddBook(id, stack);
        else if (isGear(stack)) return tryAddGear(id, stack);

        return AddResult.fail(Component.translatable("easyenchant.fail.item_not_valid"));
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