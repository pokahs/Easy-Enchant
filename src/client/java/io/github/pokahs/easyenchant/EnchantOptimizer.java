package io.github.pokahs.easyenchant;

import net.minecraft.enchantment.Enchantment;

import java.util.*;
import io.github.pokahs.easyenchant.SelectedItemManager.EnchantableItem;
import io.github.pokahs.easyenchant.SelectedItemManager.EnchantableItem.LeveledEnchant;
import io.github.pokahs.easyenchant.ModConfig.Mode;


public final class EnchantOptimizer {

    /** One anvil use. */
    public static final class Instruction {
        public final int leftId;    // item kept in output (stable id = left)
        public final int rightId;   // consumed item
        public final int levelCost;
        public final int xpCost;

        public Instruction(int leftId, int rightId, int levelCost) {
            this.leftId = leftId;
            this.rightId = rightId;
            this.levelCost = levelCost;
            this.xpCost = getXpForLevel(levelCost);
        }
    }

    /** Final plan. */
    public static final class Plan {
        public final List<Instruction> instructions;
        public final int totalLevels;
        public final int lowestTotalXP;
        public final int highestTotalXP;

        public Plan(List<Instruction> instructions) {
            this.instructions = Collections.unmodifiableList(instructions);

            int tempTotalLevels = 0;
            int tempLowestTotalXP = 0;
            for (Instruction instruction : instructions) {
                tempLowestTotalXP += instruction.xpCost;
                tempTotalLevels += instruction.levelCost;
            }
            this.totalLevels = tempTotalLevels;
            this.lowestTotalXP = tempLowestTotalXP;
            this.highestTotalXP = getXpForLevel(tempTotalLevels);
        }
    }

    /** Result of a single merge. */
    public static final class MergeResult {
        public final int stepLevel;          // -1 if invalid
        public final EnchantableItem product; // null if invalid
        MergeResult(int stepLevel, EnchantableItem product) {
            this.stepLevel = stepLevel;
            this.product = product;
        }

        public int getStepCost(Mode mode) {
            return mode == Mode.LEVELS ? stepLevel : getXpForLevel(stepLevel);
        }
    }

    /** Predict exact levels for combining two items. */
    public static int predictLevels(EnchantableItem left, EnchantableItem right) {
        MergeResult mr = merge(left, right);
        return mr.stepLevel;
    }


    public static Plan optimize(SelectedItemManager bag, Mode mode, boolean allowRenaming) {
        if (bag == null || !bag.isValid()) return null;

        final int gearId = bag.gear.id;

        // Collect books
        final List<Integer> ids = bag.getBookIds();
        final int n = ids.size();
        if (n == 0) return null;

        final EnchantableItem[] books = new EnchantableItem[n];
        for (int i = 0; i < n; i++) {
            EnchantableItem b = bag.books.get(ids.get(i));
            if (b == null) return null;
            books[i] = b;
        }

        final int FULL = (1 << n) - 1;

        // Phase A: exact synthesis DP (book+book using merge)
        Synth[] synth = new Synth[1 << n];

        // leaves
        for (int i = 0; i < n; i++) {
            int m = 1 << i;
            EnchantableItem b = books[i];
            synth[m] = new Synth(0, b);
        }

        // composites
        for (int mask = 1; mask <= FULL; mask++) {
            if (Integer.bitCount(mask) == 1) continue;
            Synth best = null;

            for (int left = (mask - 1) & mask; left > 0; left = (left - 1) & mask) {
                int right = mask ^ left;
                if (right == 0) continue;

                Synth SL = synth[left], SR = synth[right];
                if (SL == null || SR == null) continue;

                MergeResult mr = merge(SL.repr, SR.repr);
                if (mr == null) continue;

                int totalInside = SL.combine + SR.combine + mr.getStepCost(mode);
                // product keeps LEFT id and has exact enchants/repair
                EnchantableItem prod = mr.product;

                Synth cand = new Synth(totalInside, prod, left, right);
                if (best == null || cand.combine < best.combine) best = cand;
            }
            synth[mask] = best; // may be null
        }

        // Phase B: exact DP over subsets, state = exact current gear EI
        @SuppressWarnings("unchecked")
        HashMap<EnchantableItem, EntryB>[] dp = new HashMap[1 << n]; // map<gearSignature, EntryB>
        for (int i = 0; i < dp.length; i++) dp[i] = new HashMap<>();

        // initial gear state: keep real stack for acceptability checks
        EnchantableItem gear0 = bag.gear;
        dp[0].put(gear0, new EntryB(0, -1, -1, null, gear0));

        for (int mask = 0; mask <= FULL; mask++) {
            for (Map.Entry<EnchantableItem, EntryB> st : dp[mask].entrySet()) {
                EntryB ent = st.getValue();
                EnchantableItem curGear = ent.gear;

                int remaining = FULL ^ mask;
                for (int S = remaining; S > 0; S = (S - 1) & remaining) {
                    Synth comp = synth[S];
                    if (comp == null) continue;

                    MergeResult mr = merge(curGear, comp.repr);
                    if (mr == null) continue;
                    
                    int newMask = mask | S;

                    // Add one level cost to final step result if allowrenaming
                    if (allowRenaming && newMask == FULL) mr = new MergeResult(mr.stepLevel + 1, mr.product);

                    int newCost = ent.cost + comp.combine + mr.getStepCost(mode);
                    EnchantableItem newGear = mr.product;

                    EntryB prior = dp[newMask].get(newGear);
                    if (prior == null || newCost < prior.cost) {
                        dp[newMask].put(newGear, new EntryB(newCost, mask, S, st.getKey(), newGear));
                    }
                }
            }
        }

        // pick best terminal
        EntryB best = null; EnchantableItem bestGear = null;
        for (Map.Entry<EnchantableItem, EntryB> e : dp[FULL].entrySet()) {
            if (best == null || e.getValue().cost < best.cost) {
                best = e.getValue();
                bestGear = e.getKey();
            }
        }
        if (best == null) return null;

        // reconstruct chosen subsets (reverse chain), then replay merges exactly
        List<Integer> chosenSubsets = new ArrayList<>();
        int m = FULL; EnchantableItem currentGear = bestGear;
        while (m != 0) {
            EntryB cur = dp[m].get(currentGear);
            chosenSubsets.add(cur.chosenSubset);
            currentGear = cur.prevGear;
            m = cur.prevMask;
        }
        Collections.reverse(chosenSubsets);

        // build instructions: for each chosen subset S -> replay its inner merges, then apply to gear
        List<Instruction> instructions = new ArrayList<>();
        EnchantableItem gearState = bag.gear;

        for (int S : chosenSubsets) {
            // inner: book+book merges for S
            replaySynthesis(S, synth, instructions);

            // final: gear + composite via exact merge
            Synth comp = synth[S];
            MergeResult mr = merge(gearState, comp.repr);
            //if (mr == null) return null; // should not happen
            instructions.add(new Instruction(gearId, comp.repr.id, mr.stepLevel + (allowRenaming && S == chosenSubsets.getLast() ? 1 : 0)));
            gearState = mr.product;
        }

        return new Plan(instructions);

    }

    private static MergeResult merge(EnchantableItem left, EnchantableItem right) {
        // Start cost with repair tags
        int levelCost = left.repairCost + right.repairCost;

        // Build product as a shallow synthetic EI (keeps LEFT id)
        ArrayList<LeveledEnchant> newEnchants = new ArrayList<>(left.enchants);

        // Process each right enchant
        rightEnchantIteration:
        for (int i = 0; i < right.enchants.size(); i++) {
            LeveledEnchant newEnchant = right.enchants.get(i);

            // Count conflicts vs current product enchants (vanilla increments +1 for each conflict found)
            int conflicts = 0;

            // If enchant not valid (on gear), simply add one to conflict
            if (left.isGear && !newEnchant.enchant().value().isAcceptableItem(left.stack)) continue;
            else { 
                // Is acceptable enchant / should change the product
                for (LeveledEnchant existing : left.enchants) {

                    if (!Enchantment.canBeCombined(newEnchant.enchant(), existing.enchant())) {
                        if (newEnchant.enchant().equals(existing.enchant())) {

                            // Get future level of enchantment if combining two of same enchantment
                            int futureLevel = existing.level() == newEnchant.level() ? Math.min(existing.enchant().value().getMaxLevel(), existing.level() + 1) : Math.max(existing.level(), newEnchant.level());

                            
                            int weight = (existing.enchant().value().getAnvilCost() + 1 ) / 2;

                            levelCost += weight * futureLevel;

                            newEnchants.set(left.enchants.indexOf(existing), new LeveledEnchant(existing.enchant(), futureLevel));
                            continue rightEnchantIteration;

                        }
                        conflicts++;
                    }
                }
            }


            levelCost += conflicts;

            // If unacceptable OR any conflict, do not apply/upgrade this enchant
            if (conflicts > 0) continue;

            // Cost addend: halved anvil weight because RIGHT is a (stored) book/composite
            int weight = (newEnchant.enchant().value().getAnvilCost() + 1 ) / 2;
            levelCost += weight * newEnchant.level();


            newEnchants.add(newEnchant);

        }

        if (levelCost > 39) return null;


        // Next repair
        int newRepair = nextRepair(left.repairCost, right.repairCost);

        // Product keeps LEFT id; keep gear stack for acceptability on future merges
        EnchantableItem product = new EnchantableItem(
            left.isGear,
            left.id,
            left.stack,        // keep real gear stack if left was gear; ok if null for books
            newRepair,
            newEnchants
        );

        return new MergeResult((int) levelCost, product);
    }

    public static int getXpForLevel(int level) {
        if (level == 0) {
            return 0;
        } else if (level <= 16) {
            return (int) (Math.pow(level, 2) + 6 * level);
        } else if (level <= 31) {
            return (int) (2.5 * Math.pow(level, 2) - 40.5 * level + 360);
        } else {
            return (int) (4.5 * Math.pow(level, 2) - 162.5 * level + 2220);
        }
    }

    /** Replays book+book merges for subset S using exact merge to get per-step levels. */
    private static void replaySynthesis(int mask, Synth[] synth, List<Instruction> out) {
        Synth s = synth[mask];
        if (s == null) throw new IllegalStateException("unreachable subset in reconstruction");
        if (s.leftMask < 0) return; // leaf

        replaySynthesis(s.leftMask, synth, out);
        replaySynthesis(s.rightMask, synth, out);

        Synth SL = synth[s.leftMask];
        Synth SR = synth[s.rightMask];
        MergeResult mr = merge(SL.repr, SR.repr);
        if (mr == null) throw new IllegalStateException("merge failed in reconstruction");
        out.add(new Instruction(SL.repr.id, SR.repr.id, mr.stepLevel));

        // Update SL.repr so deeper siblings see the progressed composite when reconstructing siblings?
        // Not needed here because we re-merge at each node with current children, matching what Phase A used.
    }

    /** Vanilla next repair: (max*2)+1 (clamped). */
    private static int nextRepair(int leftRepair, int rightRepair) {
        return Math.max(leftRepair, rightRepair) * 2 + 1;
    }

    // ---------------- DP structs ----------------

    /** Synthesis DP node (exact composite for a subset of books). */
    private static final class Synth {
        final int combine;            // total inner combine cost (book+book)
        final EnchantableItem repr;   // exact composite EI (keeps LEFT id)
        final int leftMask, rightMask;
        Synth(int combine, EnchantableItem repr) {
            this(combine, repr, -1, -1);
        }
        Synth(int combine, EnchantableItem repr, int leftMask, int rightMask) {
            this.combine = combine; this.repr = repr; this.leftMask = leftMask; this.rightMask = rightMask;
        }
    }

    /** Phase-B DP entry (exact). */
    private static final class EntryB {
        final int cost;
        final int prevMask;
        final int chosenSubset;
        final EnchantableItem prevGear;
        final EnchantableItem gear; // exact gear state at this node

        EntryB(int costSoFar, int prevMask, int chosenSubset, EnchantableItem prevGear, EnchantableItem gear) {
            this.cost = costSoFar; this.prevMask = prevMask; this.chosenSubset = chosenSubset; this.prevGear = prevGear; this.gear = gear;
        }
    }
}
