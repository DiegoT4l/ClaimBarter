package io.github.diegot4l.claimbarter;

import harness.support.CallLog;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.PlayerData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Standalone failure-injection harness for BarterService's purchase-rollback
 * protocol (docs/design/purchase-rollback/final_spec.json).
 *
 * <p>Lives in io.github.diegot4l.claimbarter on purpose, so it can construct
 * BarterSettings through its record constructor and call BarterService's
 * package-private buy/sell/info directly, exactly as BarterCommand does in
 * the real plugin.
 *
 * <p>Every injection runs twice - once with PlayerInventory.mirrorSemantics
 * true, once false - because paper-api does not say which way
 * getStorageContents() behaves, and final_spec.json's own residual risks
 * admit the ambiguity. See harness/support/CallLog for how ordering
 * invariants are asserted from the interleaved call log rather than from
 * source.
 */
public final class Harness
{
    private static Messages messages;
    private static int passed = 0;
    private static int failed = 0;

    private static final Set<String> VALID_KEYS = Set.of(
            "bought", "sold", "info", "transaction-failed", "items-lost", "data-unavailable",
            "not-enough-items", "not-enough-blocks", "blocks-in-use", "selling-disabled",
            "amount-too-small", "limit-reached", "invalid-amount", "overflow");

    private static final Set<String> ALL_KEYS_SEEN = new HashSet<>();

    public static void main(String[] args) throws IOException
    {
        String configPath = args.length > 0 ? args[0] : "src/main/resources/config.yml";
        ConfigurationSection messagesSection = loadMessagesSection(configPath);
        Logger bootLogger = Logger.getLogger("harness.boot." + UUID.randomUUID());
        bootLogger.setUseParentHandlers(false);
        messages = new Messages(messagesSection, bootLogger);

        // invariant #5 is measured with the per-thread allocation counter; a
        // JVM that cannot supply it would let that check pass blind.
        com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!(mx.isThreadAllocatedMemorySupported() && mx.isThreadAllocatedMemoryEnabled()))
        {
            System.out.println("FATAL: per-thread allocation counting is unsupported or disabled;"
                    + " invariant #5 (PRE-BOXED SETTER) cannot be checked");
            System.exit(1);
        }

        run("BUY-HAPPY", Harness::injBuyHappy);
        run("BUY-REFUSE-NO-ITEMS", Harness::injBuyRefuseNoItems);
        run("BUY-REFUSE-OVERFLOW-NEGATIVE-POOL", Harness::injBuyOverflowNegativePool);
        run("BUY-SAVE-OOM-CLEAN-UNWIND", Harness::injBuySaveOomCleanUnwind);
        run("BUY-SAVE-OOM-STALE-WRITER-WINS", Harness::injBuySaveOomStaleWriterWins);
        run("BUY-REMOVECURRENCY-THROWS-PARTWAY", Harness::injBuyRemoveCurrencyThrowsPartway);
        run("BUY-TAKE-THROWS-AND-RECOUNT-THROWS", Harness::injBuyTakeThrowsAndRecountThrows);
        run("BUY-RECOUNT-ALONE-THROWS", Harness::injBuyRecountAloneThrows);
        run("BUY-UNDO-OUT-OF-RANGE-GRANT-STANDS", Harness::injBuyUndoOutOfRangeGrantStands);
        run("BUY-UNDO-RELATIVE-IN-RANGE", Harness::injBuyUndoRelativeInRange);
        run("BUY-POOL-MOVED-BEFORE-MUTATION", Harness::injBuyPoolMovedBeforeMutation);
        run("BUY-SILENT-WRITE-FAILURE", Harness::injBuySilentWriteFailure);
        run("BUY-LAZY-LOAD-ZERO", Harness::injBuyLazyLoadZero);
        run("BUY-DATASTORE-NULL", Harness::injBuyDataStoreNull);
        run("BUY-REFUSE-INVALID-AMOUNT", Harness::injBuyRefuseInvalidAmount);
        run("BUY-REFUSE-LIMIT-REACHED", Harness::injBuyRefuseLimitReached);
        run("WARM-DISARMS-SAVE-THREAD", Harness::injWarmDisarmsSaveThread);
        run("SELL-HAPPY", Harness::injSellHappy);
        run("SELL-SAVE-OOM-CLEAN-UNWIND", Harness::injSellSaveOomCleanUnwind);
        run("SELL-UNDO-FAILS-DEDUCTION-STANDS", Harness::injSellUndoFailsDeductionStands);
        run("SELL-UNDO-RELATIVE-IN-RANGE", Harness::injSellUndoRelativeInRange);
        run("SELL-REFUSE-NOT-ENOUGH-BLOCKS", Harness::injSellRefuseNotEnoughBlocks);
        run("SELL-REFUSE-BLOCKS-IN-USE", Harness::injSellRefuseBlocksInUse);
        run("SELL-REFUSE-AMOUNT-TOO-SMALL", Harness::injSellRefuseAmountTooSmall);
        run("SELL-REFUSE-SELLING-DISABLED", Harness::injSellRefuseSellingDisabled);
        run("SELL-PAYOUT-ALL-DROPS-CANCELLED", Harness::injSellPayoutAllDropsCancelled);
        run("SELL-PAYOUT-ADDITEM-THROWS-AFTER-PARTIAL", Harness::injSellPayoutAddItemThrowsAfterPartial);
        run("SELL-PAYOUT-LISTENER-SHRINKS-DROP", Harness::injSellPayoutListenerShrinksDrop);
        run("SELL-PAYOUT-LISTENER-MERGES-DROP-THEN-CANCEL", Harness::injSellPayoutListenerMergesThenCancel);
        run("SELL-PAYOUT-LISTENER-TELEPORTS", Harness::injSellPayoutListenerTeleports);
        run("SELL-PAYOUT-KICK-MID-PAYOUT", Harness::injSellPayoutKickMidPayout);
        run("SELL-PAYOUT-DROP-THROWS-UNCONFIRMED", Harness::injSellPayoutDropThrowsUnconfirmed);
        run("INFO-WARM-APPLIES-FIX", Harness::injInfoWarmAppliesFix);
        run("INFO-DATASTORE-NULL", Harness::injInfoDataStoreNull);
        run("METADATA-STACKS-NEVER-SPENT", Harness::injMetadataStacksNeverSpent);
        run("PREPARE-GETNAME-THROWS", Harness::injPrepareGetNameThrows);
        run("SELL-PAYOUT-DROP-RETURNS-NULL", m -> injSellPayoutDropUnconfirmable(m, false));
        run("SELL-PAYOUT-DROP-NULL-STACK", m -> injSellPayoutDropUnconfirmable(m, true));
        run("LAZY-LOAD-THROWS", Harness::injLazyLoadThrows);
        run("BUY-FIRST-COUNT-THROWS", Harness::injBuyFirstCountThrows);
        // Must stay LAST: its coverage check reads every key the runs above produced.
        run("NO-INVENTED-KEYS", Harness::injNoInventedKeys);

        System.out.println("TOTAL passed=" + passed + " failed=" + failed);
        if (failed > 0)
        {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- setup

    private static final class Ctx
    {
        BarterSettings settings;
        Logger logger;
        RecordingHandler handler;
        GriefPrevention gp;
        DataStore store;
        PlayerData data;
        PlayerInventory inv;
        World world;
        Player player;
    }

    private static Ctx setup(boolean mirror, int bonus, int blocksPerItem)
    {
        return setup(mirror, Integer.valueOf(bonus), blocksPerItem, true, 0.5, 0);
    }

    private static Ctx setup(boolean mirror, Integer bonus, int blocksPerItem)
    {
        return setup(mirror, bonus, blocksPerItem, true, 0.5, 0);
    }

    private static Ctx setup(boolean mirror, int bonus, int blocksPerItem,
            boolean sellingEnabled, double refundRatio, int maxPurchasedBlocks)
    {
        return setup(mirror, Integer.valueOf(bonus), blocksPerItem, sellingEnabled, refundRatio, maxPurchasedBlocks);
    }

    private static Ctx setup(boolean mirror, Integer bonus, int blocksPerItem,
            boolean sellingEnabled, double refundRatio, int maxPurchasedBlocks)
    {
        CallLog.reset();
        ItemStack.resetKnobs();
        World.resetGlobal();

        Ctx c = new Ctx();
        c.settings = new BarterSettings(Material.IRON_INGOT, "iron ingots", blocksPerItem,
                sellingEnabled, refundRatio, maxPurchasedBlocks);

        c.handler = new RecordingHandler();
        c.logger = Logger.getLogger("cb-test-" + UUID.randomUUID());
        c.logger.setUseParentHandlers(false);
        c.logger.addHandler(c.handler);
        c.logger.setLevel(Level.ALL);

        c.gp = new GriefPrevention();
        c.store = new DataStore();
        c.gp.dataStore = c.store;
        GriefPrevention.instance = c.gp;

        c.data = new PlayerData(bonus);
        // ARMED in every run: PRE-BOXED SETTER must never fire.
        c.data.setterThrows = true;
        c.store.setCachedPlayerData(c.data);

        c.inv = new PlayerInventory();
        c.inv.mirrorSemantics = mirror;

        c.world = new World("world");
        Player player = new Player("Steve", UUID.randomUUID(), c.world, c.inv);
        c.world.teleportTarget = player;
        c.player = player;

        return c;
    }

    private static void putCurrency(Ctx c, int slot, int amount)
    {
        putCurrency(c, slot, amount, false);
    }

    private static void putCurrency(Ctx c, int slot, int amount, boolean meta)
    {
        ItemStack stack = new ItemStack(c.settings.currency(), amount);
        stack.setHasItemMeta(meta);
        c.inv.setSlot(slot, stack);
    }

    private static int sumPlainCurrency(Ctx c)
    {
        int total = 0;
        for (int i = 0; i < PlayerInventory.SIZE; i++)
        {
            ItemStack s = c.inv.rawSlot(i);
            if (s != null && s.getType() == c.settings.currency() && !s.hasItemMeta())
            {
                total += s.getAmount();
            }
        }
        return total;
    }

    // ------------------------------------------------------------- asserts

    private static final class AssertionAborted extends RuntimeException
    {
        AssertionAborted(String message)
        {
            super(message);
        }
    }

    private static void check(boolean condition, String message)
    {
        if (!condition)
        {
            throw new AssertionAborted(message);
        }
    }

    /**
     * invariant5 PRE-BOXED SETTER. Beyond the null canary, every setter call
     * must follow ZERO bytes of allocation since the last pool/inventory read
     * (PlayerData.setterAllocBytes), which proves its Integer was boxed in
     * PREPARE rather than at the point of use. freshBoxAllowedIdx names the
     * setter calls (0-based) that are the in-range relative undo, the one
     * place the spec permits a fresh Integer.valueOf.
     *
     * <p>Blind spot: a checked setter value inside the Integer cache range
     * [-128,127] comes back from Integer.valueOf as a cached box with no
     * allocation, so every such value must stay outside it. All current
     * injections' values already do.
     */
    private static void checkSetterNeverThrew(Ctx c, int... freshBoxAllowedIdx)
    {
        check(!c.data.setterThrowsFired, "invariant5 PRE-BOXED SETTER: setterThrows fired unexpectedly");
        check(c.data.setterAllocBytes.size() == c.data.setterArguments.size(),
                "invariant5 PRE-BOXED SETTER: setterAllocBytes out of step with setterArguments");
        outer:
        for (int i = 0; i < c.data.setterAllocBytes.size(); i++)
        {
            for (int allowed : freshBoxAllowedIdx)
            {
                if (allowed == i)
                {
                    continue outer;
                }
            }
            long bytes = c.data.setterAllocBytes.get(i);
            check(bytes == 0, "invariant5 PRE-BOXED SETTER: setBonusClaimBlocks call #" + i
                    + " (arg " + c.data.setterArguments.get(i) + ") followed " + bytes
                    + " bytes of allocation after the last pool/inventory read; its Integer was not boxed in PREPARE");
        }
    }

    private static void checkResultTruthfulKeyAndRender(BarterService.Result r)
    {
        check(VALID_KEYS.contains(r.messageKey()), "NO-INVENTED-KEYS: unknown key '" + r.messageKey() + "'");
        ALL_KEYS_SEEN.add(r.messageKey());
        String rendered = messages.render(r.messageKey(), r.placeholders()).rendered();
        check(!rendered.contains("Missing message"),
                "invariant4: rendered text contains 'Missing message' for key " + r.messageKey());
        check(!rendered.contains("{"),
                "invariant4: rendered text contains an unsubstituted placeholder for key " + r.messageKey()
                        + ": " + rendered);
    }

    private static String render(BarterService.Result r)
    {
        return messages.render(r.messageKey(), r.placeholders()).rendered();
    }

    private static boolean placeholderStringEquals(Object[] placeholders, String key, String expected)
    {
        for (int i = 0; i < placeholders.length; i += 2)
        {
            if (key.equals(placeholders[i]))
            {
                return expected.equals(placeholders[i + 1]);
            }
        }
        return false;
    }

    private static boolean logHas(List<String> log, String prefix)
    {
        return log.stream().anyMatch(e -> e.startsWith(prefix));
    }

    private static int indexOfPrefix(List<String> log, String prefix)
    {
        for (int i = 0; i < log.size(); i++)
        {
            if (log.get(i).startsWith(prefix))
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * The entry immediately before the failing savePlayerData must be the
     * grant/deduction itself: the real savePlayerData only constructs and
     * starts SavePlayerDataThread, so a failure the caller can see happens
     * before run() and no PlayerData method runs inside it.
     */
    private static void checkNothingReadInsideFailingSave(List<String> log, int setterValue)
    {
        int idx = indexOfPrefix(log, "DataStore.savePlayerData(threw before run)");
        check(idx > 0, "expected 'DataStore.savePlayerData(threw before run)' in the log, got " + log);
        String expected = "PlayerData.setBonusClaimBlocks(" + setterValue + ")";
        check(expected.equals(log.get(idx - 1)),
                "expected '" + expected + "' immediately before the failing savePlayerData, got '"
                        + log.get(idx - 1) + "'");
    }

    private static RecordingHandler.Entry findAtLevel(Ctx c, Level level, String... mustContainAll)
    {
        outer:
        for (RecordingHandler.Entry e : c.handler.entries)
        {
            if (!e.level.equals(level))
            {
                continue;
            }
            for (String s : mustContainAll)
            {
                if (!e.message.contains(s))
                {
                    continue outer;
                }
            }
            return e;
        }
        return null;
    }

    private static boolean anyAtLevel(Ctx c, Level level)
    {
        for (RecordingHandler.Entry e : c.handler.entries)
        {
            if (e.level.equals(level))
            {
                return true;
            }
        }
        return false;
    }

    private static int countAtLevel(Ctx c, Level level)
    {
        int n = 0;
        for (RecordingHandler.Entry e : c.handler.entries)
        {
            if (e.level.equals(level))
            {
                n++;
            }
        }
        return n;
    }

    private static String describeEntries(Ctx c)
    {
        StringBuilder sb = new StringBuilder();
        for (RecordingHandler.Entry e : c.handler.entries)
        {
            sb.append('[').append(e.level).append("] ").append(e.message).append(" || ");
        }
        return sb.toString();
    }

    private static boolean placeholderEquals(Object[] placeholders, String key, long expected)
    {
        for (int i = 0; i < placeholders.length; i += 2)
        {
            if (key.equals(placeholders[i]))
            {
                Object v = placeholders[i + 1];
                if (v instanceof Number n)
                {
                    return n.longValue() == expected;
                }
            }
        }
        return false;
    }

    /**
     * invariant6 NO ALLOCATION-BEARING CALL BETWEEN THE TAKE AND THE GRANT.
     * getStorageContents (a plain re-read/measurement) is explicitly NOT
     * forbidden by final_spec.json's own wording of this invariant - only
     * getPlayerData, getClaims, a lazy load, ItemStack construction, or a
     * message render are.
     */
    private static void checkNoAllocationBetweenTakeAndGrant(List<String> log)
    {
        int setStorageIdx = -1;
        for (int i = 0; i < log.size(); i++)
        {
            if (log.get(i).startsWith("PlayerInventory.setStorageContents"))
            {
                setStorageIdx = i;
                break;
            }
        }
        check(setStorageIdx >= 0, "invariant6: no setStorageContents call found in the log");
        int setBonusIdx = -1;
        for (int i = setStorageIdx + 1; i < log.size(); i++)
        {
            if (log.get(i).startsWith("PlayerData.setBonusClaimBlocks"))
            {
                setBonusIdx = i;
                break;
            }
        }
        check(setBonusIdx >= 0, "invariant6: no setBonusClaimBlocks call found after setStorageContents");
        for (int i = setStorageIdx + 1; i < setBonusIdx; i++)
        {
            String e = log.get(i);
            check(!e.startsWith("DataStore.getPlayerData") && !e.startsWith("PlayerData.getClaims")
                            && !e.startsWith("PlayerData.internalFixNegative") && !e.startsWith("ItemStack.new"),
                    "invariant6 NO ALLOCATION-BEARING CALL BETWEEN THE TAKE AND THE GRANT: found '" + e
                            + "' between setStorageContents and setBonusClaimBlocks");
        }
    }

    private static void checkBuyOrdering(List<String> log)
    {
        int lastInvMutation = -1;
        int firstSetBonus = -1;
        for (int i = 0; i < log.size(); i++)
        {
            String e = log.get(i);
            if (e.startsWith("PlayerInventory.setStorageContents"))
            {
                lastInvMutation = i;
            }
            if (firstSetBonus < 0 && e.startsWith("PlayerData.setBonusClaimBlocks"))
            {
                firstSetBonus = i;
            }
        }
        check(lastInvMutation >= 0, "invariant7 ORDERING: no inventory mutation found in the log");
        check(firstSetBonus >= 0, "invariant7 ORDERING: no setBonusClaimBlocks found in the log");
        check(lastInvMutation < firstSetBonus,
                "invariant7 ORDERING: the last inventory mutation must precede the first setBonusClaimBlocks");
    }

    private static void checkSellOrdering(List<String> log)
    {
        int firstSetBonus = -1;
        int firstSave = -1;
        int firstPayout = -1;
        for (int i = 0; i < log.size(); i++)
        {
            String e = log.get(i);
            if (firstSetBonus < 0 && e.startsWith("PlayerData.setBonusClaimBlocks"))
            {
                firstSetBonus = i;
            }
            if (firstSave < 0 && e.startsWith("DataStore.savePlayerData"))
            {
                firstSave = i;
            }
            if (firstPayout < 0 && (e.startsWith("PlayerInventory.addItem") || e.contains("dropItemNaturally")))
            {
                firstPayout = i;
            }
        }
        check(firstSetBonus >= 0, "invariant7 ORDERING: no setBonusClaimBlocks found in the log");
        check(firstSave >= 0, "invariant7 ORDERING: no savePlayerData/-Sync found in the log");
        if (firstPayout >= 0)
        {
            check(firstSetBonus < firstPayout,
                    "invariant7 ORDERING: setBonusClaimBlocks must precede the first addItem/dropItemNaturally");
            check(firstSave < firstPayout,
                    "invariant7 ORDERING: savePlayerData must precede the first addItem/dropItemNaturally");
        }
    }

    // -------------------------------------------------------------- runner

    private static void run(String id, Function<Boolean, String> body)
    {
        for (boolean mirror : new boolean[] {true, false})
        {
            String failure;
            try
            {
                failure = body.apply(mirror);
            }
            catch (AssertionAborted e)
            {
                failure = e.getMessage();
            }
            catch (Throwable t)
            {
                failure = "unexpected " + t.getClass().getName() + ": " + t.getMessage();
            }
            boolean ok = failure == null;
            System.out.println("RESULT " + id + " " + (mirror ? "mirror" : "copy") + " "
                    + (ok ? "PASS" : "FAIL") + (ok ? "" : (" " + failure)));
            if (ok)
            {
                passed++;
            }
            else
            {
                failed++;
            }
        }
    }

    // ---------------------------------------------------------- injections

    private static String injBuyHappy(boolean mirror)
    {
        Ctx c = setup(mirror, 500, 100);
        putCurrency(c, 0, 64);
        UUID uuid = c.player.getUniqueId();

        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = service.buy(c.player, 2);
        List<String> log = CallLog.snapshot();

        check(result != null && result.ok(), "expected a successful buy result, got " + result);
        check("bought".equals(result.messageKey()), "expected key 'bought', got " + result.messageKey());
        checkResultTruthfulKeyAndRender(result);

        check(sumPlainCurrency(c) == 62, "expected 62 currency remaining, got " + sumPlainCurrency(c));
        check(World.GLOBAL_DROP_LOG.isEmpty(), "expected nothing dropped");
        check(c.data.getBonusClaimBlocks() == 700, "expected live pool 700");

        check(c.store.saveCallLog.size() == 1, "expected exactly one save call, got " + c.store.saveCallLog);
        check("savePlayerData(700)".equals(c.store.saveCallLog.get(0)),
                "expected savePlayerData(700), got " + c.store.saveCallLog.get(0));
        Integer disk = c.store.simulatedDisk.get(uuid);
        check(disk != null && disk == 700, "expected simulatedDisk 700, got " + disk);

        check(!anyAtLevel(c, Level.SEVERE), "expected no SEVERE; entries=" + describeEntries(c));
        check(!anyAtLevel(c, Level.WARNING), "expected no WARNING; entries=" + describeEntries(c));
        RecordingHandler.Entry info = findAtLevel(c, Level.INFO,
                "bought 200 claim blocks for 2 iron ingots", "bonus pool 500 -> 700");
        check(info != null, "expected the INFO reconciliation line; entries=" + describeEntries(c));

        checkBuyOrdering(log);
        checkNoAllocationBetweenTakeAndGrant(log);
        checkSetterNeverThrew(c);
        return null;
    }

    private static String injBuyRefuseNoItems(boolean mirror)
    {
        Ctx c = setup(mirror, 0, 100);
        putCurrency(c, 0, 3);

        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = service.buy(c.player, 10);

        check(result != null && !result.ok(), "expected a failing result, got " + result);
        check("not-enough-items".equals(result.messageKey()), "expected not-enough-items, got " + result.messageKey());
        checkResultTruthfulKeyAndRender(result);
        check(sumPlainCurrency(c) == 3, "expected currency unchanged at 3, got " + sumPlainCurrency(c));
        check(c.data.setterArguments.isEmpty(), "setBonusClaimBlocks must never be called");
        check(c.store.saveCallLog.isEmpty(), "savePlayerData must never be called");
        check(c.data.getClaimsCallCount == 0, "getClaims must never be called before the refusal");
        check(c.handler.entries.isEmpty(),
                "expected nothing logged at any level, got " + describeEntries(c));

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injBuyOverflowNegativePool(boolean mirror)
    {
        Ctx c = setup(mirror, -2_000_000_000, 1_000_000);
        putCurrency(c, 0, 3000);

        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = service.buy(c.player, 3000);

        check(result != null && !result.ok(), "expected a failing result, got " + result);
        check("overflow".equals(result.messageKey()), "expected overflow, got " + result.messageKey());
        checkResultTruthfulKeyAndRender(result);
        check(sumPlainCurrency(c) == 3000, "expected currency unchanged, got " + sumPlainCurrency(c));
        check(c.data.setterArguments.isEmpty(), "no setter call expected");
        check(c.handler.entries.isEmpty(), "expected nothing logged, got " + describeEntries(c));

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injBuySaveOomCleanUnwind(boolean mirror)
    {
        Ctx c = setup(mirror, 500, 100);
        putCurrency(c, 0, 64);
        c.store.throwOnSavePlayerData = new OutOfMemoryError("harness: native thread creation failed");

        BarterService service = new BarterService(c.settings, c.logger);
        Throwable thrown = null;
        try
        {
            service.buy(c.player, 2);
        }
        catch (Throwable t)
        {
            thrown = t;
        }
        List<String> log = CallLog.snapshot();

        check(thrown instanceof OutOfMemoryError, "expected OutOfMemoryError rethrown, got " + thrown);
        check(sumPlainCurrency(c) == 64, "expected currency refunded to 64, got " + sumPlainCurrency(c));
        check(World.GLOBAL_DROP_LOG.isEmpty(), "expected nothing dropped on refund");
        check(c.data.getBonusClaimBlocks() == 500, "expected live pool restored to 500");

        long syncSaves = c.store.saveCallLog.stream().filter(s -> s.startsWith("savePlayerDataSync")).count();
        check(syncSaves == 1, "expected savePlayerDataSync called exactly once, got " + c.store.saveCallLog);
        check("savePlayerDataSync(500)".equals(c.store.saveCallLog.get(c.store.saveCallLog.size() - 1)),
                "expected the sync save to record live=500, got " + c.store.saveCallLog);

        int addItemIdx = -1;
        int syncIdx = -1;
        for (int i = 0; i < log.size(); i++)
        {
            if (addItemIdx < 0 && log.get(i).startsWith("PlayerInventory.addItem"))
            {
                addItemIdx = i;
            }
            if (log.get(i).startsWith("DataStore.savePlayerDataSync"))
            {
                syncIdx = i;
            }
        }
        check(addItemIdx >= 0, "expected a refund addItem call in the log");
        check(syncIdx > addItemIdx, "expected savePlayerDataSync to run AFTER the refund");

        RecordingHandler.Entry severe = findAtLevel(c, Level.SEVERE,
                "2 iron ingots requested", "2 taken", "2 returned",
                "granted 200 and undone in memory, pool now 500",
                "GriefPrevention swallows write failures, so this does not confirm what the file holds",
                "Verify the player's file: if it shows bonus=700");
        check(severe != null, "expected one SEVERE with the clean-unwind text; entries=" + describeEntries(c));
        check(!severe.message.contains("Nothing is left to fix by hand"), "forbidden substring present");
        check(!severe.message.contains("written"), "forbidden claim about the file being 'written'");

        checkNothingReadInsideFailingSave(log, 700);
        checkSetterNeverThrew(c);
        return null;
    }

    private static String injBuySaveOomStaleWriterWins(boolean mirror)
    {
        Ctx c = setup(mirror, 500, 100);
        putCurrency(c, 0, 64);
        c.store.throwOnSavePlayerData = new OutOfMemoryError("harness: native thread creation failed");
        UUID uuid = c.player.getUniqueId();
        c.store.armPendingStaleWriter(uuid, c.data);

        BarterService service = new BarterService(c.settings, c.logger);
        Throwable thrown = null;
        try
        {
            service.buy(c.player, 2);
        }
        catch (Throwable t)
        {
            thrown = t;
        }

        check(thrown instanceof OutOfMemoryError, "expected OutOfMemoryError rethrown, got " + thrown);
        check(sumPlainCurrency(c) == 64, "expected currency refunded to 64, got " + sumPlainCurrency(c));
        check(c.data.getBonusClaimBlocks() == 500, "expected the LIVE pool to be correct (500)");
        check(c.store.hasCapturedStaleWrite(), "expected the stale writer to have captured a value");
        Integer disk = c.store.simulatedDisk.get(uuid);
        check(disk != null && disk == 700, "expected simulatedDisk to hold the stale 700, got " + disk);

        String lastSyncEntry = null;
        for (String s : c.store.saveCallLog)
        {
            if (s.startsWith("savePlayerDataSync"))
            {
                lastSyncEntry = s;
            }
        }
        check("savePlayerDataSync(500)".equals(lastSyncEntry),
                "expected the plugin's own sync write to record 500, got " + lastSyncEntry);

        RecordingHandler.Entry severe = findAtLevel(c, Level.SEVERE,
                "Verify the player's file: if it shows bonus=700",
                "/adjustbonusclaimblocks Steve -200");
        check(severe != null, "expected the regression-test SEVERE naming the exact command; entries="
                + describeEntries(c));

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injBuyRemoveCurrencyThrowsPartway(boolean mirror)
    {
        Ctx c = setup(mirror, 500, 100);
        putCurrency(c, 0, 32);
        putCurrency(c, 1, 32);
        c.inv.throwInSetStorageContentsAfterSlotK = 1;

        BarterService service = new BarterService(c.settings, c.logger);
        Throwable thrown = null;
        try
        {
            service.buy(c.player, 40);
        }
        catch (Throwable t)
        {
            thrown = t;
        }

        check(thrown instanceof Error, "expected an Error rethrown, got " + thrown);
        check(sumPlainCurrency(c) == 64,
                "expected final currency == 64 under " + (mirror ? "mirror" : "copy")
                        + " semantics, got " + sumPlainCurrency(c));
        check(c.data.setterArguments.isEmpty(), "setBonusClaimBlocks must never be called");
        check(c.store.saveCallLog.stream().noneMatch(s -> s.startsWith("savePlayerDataSync")),
                "savePlayerDataSync must never be called");

        // removeCurrency's body is unchanged by the new protocol (final_spec B9),
        // so the actually-removed count can be derived from its known logic: a
        // fully-processed local array is committed only up to slot K, so under
        // copy semantics only slot 0's full removal lands (32); under mirror
        // semantics every mutation already landed on the live array before the
        // throw, so the full 40 was actually taken.
        int expectedK = mirror ? 40 : 32;
        RecordingHandler.Entry severe = findAtLevel(c, Level.SEVERE,
                expectedK + " taken", expectedK + " returned",
                "claim blocks: never granted",
                "durable write: not attempted; nothing of this trade was ever written",
                "Fix by hand: nothing");
        check(severe != null, "expected SEVERE describing K=" + expectedK + "; entries=" + describeEntries(c));

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injBuyTakeThrowsAndRecountThrows(boolean mirror)
    {
        Ctx c = setup(mirror, 500, 100);
        putCurrency(c, 0, 64);
        c.inv.throwInSetStorageContentsAfterSlotK = 1;
        c.inv.alsoFailNextReadAfterSetStorageContentsThrow = true;

        BarterService service = new BarterService(c.settings, c.logger);
        Throwable thrown = null;
        try
        {
            service.buy(c.player, 40);
        }
        catch (Throwable t)
        {
            thrown = t;
        }
        List<String> log = CallLog.snapshot();

        check(thrown instanceof Error, "expected an Error rethrown, got " + thrown);
        check(log.stream().noneMatch(e -> e.startsWith("PlayerInventory.addItem")), "addItem must never be called");
        check(World.GLOBAL_DROP_LOG.isEmpty(), "dropItemNaturally must never be called");
        check(c.data.setterArguments.isEmpty(), "setBonusClaimBlocks must never be called");

        RecordingHandler.Entry severe = findAtLevel(c, Level.SEVERE,
                "an unknown number taken", "0 returned (the refund was deliberately skipped)",
                "Inspect the inventory: up to 40 iron ingots may be missing and were not refunded automatically.");
        check(severe != null, "expected the unknown-count SEVERE; entries=" + describeEntries(c));

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injBuyUndoOutOfRangeGrantStands(boolean mirror)
    {
        Ctx c = setup(mirror, 0, 2_000_000_000);
        putCurrency(c, 0, 5);
        c.store.throwOnSavePlayerData = new OutOfMemoryError("harness: native thread creation failed");
        int base = 2_000_000_000; // the value the grant lands on: 0 + 1 * 2_000_000_000
        int target = Integer.MIN_VALUE;
        int delta = target - base; // relies on two's-complement wraparound to land exactly on target
        c.data.armForeignWriteAfterSetterCalls(1, delta);

        BarterService service = new BarterService(c.settings, c.logger);
        Throwable thrown = null;
        try
        {
            service.buy(c.player, 1);
        }
        catch (Throwable t)
        {
            thrown = t;
        }
        List<String> log = CallLog.snapshot();

        check(thrown instanceof OutOfMemoryError, "expected the original OutOfMemoryError rethrown, got " + thrown);
        check(log.stream().noneMatch(e -> e.startsWith("PlayerInventory.addItem")),
                "the refund must be skipped: no addItem call expected");
        check(World.GLOBAL_DROP_LOG.isEmpty(), "the refund must be skipped: no dropItemNaturally call expected");
        check(c.store.saveCallLog.stream().anyMatch(s -> s.startsWith("savePlayerDataSync")),
                "expected savePlayerDataSync to still be called for the standing grant");

        RecordingHandler.Entry severe = findAtLevel(c, Level.SEVERE,
                "granted", "and NOT undone",
                "0 returned (the refund was deliberately skipped)",
                "The purchase stands: the items are the payment and the blocks were kept.");
        check(severe != null, "expected the grant-stands SEVERE; entries=" + describeEntries(c));

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injBuyPoolMovedBeforeMutation(boolean mirror)
    {
        Ctx c = setup(mirror, 500, 100);
        putCurrency(c, 0, 64);
        c.data.armForeignWriteAfterGetClaims(1, 37);

        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = service.buy(c.player, 2);
        List<String> log = CallLog.snapshot();

        check(result != null && !result.ok(), "expected a failing result, got " + result);
        check("transaction-failed".equals(result.messageKey()),
                "expected transaction-failed, got " + result.messageKey());
        checkResultTruthfulKeyAndRender(result);
        check(sumPlainCurrency(c) == 64, "expected currency untouched");
        check(log.stream().noneMatch(e -> e.startsWith("PlayerInventory.setStorageContents")),
                "removeCurrency must never run");
        check(c.data.setterArguments.isEmpty(), "setBonusClaimBlocks must never be called");
        check(!anyAtLevel(c, Level.SEVERE), "expected no SEVERE; entries=" + describeEntries(c));
        RecordingHandler.Entry warning = findAtLevel(c, Level.WARNING,
                "bonus pool changed from", "refused and nothing was charged");
        check(warning != null, "expected the pool-moved WARNING; entries=" + describeEntries(c));

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injBuySilentWriteFailure(boolean mirror)
    {
        Ctx c = setup(mirror, 500, 100);
        putCurrency(c, 0, 64);
        c.store.swallowWriteSilently = true;
        UUID uuid = c.player.getUniqueId();

        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = service.buy(c.player, 2);
        List<String> log = CallLog.snapshot();

        check(result != null && result.ok() && "bought".equals(result.messageKey()), "expected bought, got " + result);
        checkResultTruthfulKeyAndRender(result);
        check(sumPlainCurrency(c) == 62, "expected 62 remaining, got " + sumPlainCurrency(c));
        check(c.data.getBonusClaimBlocks() == 700, "expected live pool 700");
        Integer disk = c.store.simulatedDisk.get(uuid);
        check(disk == null || disk == 500, "expected simulatedDisk to still read 500 (or absent), got " + disk);
        check(!anyAtLevel(c, Level.SEVERE), "expected no SEVERE; entries=" + describeEntries(c));
        RecordingHandler.Entry info = findAtLevel(c, Level.INFO, "bonus pool 500 -> 700");
        check(info != null, "expected the INFO reconciliation line; entries=" + describeEntries(c));
        for (RecordingHandler.Entry e : c.handler.entries)
        {
            check(!e.message.toLowerCase(Locale.ROOT).contains("saved"),
                    "log must not claim the data was saved: " + e.message);
        }

        checkBuyOrdering(log);
        checkSetterNeverThrew(c);
        return null;
    }

    private static String injBuyLazyLoadZero(boolean mirror)
    {
        Ctx c = setup(mirror, null, 100);
        c.data.lazyLoadReturnsZero = true;
        putCurrency(c, 0, 64);
        UUID uuid = c.player.getUniqueId();
        c.store.simulatedDisk.put(uuid, 500);

        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = service.buy(c.player, 2);
        List<String> log = CallLog.snapshot();

        check(result != null && result.ok() && "bought".equals(result.messageKey()), "expected bought, got " + result);
        checkResultTruthfulKeyAndRender(result);
        check(sumPlainCurrency(c) == 62, "expected 62 remaining, got " + sumPlainCurrency(c));
        check(c.data.getBonusClaimBlocks() == 200, "expected live pool 200");
        Integer disk = c.store.simulatedDisk.get(uuid);
        check(disk != null && disk == 200, "expected simulatedDisk overwritten to 200, got " + disk);
        check(!anyAtLevel(c, Level.WARNING), "expected no WARNING (before and after the warm are both 0); entries="
                + describeEntries(c));
        RecordingHandler.Entry info = findAtLevel(c, Level.INFO, "bonus pool 0 -> 200");
        check(info != null, "expected the INFO reconciliation line; entries=" + describeEntries(c));

        checkBuyOrdering(log);
        checkSetterNeverThrew(c);
        return null;
    }

    private static String injBuyDataStoreNull(boolean mirror)
    {
        Ctx c = setup(mirror, 500, 100);
        putCurrency(c, 0, 64);
        c.gp.dataStore = null;

        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = service.buy(c.player, 2);

        check(result != null && !result.ok() && "data-unavailable".equals(result.messageKey()),
                "expected data-unavailable, got " + result);
        checkResultTruthfulKeyAndRender(result);
        check(sumPlainCurrency(c) == 64, "expected currency unchanged");
        check(countAtLevel(c, Level.WARNING) == 1,
                "expected exactly one WARNING, got " + countAtLevel(c, Level.WARNING));
        check(!anyAtLevel(c, Level.SEVERE), "expected no SEVERE");
        RecordingHandler.Entry warning = findAtLevel(c, Level.WARNING,
                "Refused a purchase for", "data store is unavailable");
        check(warning != null, "expected the data-unavailable WARNING; entries=" + describeEntries(c));

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injWarmDisarmsSaveThread(boolean mirror)
    {
        Ctx c = setup(mirror, 55, 100);
        putCurrency(c, 0, 64);
        c.data.fixNegativeDeficit = 445;

        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = service.buy(c.player, 1);

        check(result != null && result.ok() && "bought".equals(result.messageKey()), "expected bought, got " + result);
        checkResultTruthfulKeyAndRender(result);
        check(c.data.getBonusClaimBlocks() == 600, "expected live pool 600 (55+445 credit, then +100 grant)");
        check(c.data.getClaimsCallCount >= 2,
                "expected getClaims called at least twice (warm + save), got " + c.data.getClaimsCallCount);
        check(countAtLevel(c, Level.WARNING) == 1,
                "expected exactly one WARNING for the GriefPrevention credit, got " + countAtLevel(c, Level.WARNING));
        RecordingHandler.Entry warning = findAtLevel(c, Level.WARNING,
                "GriefPrevention corrected Steve's bonus pool from 55 to 500",
                "while preparing a purchase", "ClaimBarter did not write this");
        check(warning != null, "expected the warm's credit WARNING; entries=" + describeEntries(c));

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injSellHappy(boolean mirror)
    {
        Ctx c = setup(mirror, 1000, 100, true, 1.0, 0);
        c.data.remainingOverride = 1000;
        UUID uuid = c.player.getUniqueId();

        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = service.sell(c.player, 500);
        List<String> log = CallLog.snapshot();

        check(result != null && result.ok() && "sold".equals(result.messageKey()), "expected sold, got " + result);
        checkResultTruthfulKeyAndRender(result);
        check(sumPlainCurrency(c) == 5, "expected 5 currency placed, got " + sumPlainCurrency(c));
        check(World.GLOBAL_DROP_LOG.isEmpty(), "expected nothing dropped");
        check(c.data.getBonusClaimBlocks() == 500, "expected pool 500");
        Integer disk = c.store.simulatedDisk.get(uuid);
        check(disk != null && disk == 500, "expected simulatedDisk 500, got " + disk);

        int saveIdx = -1;
        int addItemIdx = -1;
        for (int i = 0; i < log.size(); i++)
        {
            if (saveIdx < 0 && log.get(i).startsWith("DataStore.savePlayerData"))
            {
                saveIdx = i;
            }
            if (addItemIdx < 0 && log.get(i).startsWith("PlayerInventory.addItem"))
            {
                addItemIdx = i;
            }
        }
        check(saveIdx >= 0, "expected a savePlayerData call");
        check(addItemIdx < 0 || saveIdx < addItemIdx, "expected savePlayerData BEFORE any addItem call");

        check(!anyAtLevel(c, Level.SEVERE), "expected no SEVERE; entries=" + describeEntries(c));
        RecordingHandler.Entry info = findAtLevel(c, Level.INFO,
                "sold 500 claim blocks for 5 iron ingots", "bonus pool 1000 -> 500");
        check(info != null, "expected the INFO reconciliation line; entries=" + describeEntries(c));

        checkSellOrdering(log);
        checkSetterNeverThrew(c);
        return null;
    }

    private static String injSellSaveOomCleanUnwind(boolean mirror)
    {
        Ctx c = setup(mirror, 1000, 100);
        c.store.throwOnSavePlayerData = new OutOfMemoryError("harness: native thread creation failed");

        BarterService service = new BarterService(c.settings, c.logger);
        Throwable thrown = null;
        try
        {
            service.sell(c.player, 500);
        }
        catch (Throwable t)
        {
            thrown = t;
        }
        List<String> log = CallLog.snapshot();

        check(thrown instanceof OutOfMemoryError, "expected OutOfMemoryError rethrown, got " + thrown);
        check(log.stream().noneMatch(e -> e.startsWith("PlayerInventory.addItem")), "no items must be paid out");
        check(World.GLOBAL_DROP_LOG.isEmpty(), "no items must be dropped");
        check(c.data.getBonusClaimBlocks() == 1000, "expected pool restored to 1000");
        check(c.store.saveCallLog.stream().filter(s -> s.startsWith("savePlayerDataSync")).count() == 1,
                "expected savePlayerDataSync called exactly once, got " + c.store.saveCallLog);

        RecordingHandler.Entry severe = findAtLevel(c, Level.SEVERE,
                "deducted 500 claim blocks in memory, undone", "no items were paid out",
                "does not confirm what the file holds", "Verify the player's file: if it shows bonus=500");
        check(severe != null, "expected the clean-unwind SEVERE; entries=" + describeEntries(c));

        checkNothingReadInsideFailingSave(log, 500);
        checkSetterNeverThrew(c);
        return null;
    }

    private static String injSellUndoFailsDeductionStands(boolean mirror)
    {
        Ctx c = setup(mirror, 2_147_483_647, 1, true, 1.0, 0);
        c.store.throwOnSavePlayerData = new OutOfMemoryError("harness: native thread creation failed");
        int base = 147_483_647; // purchased - blocks
        int target = Integer.MAX_VALUE;
        c.data.armForeignWriteAfterSetterCalls(1, target - base);

        BarterService service = new BarterService(c.settings, c.logger);
        Throwable thrown = null;
        try
        {
            service.sell(c.player, 2_000_000_000);
        }
        catch (Throwable t)
        {
            thrown = t;
        }

        check(thrown instanceof OutOfMemoryError, "expected OutOfMemoryError rethrown, got " + thrown);
        check(World.GLOBAL_DROP_LOG.isEmpty(), "no items must be paid out");
        check(c.store.saveCallLog.stream().noneMatch(s -> s.startsWith("savePlayerDataSync")),
                "expected savePlayerDataSync to NEVER be called, got " + c.store.saveCallLog);

        RecordingHandler.Entry severe = findAtLevel(c, Level.SEVERE,
                "NOT undone",
                "durable write: skipped deliberately, so this plugin did not make the unpaid deduction durable",
                "Give the blocks back: run /adjustbonusclaimblocks Steve 2000000000");
        check(severe != null, "expected the deduction-stands SEVERE; entries=" + describeEntries(c));

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injSellPayoutAllDropsCancelled(boolean mirror)
    {
        Ctx c = setup(mirror, 1000, 100, true, 1.0, 0);
        c.inv.freeSlotOverride = 0;
        for (int i = 1; i <= 5; i++)
        {
            c.world.cancelDropNumbers.add(i);
        }

        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = service.sell(c.player, 500);

        check(result != null && !result.ok() && "items-lost".equals(result.messageKey()),
                "expected items-lost, got " + result);
        checkResultTruthfulKeyAndRender(result);
        check(placeholderEquals(result.placeholders(), "lost", 5),
                "expected lost=5, got " + Arrays.toString(result.placeholders()));
        check(sumPlainCurrency(c) == 0, "expected nothing to reach the player's inventory");
        check(c.data.getBonusClaimBlocks() == 500, "expected the sale itself to be correct: pool 500");
        check(c.store.saveCallLog.stream().anyMatch(s -> s.startsWith("savePlayerData(500)")),
                "expected the save to have started with 500");

        RecordingHandler.Entry severe = findAtLevel(c, Level.SEVERE, "0 confirmed",
                "5 lost and must be restored by hand",
                "nothing threw, so a drop was most likely destroyed by another plugin cancelling ItemSpawnEvent");
        check(severe != null, "expected the all-cancelled SEVERE; entries=" + describeEntries(c));

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injSellPayoutAddItemThrowsAfterPartial(boolean mirror)
    {
        Ctx c = setup(mirror, 1000, 100, true, 1.0, 0);
        c.inv.throwInAddItemAfterPlacingK = 3;

        BarterService service = new BarterService(c.settings, c.logger);
        Throwable thrown = null;
        try
        {
            service.sell(c.player, 500);
        }
        catch (Throwable t)
        {
            thrown = t;
        }

        check(thrown instanceof Error, "expected an Error rethrown by S12, got " + thrown);
        check(sumPlainCurrency(c) == 3, "expected exactly 3 currency placed, got " + sumPlainCurrency(c));
        check(c.data.getBonusClaimBlocks() == 500, "expected the sale itself correct: pool 500");
        check(c.store.saveCallLog.stream().anyMatch(s -> s.startsWith("savePlayerData(500)")),
                "expected the save to have started with 500");

        RecordingHandler.Entry severe = findAtLevel(c, Level.SEVERE,
                "3 confirmed (3 into the inventory, 0 dropped at their feet)",
                "2 lost and must be restored by hand");
        check(severe != null, "expected deliver's partial-addItem SEVERE; entries=" + describeEntries(c));

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injSellPayoutListenerShrinksDrop(boolean mirror)
    {
        Ctx c = setup(mirror, 1000, 100, true, 1.0, 0);
        c.inv.freeSlotOverride = 0;
        c.world.shrinkDropNumberTo.put(1, 1);

        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = service.sell(c.player, 500);

        check(result != null && !result.ok() && "items-lost".equals(result.messageKey()),
                "expected items-lost, got " + result);
        checkResultTruthfulKeyAndRender(result);
        check(placeholderEquals(result.placeholders(), "lost", 4),
                "expected lost=4, got " + Arrays.toString(result.placeholders()));
        check(sumPlainCurrency(c) == 0, "expected nothing placed in the inventory");
        check(c.data.getBonusClaimBlocks() == 500, "expected the sale itself correct: pool 500");

        RecordingHandler.Entry severe = findAtLevel(c, Level.SEVERE, "1 confirmed",
                "4 lost and must be restored by hand");
        check(severe != null, "expected the shrink SEVERE; entries=" + describeEntries(c));

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injSellPayoutListenerMergesThenCancel(boolean mirror)
    {
        Ctx c = setup(mirror, 100_000, 100, true, 1.0, 0);
        c.inv.freeSlotOverride = 0;
        c.world.growDropNumberTo.put(1, 128);
        c.world.cancelDropNumbers.add(2);

        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = service.sell(c.player, 12800);

        check(result != null && !result.ok() && "items-lost".equals(result.messageKey()),
                "expected items-lost, got " + result);
        checkResultTruthfulKeyAndRender(result);
        check(placeholderEquals(result.placeholders(), "lost", 64),
                "expected lost=64, got " + Arrays.toString(result.placeholders()));
        check(sumPlainCurrency(c) == 0, "expected nothing placed in the inventory");

        RecordingHandler.Entry severe = findAtLevel(c, Level.SEVERE, "64 confirmed",
                "64 lost and must be restored by hand");
        check(severe != null, "expected the merge-then-cancel SEVERE; entries=" + describeEntries(c));

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injSellPayoutListenerTeleports(boolean mirror)
    {
        Ctx c = setup(mirror, 1_000_000, 100, true, 1.0, 0);
        c.inv.freeSlotOverride = 0;
        World newWorld = new World("otherworld");
        c.world.teleportPlayerOnDropNumbers.add(1);
        c.world.teleportToWorld = newWorld;
        c.world.teleportToLocation = new Location(newWorld, 10, 20, 30);

        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = service.sell(c.player, 19200);

        check(result != null && result.ok() && "sold".equals(result.messageKey()), "expected sold, got " + result);
        checkResultTruthfulKeyAndRender(result);
        check(!anyAtLevel(c, Level.SEVERE), "expected no SEVERE (nothing lost); entries=" + describeEntries(c));
        check(World.GLOBAL_DROP_LOG.size() == 3, "expected 3 drop calls, got " + World.GLOBAL_DROP_LOG.size());
        check(World.GLOBAL_DROP_LOG.get(0).world == c.world, "expected the first drop on the original world");
        for (int i = 1; i < World.GLOBAL_DROP_LOG.size(); i++)
        {
            check(World.GLOBAL_DROP_LOG.get(i).world == newWorld, "expected drop #" + (i + 1) + " on the new world");
        }

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injSellPayoutKickMidPayout(boolean mirror)
    {
        Ctx c = setup(mirror, 1_000_000, 100, true, 1.0, 0);
        c.inv.freeSlotOverride = 0;
        c.world.kickPlayerOnDropNumbers.add(1);

        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = service.sell(c.player, 19200);

        check(result != null && !result.ok() && "items-lost".equals(result.messageKey()),
                "expected items-lost, got " + result);
        checkResultTruthfulKeyAndRender(result);
        check(World.GLOBAL_DROP_LOG.size() == 1,
                "expected exactly one drop attempted before the kick stopped the loop, got "
                        + World.GLOBAL_DROP_LOG.size());
        check(placeholderEquals(result.placeholders(), "lost", 128),
                "expected lost=128 (only the first 64-chunk delivered), got " + Arrays.toString(result.placeholders()));

        RecordingHandler.Entry severe = findAtLevel(c, Level.SEVERE, "confirmed", "lost and must be restored by hand");
        check(severe != null, "expected a SEVERE naming confirmed/lost; entries=" + describeEntries(c));
        check(!severe.message.toLowerCase(Locale.ROOT).contains("all delivered"),
                "must not claim an un-attempted chunk was delivered");

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injSellPayoutDropThrowsUnconfirmed(boolean mirror)
    {
        Ctx c = setup(mirror, 1_000_000, 100, true, 1.0, 0);
        c.inv.freeSlotOverride = 0;
        // Fixed 2-runs-per-injection contract: mirror=true exercises the Error
        // variant (rethrown by S12), mirror=false the RuntimeException variant
        // (mapped to items-lost) - both named explicitly in the injection spec.
        if (mirror)
        {
            c.world.throwErrorOnDropNumber.put(2,
                    new Error("harness: listener exception propagated from dropItemNaturally"));
        }
        else
        {
            c.world.throwRuntimeOnDropNumber.put(2,
                    new RuntimeException("harness: listener exception propagated from dropItemNaturally"));
        }

        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = null;
        Throwable thrown = null;
        try
        {
            result = service.sell(c.player, 19200);
        }
        catch (Throwable t)
        {
            thrown = t;
        }

        if (mirror)
        {
            check(thrown instanceof Error, "expected the Error rethrown by S12 after deliver logged, got " + thrown);
        }
        else
        {
            check(thrown == null, "the RuntimeException variant must not propagate, got " + thrown);
            check(result != null && !result.ok() && "items-lost".equals(result.messageKey()),
                    "expected items-lost, got " + result);
            checkResultTruthfulKeyAndRender(result);
            check(placeholderEquals(result.placeholders(), "lost", 128),
                    "expected lost=128 (items 192 - delivered 64), got " + Arrays.toString(result.placeholders()));
        }

        RecordingHandler.Entry severe = findAtLevel(c, Level.SEVERE,
                "in a stack whose drop threw and may or may not be on the ground",
                "check the ground at the player's position before restoring");
        check(severe != null, "expected the unconfirmed-stack SEVERE; entries=" + describeEntries(c));

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injInfoWarmAppliesFix(boolean mirror)
    {
        Ctx c = setup(mirror, 200, 100);
        c.data.fixNegativeDeficit = 50;

        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = service.info(c.player);
        List<String> log = CallLog.snapshot();

        check(result != null && result.ok() && "info".equals(result.messageKey()), "expected info, got " + result);
        checkResultTruthfulKeyAndRender(result);
        check(log.stream().noneMatch(e -> e.startsWith("PlayerInventory.addItem")), "info must never touch the inventory");
        check(World.GLOBAL_DROP_LOG.isEmpty(), "info must never drop items");
        check(c.data.setterArguments.isEmpty(), "info must never call setBonusClaimBlocks");
        check(c.store.saveCallLog.isEmpty(), "info must never call savePlayerData");
        check(c.data.getBonusClaimBlocks() == 250, "expected the +50 credit applied once (200+50)");

        Set<String> forbidden = Set.of("transaction-failed", "items-lost", "bought", "sold");
        check(!forbidden.contains(result.messageKey()), "info must never return a trade-failure/success key");

        check(countAtLevel(c, Level.WARNING) == 1,
                "expected exactly one WARNING, got " + countAtLevel(c, Level.WARNING));
        RecordingHandler.Entry warning = findAtLevel(c, Level.WARNING,
                "GriefPrevention corrected Steve's bonus pool from 200 to 250",
                "while preparing a rate lookup", "ClaimBarter did not write this");
        check(warning != null, "expected the warm's credit WARNING; entries=" + describeEntries(c));

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injInfoDataStoreNull(boolean mirror)
    {
        Ctx c = setup(mirror, 200, 100);
        GriefPrevention.instance = null;

        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = service.info(c.player);

        check(result != null && !result.ok() && "data-unavailable".equals(result.messageKey()),
                "expected data-unavailable, got " + result);
        checkResultTruthfulKeyAndRender(result);
        check(!anyAtLevel(c, Level.SEVERE), "expected never SEVERE; entries=" + describeEntries(c));
        RecordingHandler.Entry warning = findAtLevel(c, Level.WARNING, "Refused a rate lookup for");
        check(warning != null, "expected the data-unavailable WARNING; entries=" + describeEntries(c));

        checkSetterNeverThrew(c);
        return null;
    }

    private static String injMetadataStacksNeverSpent(boolean mirror)
    {
        // Scenario 1: an ordinary purchase must skip the metadata stack entirely.
        Ctx c1 = setup(mirror, 500, 100);
        putCurrency(c1, 0, 64, true);
        putCurrency(c1, 1, 10, false);
        BarterService service1 = new BarterService(c1.settings, c1.logger);
        BarterService.Result result1 = service1.buy(c1.player, 5);
        check(result1 != null && result1.ok() && "bought".equals(result1.messageKey()),
                "expected bought (5 <= 10 plain), got " + result1);
        checkResultTruthfulKeyAndRender(result1);
        ItemStack metaSlot = c1.inv.rawSlot(0);
        check(metaSlot != null && metaSlot.getAmount() == 64 && metaSlot.hasItemMeta(),
                "the metadata stack must be untouched");
        ItemStack plainSlot = c1.inv.rawSlot(1);
        check(plainSlot != null && plainSlot.getAmount() == 5,
                "expected 5 plain currency remaining, got " + (plainSlot == null ? "null" : plainSlot.getAmount()));

        // Scenario 2: a forced refund path must still never touch the metadata stack.
        Ctx c2 = setup(mirror, 500, 100);
        putCurrency(c2, 0, 64, true);
        putCurrency(c2, 1, 10, false);
        c2.store.throwOnSavePlayerData = new OutOfMemoryError("harness: forced refund path");
        BarterService service2 = new BarterService(c2.settings, c2.logger);
        try
        {
            service2.buy(c2.player, 5);
        }
        catch (Throwable ignored)
        {
            // expected: the OOM is rethrown once the unwind has run.
        }
        ItemStack metaSlot2 = c2.inv.rawSlot(0);
        check(metaSlot2 != null && metaSlot2.getAmount() == 64 && metaSlot2.hasItemMeta(),
                "the metadata stack must be untouched even on the refund path");
        check(sumPlainCurrency(c2) == 10,
                "expected the plain currency fully refunded back to 10, got " + sumPlainCurrency(c2));

        checkSetterNeverThrew(c1);
        checkSetterNeverThrew(c2);
        return null;
    }

    private static String injNoInventedKeys(boolean mirror)
    {
        // Cross-cutting: every Result key produced by every injection that ran
        // before this one must belong to the exact shipped set and must never
        // render "Missing message". main() runs this last so the accumulator
        // is fully populated. mirror is unused - this is a summary check.
        check(!ALL_KEYS_SEEN.isEmpty(), "expected at least one Result key to have been observed by this point");
        for (String key : ALL_KEYS_SEEN)
        {
            check(VALID_KEYS.contains(key), "NO-INVENTED-KEYS: observed an invented key '" + key + "'");
            String rendered = messages.render(key).rendered();
            check(!rendered.contains("Missing message"), "NO-INVENTED-KEYS: key '" + key + "' renders 'Missing message'");
        }
        // Coverage: every declared key must have been produced and rendered by
        // some injection, or a key could lose its template or placeholders
        // without failing anything.
        Set<String> missing = new HashSet<>(VALID_KEYS);
        missing.removeAll(ALL_KEYS_SEEN);
        check(missing.isEmpty(), "NO-INVENTED-KEYS coverage: keys never produced by any injection: " + missing);
        return null;
    }

    // ------------------------------------------------ added coverage

    /**
     * The lone-recount case, as the maintainer resolved the B9 spec gap on
     * 2026-09-24: removeCurrency returns normally, then only the verification
     * recount throws. removeCurrency's own count of the array it wrote stands
     * in for the recount, so the 40 are refunded instead of withheld as an
     * unknown, and the record says where the figure came from. The recount's
     * throwable is still the failure and is still rethrown.
     */
    private static String injBuyRecountAloneThrows(boolean mirror)
    {
        Ctx c = setup(mirror, 500, 100);
        putCurrency(c, 0, 64);
        c.inv.failNextReadAfterSetStorageContents = true;

        BarterService service = new BarterService(c.settings, c.logger);
        Throwable thrown = null;
        try
        {
            service.buy(c.player, 40);
        }
        catch (Throwable t)
        {
            thrown = t;
        }
        List<String> log = CallLog.snapshot();

        // (a) the recount's Error is what U7 rethrows.
        check(thrown instanceof Error, "expected an Error rethrown, got " + thrown);
        check(thrown.getMessage() != null && thrown.getMessage().contains("getStorageContents"),
                "expected the recount's getStorageContents Error, got " + thrown);

        // (b) the take ran, and its derived count was refunded in full.
        check(sumPlainCurrency(c) == 64, "expected all 64 back (40 taken, 40 refunded), got " + sumPlainCurrency(c));

        // (c) one completed write, and the very next inventory call is the failing recount.
        long setCount = log.stream().filter(e -> e.startsWith("PlayerInventory.setStorageContents")).count();
        check(setCount == 1, "expected exactly one setStorageContents call, got " + setCount + " in " + log);
        int setIdx = indexOfPrefix(log, "PlayerInventory.setStorageContents");
        String nextInv = null;
        for (int i = setIdx + 1; i < log.size(); i++)
        {
            if (log.get(i).startsWith("PlayerInventory."))
            {
                nextInv = log.get(i);
                break;
            }
        }
        check(nextInv != null && nextInv.startsWith("PlayerInventory.getStorageContents#"),
                "expected the next inventory call after the take to be the recount, got " + nextInv);
        String callNo = nextInv.substring("PlayerInventory.getStorageContents#".length(), nextInv.indexOf('['));
        check(thrown.getMessage().contains("call #" + callNo + " "),
                "expected the rethrown Error to come from recount call #" + callNo + ", got " + thrown.getMessage());

        // (d) the refund went into the inventory; nothing needed dropping.
        check(logHas(log, "PlayerInventory.addItem"), "expected the refund to call addItem");
        check(World.GLOBAL_DROP_LOG.isEmpty(), "dropItemNaturally must never be called");

        // (e) nothing granted, nothing saved.
        check(c.data.setterArguments.isEmpty(), "setBonusClaimBlocks must never be called");
        check(c.store.saveCallLog.isEmpty(), "no save expected, got " + c.store.saveCallLog);

        // (f) one truthful operator record.
        check(countAtLevel(c, Level.SEVERE) == 1,
                "expected exactly one SEVERE, got " + countAtLevel(c, Level.SEVERE) + "; entries=" + describeEntries(c));
        RecordingHandler.Entry severe = findAtLevel(c, Level.SEVERE,
                "40 iron ingots requested, 40 taken (by the removal's own count; the recount threw), 40 returned",
                "claim blocks: never granted",
                "durable write: not attempted; nothing of this trade was ever written",
                "Fix by hand: nothing");
        check(severe != null, "expected the lone-recount SEVERE; entries=" + describeEntries(c));
        check(!severe.message.contains("unknown"), "the count is known and must not be reported as unknown");

        // (g) failure is the recount's own throwable, with nothing suppressed.
        check(severe.thrown == thrown, "expected the SEVERE's thrown to be the rethrown recount Error");
        check(severe.suppressed.length == 0,
                "expected no suppressed throwables, got " + Arrays.toString(severe.suppressed));

        checkSetterNeverThrew(c);
        return null;
    }

    /**
     * The PREPARE row when the failing call is getName() itself. The catch
     * used to call getName() again to build its SEVERE, so a persistent throw
     * escaped with no record at all; it now names the player by UUID.
     */
    private static String injPrepareGetNameThrows(boolean mirror)
    {
        Ctx c = setup(mirror, 500, 100, true, 1.0, 0);
        putCurrency(c, 0, 64);
        c.data.remainingOverride = 500;
        c.player.getNameThrows = true;
        UUID uuid = c.player.getUniqueId();
        BarterService service = new BarterService(c.settings, c.logger);

        BarterService.Result bought = service.buy(c.player, 2);
        check(bought != null && !bought.ok() && "transaction-failed".equals(bought.messageKey()),
                "buy: expected transaction-failed, got " + bought);
        checkResultTruthfulKeyAndRender(bought);
        check(findAtLevel(c, Level.SEVERE, "Refused a purchase for " + uuid, "nothing moved") != null,
                "buy: expected the PREPARE SEVERE naming the UUID; entries=" + describeEntries(c));

        BarterService.Result sold = service.sell(c.player, 200);
        check(sold != null && !sold.ok() && "transaction-failed".equals(sold.messageKey()),
                "sell: expected transaction-failed, got " + sold);
        check(findAtLevel(c, Level.SEVERE, "Refused a sale for " + uuid, "nothing moved") != null,
                "sell: expected the PREPARE SEVERE naming the UUID; entries=" + describeEntries(c));

        check(sumPlainCurrency(c) == 64, "nothing may move; currency is " + sumPlainCurrency(c));
        check(c.data.setterArguments.isEmpty(), "setBonusClaimBlocks must never be called");
        check(c.store.saveCallLog.isEmpty(), "no save expected, got " + c.store.saveCallLog);
        checkSetterNeverThrew(c);
        return null;
    }

    /**
     * A drop that returns no entity, or an entity whose stack reads null,
     * cannot be confirmed. deliver's null checks must credit it as zero and
     * carry on with the next stack, not throw a NullPointerException mid-payout.
     * 192 items at 64 per stack is three drops; the second is unconfirmable.
     */
    private static String injSellPayoutDropUnconfirmable(boolean mirror, boolean nullStack)
    {
        Ctx c = setup(mirror, 1_000_000, 100, true, 1.0, 0);
        c.inv.freeSlotOverride = 0;
        if (nullStack)
        {
            c.world.nullStackOnDropNumbers.add(2);
        }
        else
        {
            c.world.returnNullOnDropNumbers.add(2);
        }

        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = service.sell(c.player, 19200);

        check(result != null && !result.ok() && "items-lost".equals(result.messageKey()),
                "expected items-lost, got " + result);
        checkResultTruthfulKeyAndRender(result);
        check(placeholderEquals(result.placeholders(), "lost", 64),
                "expected lost=64 (the unconfirmable stack), got " + Arrays.toString(result.placeholders()));
        check(c.world.dropCallCount() == 3, "expected all three stacks handed over, got " + c.world.dropCallCount());
        RecordingHandler.Entry severe = findAtLevel(c, Level.SEVERE,
                "128 confirmed (0 into the inventory, 128 dropped at their feet)", "0 unconfirmed", "64 lost");
        check(severe != null, "expected the payout shortfall SEVERE; entries=" + describeEntries(c));
        check(severe.thrown == null, "nothing threw, so no throwable may be attached, got " + severe.thrown);

        checkSetterNeverThrew(c);
        return null;
    }

    /**
     * GriefPrevention's lazy load throwing inside the warm, before anything
     * is prepared. The warm is deliberately unguarded: nothing ClaimBarter
     * owns has moved, so the throw belongs to the dispatcher. Mirror runs the
     * Error, copy the RuntimeException.
     */
    private static String injLazyLoadThrows(boolean mirror)
    {
        for (String op : List.of("buy", "sell", "info"))
        {
            Ctx c = setup(mirror, (Integer) null, 100, true, 1.0, 0);
            putCurrency(c, 0, 64);
            Throwable planted = mirror
                    ? new Error("harness: lazy load threw")
                    : new RuntimeException("harness: lazy load threw");
            if (mirror)
            {
                c.data.lazyLoadThrowsError = (Error) planted;
            }
            else
            {
                c.data.lazyLoadThrowsRuntime = (RuntimeException) planted;
            }

            BarterService service = new BarterService(c.settings, c.logger);
            Throwable thrown = null;
            try
            {
                switch (op)
                {
                    case "buy" -> service.buy(c.player, 2);
                    case "sell" -> service.sell(c.player, 100);
                    default -> service.info(c.player);
                }
            }
            catch (Throwable t)
            {
                thrown = t;
            }

            check(thrown == planted, op + ": expected the lazy load's own throwable to propagate, got " + thrown);
            check(sumPlainCurrency(c) == 64, op + ": nothing may move; currency is " + sumPlainCurrency(c));
            check(c.data.setterArguments.isEmpty(), op + ": setBonusClaimBlocks must never be called");
            check(c.store.saveCallLog.isEmpty(), op + ": no save expected, got " + c.store.saveCallLog);
            check(World.GLOBAL_DROP_LOG.isEmpty(), op + ": nothing may be dropped");
            checkSetterNeverThrew(c);
        }
        return null;
    }

    /**
     * The B2 refusal count is the first inventory read and runs before
     * anything is prepared; its throw propagates and nothing moves.
     */
    private static String injBuyFirstCountThrows(boolean mirror)
    {
        Ctx c = setup(mirror, 500, 100);
        putCurrency(c, 0, 64);
        c.inv.throwOnGetStorageContentsCallN = 1;

        BarterService service = new BarterService(c.settings, c.logger);
        Throwable thrown = null;
        try
        {
            service.buy(c.player, 2);
        }
        catch (Throwable t)
        {
            thrown = t;
        }

        check(thrown instanceof Error && thrown.getMessage().contains("call #1 "),
                "expected the first count's Error to propagate, got " + thrown);
        check(sumPlainCurrency(c) == 64, "nothing may move; currency is " + sumPlainCurrency(c));
        check(c.data.setterArguments.isEmpty(), "setBonusClaimBlocks must never be called");
        check(c.store.saveCallLog.isEmpty(), "no save expected, got " + c.store.saveCallLog);
        checkSetterNeverThrew(c);
        return null;
    }

    /** The permitted half of invariant #5: the in-range relative undo may box fresh. */
    private static String injBuyUndoRelativeInRange(boolean mirror)
    {
        Ctx c = setup(mirror, 500, 100);
        putCurrency(c, 0, 64);
        c.store.throwOnSavePlayerData = new OutOfMemoryError("harness: native thread creation failed");
        c.data.armForeignWriteAfterSetterCalls(1, 37);

        BarterService service = new BarterService(c.settings, c.logger);
        Throwable thrown = null;
        try
        {
            service.buy(c.player, 2);
        }
        catch (Throwable t)
        {
            thrown = t;
        }

        check(thrown instanceof OutOfMemoryError, "expected OutOfMemoryError rethrown, got " + thrown);
        check(c.data.setterArguments.equals(List.of(700, 537)),
                "expected setter arguments [700, 537], got " + c.data.setterArguments);
        check(c.data.setterAllocBytes.get(1) > 0,
                "positive control: the fresh relative box read 0 bytes, so the allocation counter is blind");
        checkSetterNeverThrew(c, 1);
        check(c.data.getBonusClaimBlocks() == 537, "expected live pool 537 (foreign +37 kept)");
        check(sumPlainCurrency(c) == 64, "expected currency refunded to 64, got " + sumPlainCurrency(c));
        String lastSave = c.store.saveCallLog.get(c.store.saveCallLog.size() - 1);
        check("savePlayerDataSync(537)".equals(lastSave), "expected last save savePlayerDataSync(537), got "
                + c.store.saveCallLog);

        RecordingHandler.Entry severe = findAtLevel(c, Level.SEVERE,
                "2 taken, 2 returned",
                "granted 200 and undone in memory, pool now 537",
                "Verify the player's file: if it shows bonus=700");
        check(severe != null, "expected the relative-undo SEVERE; entries=" + describeEntries(c));
        return null;
    }

    /** The permitted half of invariant #5 on the sell side. */
    private static String injSellUndoRelativeInRange(boolean mirror)
    {
        Ctx c = setup(mirror, 1000, 100, true, 1.0, 0);
        c.store.throwOnSavePlayerData = new OutOfMemoryError("harness: native thread creation failed");
        c.data.armForeignWriteAfterSetterCalls(1, 37);

        BarterService service = new BarterService(c.settings, c.logger);
        Throwable thrown = null;
        try
        {
            service.sell(c.player, 500);
        }
        catch (Throwable t)
        {
            thrown = t;
        }
        List<String> log = CallLog.snapshot();

        check(thrown instanceof OutOfMemoryError, "expected OutOfMemoryError rethrown, got " + thrown);
        check(c.data.setterArguments.equals(List.of(500, 1037)),
                "expected setter arguments [500, 1037], got " + c.data.setterArguments);
        check(c.data.setterAllocBytes.get(1) > 0,
                "positive control: the fresh relative box read 0 bytes, so the allocation counter is blind");
        checkSetterNeverThrew(c, 1);
        check(c.data.getBonusClaimBlocks() == 1037, "expected live pool 1037 (foreign +37 kept)");
        check(!logHas(log, "PlayerInventory.addItem"), "no items must be paid out");
        check(World.GLOBAL_DROP_LOG.isEmpty(), "no items must be dropped");

        RecordingHandler.Entry severe = findAtLevel(c, Level.SEVERE,
                "deducted 500 claim blocks in memory, undone, pool now 1037 (was 1000)",
                "Verify the player's file: if it shows bonus=500");
        check(severe != null, "expected the relative-undo SEVERE; entries=" + describeEntries(c));
        return null;
    }

    /** B1/S1: a non-positive amount is refused before anything is read. */
    private static String injBuyRefuseInvalidAmount(boolean mirror)
    {
        Ctx c = setup(mirror, 500, 100);
        putCurrency(c, 0, 64);
        CallLog.reset();
        BarterService service = new BarterService(c.settings, c.logger);
        for (int amount : new int[] {0, -1})
        {
            checkInvalidAmount(c, service.buy(c.player, amount), "buy(" + amount + ")");
        }
        check(sumPlainCurrency(c) == 64, "expected currency unchanged at 64, got " + sumPlainCurrency(c));
        checkSetterNeverThrew(c);

        Ctx s = setup(mirror, 1000, 100, true, 1.0, 0);
        CallLog.reset();
        BarterService sellService = new BarterService(s.settings, s.logger);
        for (int amount : new int[] {0, -5})
        {
            checkInvalidAmount(s, sellService.sell(s.player, amount), "sell(" + amount + ")");
        }
        checkSetterNeverThrew(s);
        return null;
    }

    private static void checkInvalidAmount(Ctx c, BarterService.Result result, String call)
    {
        check(result != null && !result.ok(), call + ": expected a failing result, got " + result);
        check("invalid-amount".equals(result.messageKey()), call + ": expected invalid-amount, got "
                + result.messageKey());
        check(result.placeholders().length == 0, call + ": expected no placeholders, got "
                + Arrays.toString(result.placeholders()));
        checkResultTruthfulKeyAndRender(result);
        List<String> log = CallLog.snapshot();
        check(!logHas(log, "PlayerInventory.getStorageContents") && !logHas(log, "DataStore.getPlayerData")
                        && !logHas(log, "PlayerData."),
                call + ": nothing may be read before the amount check, got " + log);
        check(c.data.setterArguments.isEmpty(), call + ": setBonusClaimBlocks must never be called");
        check(c.store.saveCallLog.isEmpty(), call + ": no save expected");
        check(c.handler.entries.isEmpty(), call + ": expected nothing logged, got " + describeEntries(c));
    }

    /** B6: the purchase ceiling, and its strictly-greater boundary. */
    private static String injBuyRefuseLimitReached(boolean mirror)
    {
        Ctx c = setup(mirror, 500, 100, true, 0.5, 600);
        putCurrency(c, 0, 64);
        BarterService service = new BarterService(c.settings, c.logger);
        BarterService.Result result = service.buy(c.player, 2);
        List<String> log = CallLog.snapshot();

        check(result != null && !result.ok() && "limit-reached".equals(result.messageKey()),
                "expected limit-reached, got " + result);
        check(placeholderEquals(result.placeholders(), "limit", 600),
                "expected limit=600, got " + Arrays.toString(result.placeholders()));
        checkResultTruthfulKeyAndRender(result);
        String rendered = render(result);
        check(rendered.contains("600") && !rendered.contains("{"), "expected '600' rendered, got " + rendered);
        check(sumPlainCurrency(c) == 64, "expected currency unchanged at 64, got " + sumPlainCurrency(c));
        check(c.data.setterArguments.isEmpty(), "setBonusClaimBlocks must never be called");
        check(c.store.saveCallLog.isEmpty(), "no save expected");
        check(!logHas(log, "PlayerInventory.setStorageContents"), "removeCurrency must never run");
        check(c.handler.entries.isEmpty(), "expected nothing logged, got " + describeEntries(c));
        checkSetterNeverThrew(c);

        Ctx b = setup(mirror, 500, 100, true, 0.5, 600);
        putCurrency(b, 0, 64);
        BarterService boundaryService = new BarterService(b.settings, b.logger);
        BarterService.Result boundary = boundaryService.buy(b.player, 1);
        check(boundary != null && boundary.ok() && "bought".equals(boundary.messageKey()),
                "expected bought at exactly the ceiling (600 is not > 600), got " + boundary);
        checkResultTruthfulKeyAndRender(boundary);
        check(b.data.getBonusClaimBlocks() == 600, "expected live pool 600");
        check(b.store.saveCallLog.equals(List.of("savePlayerData(600)")),
                "expected saveCallLog [savePlayerData(600)], got " + b.store.saveCallLog);
        checkSetterNeverThrew(b);
        return null;
    }

    /** S5: the first pool guard, including a negative pool. */
    private static String injSellRefuseNotEnoughBlocks(boolean mirror)
    {
        Ctx c = setup(mirror, 300, 100, true, 1.0, 0);
        BarterService.Result result = new BarterService(c.settings, c.logger).sell(c.player, 500);
        checkNotEnoughBlocks(c, result, 300);
        check(render(result).contains("300"), "expected '300' rendered, got " + render(result));

        Ctx n = setup(mirror, -50, 100, true, 1.0, 0);
        BarterService.Result negative = new BarterService(n.settings, n.logger).sell(n.player, 1);
        checkNotEnoughBlocks(n, negative, -50);
        return null;
    }

    private static void checkNotEnoughBlocks(Ctx c, BarterService.Result result, int have)
    {
        List<String> log = CallLog.snapshot();
        check(result != null && !result.ok() && "not-enough-blocks".equals(result.messageKey()),
                "expected not-enough-blocks, got " + result);
        check(placeholderEquals(result.placeholders(), "have", have),
                "expected have=" + have + ", got " + Arrays.toString(result.placeholders()));
        checkResultTruthfulKeyAndRender(result);
        check(log.contains("PlayerData.getClaims#1"), "expected the S4 warm (getClaims#1) to run, got " + log);
        check(!logHas(log, "PlayerData.getRemainingClaimBlocks"), "S5 must refuse before S6 reads availability");
        check(c.data.setterArguments.isEmpty(), "setBonusClaimBlocks must never be called");
        check(c.store.saveCallLog.isEmpty(), "no save expected");
        check(!logHas(log, "PlayerInventory.addItem"), "no items must be paid out");
        check(World.GLOBAL_DROP_LOG.isEmpty(), "no items must be dropped");
        check(c.handler.entries.isEmpty(), "expected nothing logged, got " + describeEntries(c));
        checkSetterNeverThrew(c);
    }

    /** S6: blocks committed to claims, including GriefPrevention's overflow mapped to 0. */
    private static String injSellRefuseBlocksInUse(boolean mirror)
    {
        for (int available : new int[] {200, 0})
        {
            Ctx c = setup(mirror, 1000, 100, true, 1.0, 0);
            c.data.remainingOverride = available;
            BarterService.Result result = new BarterService(c.settings, c.logger).sell(c.player, 500);
            List<String> log = CallLog.snapshot();

            check(result != null && !result.ok() && "blocks-in-use".equals(result.messageKey()),
                    "expected blocks-in-use, got " + result);
            check(placeholderEquals(result.placeholders(), "available", available),
                    "expected available=" + available + ", got " + Arrays.toString(result.placeholders()));
            checkResultTruthfulKeyAndRender(result);
            if (available == 200)
            {
                check(render(result).contains("200"), "expected '200' rendered, got " + render(result));
            }
            int warmIdx = log.indexOf("PlayerData.getClaims#1");
            int remainingIdx = indexOfPrefix(log, "PlayerData.getRemainingClaimBlocks");
            check(warmIdx >= 0 && remainingIdx > warmIdx,
                    "expected the warm (getClaims#1) before getRemainingClaimBlocks, got " + log);
            check(c.data.setterArguments.isEmpty(), "setBonusClaimBlocks must never be called");
            check(c.store.saveCallLog.isEmpty(), "no save expected");
            check(!logHas(log, "PlayerInventory.addItem"), "no items must be paid out");
            check(World.GLOBAL_DROP_LOG.isEmpty(), "no items must be dropped");
            check(c.handler.entries.isEmpty(), "expected nothing logged, got " + describeEntries(c));
            check(c.data.getBonusClaimBlocks() == 1000, "expected pool still 1000");
            checkSetterNeverThrew(c);
        }
        return null;
    }

    /** S7: the round-down refusal, and its boundary. */
    private static String injSellRefuseAmountTooSmall(boolean mirror)
    {
        Ctx c = setup(mirror, 1000, 100, true, 0.5, 0);
        c.data.remainingOverride = 1000;
        BarterService.Result result = new BarterService(c.settings, c.logger).sell(c.player, 199);
        List<String> log = CallLog.snapshot();

        check(result != null && !result.ok() && "amount-too-small".equals(result.messageKey()),
                "expected amount-too-small, got " + result);
        // Uninflected: the template itself supplies "a single".
        check(placeholderStringEquals(result.placeholders(), "item", c.settings.currencyName()),
                "expected item='" + c.settings.currencyName() + "', got " + Arrays.toString(result.placeholders()));
        check("iron ingot".equals(c.settings.currencyName()), "expected currencyName() 'iron ingot'");
        checkResultTruthfulKeyAndRender(result);
        check(render(result).contains("a single iron ingot"),
                "expected 'a single iron ingot' rendered, got " + render(result));
        check(c.data.setterArguments.isEmpty(), "setBonusClaimBlocks must never be called");
        check(c.store.saveCallLog.isEmpty(), "no save expected");
        check(!logHas(log, "PlayerInventory.addItem"), "no items must be paid out");
        check(World.GLOBAL_DROP_LOG.isEmpty(), "no items must be dropped");
        check(c.data.getBonusClaimBlocks() == 1000, "expected pool still 1000");
        check(c.handler.entries.isEmpty(), "expected nothing logged, got " + describeEntries(c));
        checkSetterNeverThrew(c);

        Ctx b = setup(mirror, 1000, 100, true, 0.5, 0);
        b.data.remainingOverride = 1000;
        BarterService.Result boundary = new BarterService(b.settings, b.logger).sell(b.player, 200);
        check(boundary != null && boundary.ok() && "sold".equals(boundary.messageKey()),
                "expected sold at 200 blocks (exactly 1 item), got " + boundary);
        checkResultTruthfulKeyAndRender(boundary);
        check(placeholderEquals(boundary.placeholders(), "items", 1),
                "expected items=1, got " + Arrays.toString(boundary.placeholders()));
        check(b.data.getBonusClaimBlocks() == 800, "expected pool 800");
        check(sumPlainCurrency(b) == 1, "expected one plain currency paid out, got " + sumPlainCurrency(b));
        checkSetterNeverThrew(b);
        return null;
    }

    /** S1: selling-disabled is checked before the amount and before the store. */
    private static String injSellRefuseSellingDisabled(boolean mirror)
    {
        for (int amount : new int[] {500, 0})
        {
            Ctx c = setup(mirror, 1000, 100, false, 1.0, 0);
            CallLog.reset();
            BarterService.Result result = new BarterService(c.settings, c.logger).sell(c.player, amount);
            List<String> log = CallLog.snapshot();

            check(result != null && !result.ok() && "selling-disabled".equals(result.messageKey()),
                    "sell(" + amount + "): expected selling-disabled (S1 order), got " + result);
            check(result.placeholders().length == 0,
                    "expected no placeholders, got " + Arrays.toString(result.placeholders()));
            checkResultTruthfulKeyAndRender(result);
            check(log.isEmpty(), "sell(" + amount + "): expected an empty call log, got " + log);
            check(c.data.setterArguments.isEmpty(), "setBonusClaimBlocks must never be called");
            check(c.store.saveCallLog.isEmpty(), "no save expected");
            check(c.handler.entries.isEmpty(), "expected nothing logged, got " + describeEntries(c));
            checkSetterNeverThrew(c);
        }
        return null;
    }

    // ------------------------------------------------------- config.yml parsing

    /**
     * Parses only the top-level {@code messages:} block of config.yml, as
     * simple {@code key: "value"} lines - no YAML library, per the plan. Every
     * other top-level key is ignored; BarterSettings is built directly through
     * its record constructor instead of through BarterSettings.from(...).
     */
    private static ConfigurationSection loadMessagesSection(String configPath) throws IOException
    {
        List<String> lines = Files.readAllLines(Path.of(configPath), StandardCharsets.UTF_8);
        ConfigurationSection section = new ConfigurationSection();
        boolean inMessages = false;
        for (String raw : lines)
        {
            if (!inMessages)
            {
                if (raw.matches("^messages:\\s*$"))
                {
                    inMessages = true;
                }
                continue;
            }
            if (raw.isBlank())
            {
                continue;
            }
            if (!raw.startsWith(" ") && !raw.startsWith("\t"))
            {
                break; // dedent: end of the messages section
            }
            String trimmed = raw.strip();
            if (trimmed.startsWith("#"))
            {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0)
            {
                continue;
            }
            String key = trimmed.substring(0, colon).strip();
            String rest = trimmed.substring(colon + 1).strip();
            String value;
            if (rest.length() >= 2 && rest.startsWith("\"") && rest.endsWith("\""))
            {
                value = rest.substring(1, rest.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
            }
            else
            {
                value = rest;
            }
            section.set(key, value);
        }
        return section;
    }
}
