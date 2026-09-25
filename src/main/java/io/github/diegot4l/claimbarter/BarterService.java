package io.github.diegot4l.claimbarter;

import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.PlayerData;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every trade between items and GriefPrevention claim blocks.
 *
 * <p>The promises below are the only ones this class makes. Each is worded to
 * what GriefPrevention 16.18.7's bytecode supports rather than to what would
 * be convenient to believe, because an operator who trusts a promise the code
 * cannot keep stops looking exactly where the damage is.
 *
 * <p>A trade that cannot be completed is refused before anything moves: every
 * read, every ceiling check and the pool re-read all happen before the first
 * mutation. A refusal needs no unwinding, so everything decided up front is
 * something that cannot depend on compensation that might itself fail.
 *
 * <p>On a purchase the items leave the inventory before the blocks are
 * granted; on a sale the blocks are deducted and their save started before any
 * item is handed over. A failure between the two costs the plugin, never the
 * server.
 *
 * <p>Every allocation the never-mint compensation can need is made before the
 * first item moves, so undoing a grant or a deduction in memory is a bare
 * field write that cannot fail. PlayerData.setBonusClaimBlocks(Integer) is
 * aload_0/aload_1/putfield/return, so once the Integer exists the write cannot
 * throw. This matters because the realistic failure in a trade is an
 * OutOfMemoryError, and an undo that allocated could fail for the very reason
 * the trade did.
 *
 * <p>The never-lose-items compensation allocates by nature. It is guarded and
 * measured instead of assumed, and the two invariants are reasoned about
 * separately. Handing items back builds stacks and fires other plugins'
 * events, so pretending it cannot fail would only hide the count of what did
 * not arrive.
 *
 * <p>Compensation is driven by a ledger of what actually landed - each flag
 * set on the line after the call it records, each number measured after the
 * fact - and every compensation step, log statement and addSuppressed is
 * individually guarded so no step's failure can skip a later one. An undo
 * applied for a mutation that never happened is itself the bug: it subtracts
 * blocks that were never added, or refunds items that were never taken.
 *
 * <p>Before the pool is read, {@link #warmClaimData} makes GriefPrevention
 * run its own pool correction on this thread, so no save thread a trade
 * starts can write the pool behind the trade's back.
 *
 * <p>savePlayerDataSync is used only on the failure path, never on the trade
 * itself. On every trade it would put file I/O on the main thread, a stall
 * felt by everyone on the server, to guard a path that only opens when
 * something has already gone wrong. On the failure path it runs rarely, and
 * leaving the correction in memory alone would leave the file holding
 * whatever the last save serialized. What that write is and is not ordered
 * against is on {@link #persistRollback}.
 *
 * <p>No number is reported to a player that the plugin did not measure. Where
 * no configured message states the outcome truthfully, the plugin throws
 * rather than bending a key or adding one. A new key would render as a missing
 * message on every server whose config.yml predates it, because
 * saveDefaultConfig never overwrites; the server's generic error asserts
 * nothing, so it cannot be false.
 *
 * <p>What this does not promise is stated as plainly, because the limits live
 * in GriefPrevention rather than here.
 *
 * <p><b>A player-data write failure is invisible.</b> Both GriefPrevention
 * backends catch around the write and only log - FlatFileDataStore catches
 * Exception, DatabaseDataStore catches SQLException - so savePlayerDataSync
 * changes ordering and never observability, and nothing here ever claims what
 * the file holds.
 *
 * <p><b>An unreadable player file looks like an empty pool.</b> A lazy load
 * that cannot parse the player file yields bonus=0 after five retries, and
 * this plugin cannot tell that from a genuinely empty pool. The per-trade INFO
 * line, which names the pool before and after, is the only handle an operator
 * has for spotting it.
 *
 * <p><b>The pool is shared, unguarded state.</b> bonusClaimBlocks is a
 * non-volatile Integer with an unsynchronized getter and setter, and
 * GriefPrevention writes it too. That is why every undo is decided from a
 * fresh read of the pool rather than from the value read at the top.
 */
final class BarterService
{
    /**
     * Stands in for a count the plugin could not measure.
     *
     * <p>The ledger is kept in primitives so the unwind does not allocate,
     * which rules out a nullable Integer. An item count cannot be negative, so
     * there the sentinel is unambiguous. A bonus pool can legitimately be -1,
     * so a pool of -1 in a log line is ambiguous; the pool value is only ever
     * printed, never acted on, so the ambiguity cannot move anything.
     */
    private static final int UNKNOWN = -1;

    private final BarterSettings settings;
    private final Logger logger;

    BarterService(BarterSettings settings, Logger logger)
    {
        this.settings = settings;
        this.logger = logger;
    }

    /** The outcome of a trade, resolved into a message by the caller. */
    record Result(boolean ok, String messageKey, Object[] placeholders)
    {
        static Result fail(String key, Object... placeholders)
        {
            return new Result(false, key, placeholders);
        }

        static Result ok(String key, Object... placeholders)
        {
            return new Result(true, key, placeholders);
        }
    }

    /**
     * What one handover of items achieved, filled in by {@link #deliver}.
     *
     * <p>Created while a trade is being prepared, before anything moves, so
     * reporting a refund never needs a fresh object in the middle of an unwind
     * that may itself be running out of heap. Mutable fields rather than a
     * returned value for the same reason.
     */
    private static final class Handover
    {
        /**
         * Items that entered the inventory or existed as a valid entity when
         * read - not items proven to be in the player's possession.
         */
        int delivered;

        /**
         * The one stack whose drop call threw. It may or may not be on the
         * ground, so it is not counted as delivered.
         */
        int unconfirmed;

        /** What went wrong during the handover; deliver records it rather than throwing. */
        Throwable failure;
    }

    /**
     * Trades items for claim blocks.
     *
     * <p>How many items left the inventory is measured by recounting it, not
     * trusted from removeCurrency, because the measurement has to survive a
     * throw inside setStorageContents - which is exactly when the answer is not
     * the amount requested. When only the recount throws, removeCurrency's own
     * count of the array it wrote is used instead, and the record says so.
     *
     * <p>A grant that cannot be undone skips the refund, because handing the
     * items back beside a live grant is a mint. The items are then the payment
     * for a purchase that completed but was never saved, so it is persisted as
     * one and the operator is told how to check it. An unknown number taken
     * skips the refund too: refunding a guess is either loss or duplication,
     * and the plugin cannot tell which.
     *
     * <p>An Error is not rethrown once every compensation step has run. The
     * Bukkit dispatcher catches Throwable anyway, so rethrowing would only
     * swap the measured outcome for the server's generic error and log the
     * same stack twice; the SEVERE above already carries it.
     */
    Result buy(Player player, int items)
    {
        if (items <= 0)
        {
            return Result.fail("invalid-amount");
        }

        PlayerInventory inventory = player.getInventory();
        int held = countCurrency(inventory);
        if (held < items)
        {
            return Result.fail("not-enough-items",
                    "needed", items, "have", held, "item", settings.currencyName(items));
        }

        DataStore store = dataStore("a purchase", player);
        if (store == null)
        {
            return Result.fail("data-unavailable");
        }

        // Widened to long so the ceiling checks below cannot themselves overflow.
        long blocks = (long) items * settings.blocksPerItem();
        UUID playerId = player.getUniqueId();
        // Fetched once and held for the whole call. clearCachedPlayerData can
        // orphan a later fetch, and an undo written to an orphaned object
        // corrects nothing GriefPrevention will ever save.
        PlayerData data = store.getPlayerData(playerId);
        int bonusBefore = warmClaimData(data, player, "a purchase");

        // A negative pool, which GriefPrevention's own commands can create,
        // lets blocks exceed int range while updated does not. A grant that
        // size could not be expressed as one int correction, by the undo or by
        // an operator running /adjustbonusclaimblocks.
        if (blocks > Integer.MAX_VALUE)
        {
            return Result.fail("overflow");
        }
        long updated = (long) bonusBefore + blocks;
        if (updated > Integer.MAX_VALUE)
        {
            return Result.fail("overflow");
        }
        if (settings.maxPurchasedBlocks() > 0 && updated > settings.maxPurchasedBlocks())
        {
            return Result.fail("limit-reached", "limit", settings.maxPurchasedBlocks());
        }

        // Everything the unwind can need is allocated here, while a failure
        // still costs nothing: both Integers the setter will receive, the
        // refund's result object, the name every log line uses and the count
        // the take is measured against.
        Integer grantBox;
        Integer restoreBox;
        Handover refund;
        String name;
        int beforeTake;
        try
        {
            grantBox = Integer.valueOf((int) updated);
            restoreBox = Integer.valueOf(bonusBefore);
            refund = new Handover();
            name = player.getName();
            beforeTake = countCurrency(inventory);
            // A refusal, not a rollback: a pool that moved before anything
            // was charged is left exactly as it is, so a foreign change can
            // never be overwritten with a stale total.
            if (data.getBonusClaimBlocks() != bonusBefore)
            {
                logger.warning(name + "'s bonus pool changed from " + bonusBefore
                        + " while preparing a purchase; the purchase was refused and nothing was charged");
                return Result.fail("transaction-failed");
            }
        }
        catch (Throwable prepFailure)
        {
            return refuseUnprepared("a purchase", playerId, prepFailure);
        }

        int itemsTaken = 0;
        int removed = UNKNOWN;
        boolean recountFailed = false;
        boolean blocksMutated = false;
        boolean poolRestored = false;
        int returned = 0;
        Throwable failure = null;
        Throwable undoFailure = null;
        Throwable persistFailure = null;

        try
        {
            removed = removeCurrency(inventory, items);
        }
        catch (Throwable thrown)
        {
            failure = thrown;
        }
        // Its own try, so the count is still attempted after a throw above;
        // that is the case where it matters.
        try
        {
            itemsTaken = beforeTake - countCurrency(inventory);
        }
        catch (Throwable thrown)
        {
            // A removal that returned wrote exactly the array it counted, so
            // its own figure stands in for the recount. Only when the removal
            // threw as well is the number truly unknown, and only then is the
            // refund skipped: withholding a known count would be a sure loss
            // to avoid an impossible duplication.
            itemsTaken = removed;
            recountFailed = true;
            if (failure == null)
            {
                failure = thrown;
            }
        }
        if (failure == null && itemsTaken != items)
        {
            failure = new IllegalStateException("the inventory changed between the count and the removal");
        }

        if (failure == null)
        {
            try
            {
                data.setBonusClaimBlocks(grantBox);
                blocksMutated = true;
                // GriefPrevention does not persist on its own. DataStore.java:1031
                // is explicit: "MUST be called after you're done making changes,
                // otherwise a reload will lose them."
                store.savePlayerData(playerId, data);
            }
            catch (Throwable thrown)
            {
                // Throwable, not RuntimeException. The setter is a bare
                // putfield against 16.18.7; the realistic throw is
                // OutOfMemoryError from new SavePlayerDataThread or
                // Thread.start, after which no thread exists and nothing of
                // this trade is on disk. The items are already gone by here,
                // so nothing may skip the unwind.
                failure = thrown;
            }
        }

        if (failure == null)
        {
            warnIfForeignWrite(data, name, "purchase", (int) updated);
            // The only handle an operator has for reconciling a write failure
            // GriefPrevention swallowed against a specific trade.
            try
            {
                logger.info(name + " bought " + blocks + " claim blocks for " + items + " "
                        + settings.currencyName(items) + "; bonus pool " + bonusBefore + " -> " + updated);
            }
            catch (Throwable ignored)
            {
                // Diagnostics only; the trade stands either way.
            }
            return Result.ok("bought",
                    "blocks", blocks, "items", items, "item", settings.currencyName(items));
        }

        // The pool is undone before the refund, so no ItemSpawnEvent listener
        // fired by the refund can trigger a GriefPrevention save while the
        // grant is still live.
        if (blocksMutated)
        {
            try
            {
                poolRestored = undo(data, (int) updated, restoreBox, -blocks);
            }
            catch (Throwable thrown)
            {
                undoFailure = thrown;
            }
        }
        else
        {
            // Nothing was granted, so there is nothing to restore.
            poolRestored = true;
        }

        boolean refundSkipped = (blocksMutated && !poolRestored) || itemsTaken == UNKNOWN;
        if (!refundSkipped && itemsTaken > 0)
        {
            // deliver never throws; the guard is so a future change to it
            // cannot strand the durable write and the summary below.
            try
            {
                deliver(player, itemsTaken, "refund for a failed purchase", refund);
                returned = refund.delivered;
            }
            catch (Throwable thrown)
            {
                refund.failure = combine(refund.failure, thrown);
            }
        }

        // After the refund, so a stalled synchronous write cannot delay the
        // player's items. Runs whether or not the pool was restored: restored,
        // it writes the correction; not restored, it writes the grant that
        // stands, because the items were kept as its payment.
        if (blocksMutated)
        {
            persistFailure = persistRollback(store, playerId, data);
        }

        int liveAfter = readPool(data);

        suppress(failure, undoFailure);
        suppress(failure, refund.failure);
        suppress(failure, persistFailure);

        // One record, composed last and from measured values only, so it
        // describes the state every step above actually left behind.
        try
        {
            String blocksReport = !blocksMutated
                    ? "never granted"
                    : poolRestored
                            ? "granted " + blocks + " and undone in memory, pool now " + liveAfter
                            : "granted " + blocks + " and NOT undone, pool now " + liveAfter
                                    + ", should be " + bonusBefore + " if nothing else wrote it";
            String writeReport = !blocksMutated
                    ? "not attempted; nothing of this trade was ever written"
                    : persistFailure != null
                            ? "the synchronous write threw"
                            : "the synchronous write returned; GriefPrevention swallows write failures,"
                                    + " so this does not confirm what the file holds";
            StringBuilder fix = new StringBuilder();
            if (itemsTaken == UNKNOWN)
            {
                clause(fix, "Inspect the inventory: up to " + items + " " + settings.currencyName(items)
                        + " may be missing and were not refunded automatically.");
            }
            if (itemsTaken != UNKNOWN && !refundSkipped && returned < itemsTaken)
            {
                clause(fix, "Give back " + (itemsTaken - returned) + " "
                        + settings.currencyName(itemsTaken - returned)
                        + "; if a stack was reported as unconfirmed above, check the ground at the"
                        + " player's position first.");
            }
            if (refundSkipped && blocksMutated && !poolRestored)
            {
                clause(fix, "The purchase stands: the items are the payment and the blocks were kept."
                        + " If the player's file does not show bonus=" + updated
                        + " after their next save, run /adjustbonusclaimblocks " + name + " " + blocks + ".");
            }
            // Whenever a grant was applied and undone, the disk may still be
            // wrong: a save already in flight can have serialized the grant.
            // Leaving this out is what would make the record a false all-clear.
            if (blocksMutated && poolRestored)
            {
                clause(fix, "Verify the player's file: if it shows bonus=" + updated
                        + ", a save that was already in flight serialized the abandoned total;"
                        + " run /adjustbonusclaimblocks " + name + " -" + blocks + ".");
            }
            if (fix.length() == 0)
            {
                fix.append("nothing");
            }
            logger.log(Level.SEVERE, "Purchase failed for " + name + " (" + playerId + "): "
                    + items + " " + settings.currencyName(items) + " requested, "
                    + (itemsTaken == UNKNOWN ? "an unknown number" : String.valueOf(itemsTaken)) + " taken"
                    + (recountFailed && itemsTaken != UNKNOWN ? " (by the removal's own count; the recount threw)" : "")
                    + ", "
                    + (refundSkipped ? "0 returned (the refund was deliberately skipped)" : returned + " returned")
                    + "; claim blocks: " + blocksReport
                    + "; durable write: " + writeReport
                    + ". Fix by hand: " + fix, failure);
        }
        catch (Throwable ignored)
        {
            // java.util.logging allocates. Every step that changes state has
            // already run, so a lost record strands nothing.
        }

        // Errors included: see the javadoc. Only an outcome no configured
        // message can state truthfully still throws.
        if (refundSkipped)
        {
            throw new IllegalStateException(
                    "the purchase is in a state no configured message describes truthfully", failure);
        }
        return returned < itemsTaken
                ? Result.fail("items-lost",
                        "lost", itemsTaken - returned, "item", settings.currencyName(itemsTaken - returned))
                : Result.fail("transaction-failed");
    }

    /**
     * Trades claim blocks back for items.
     *
     * <p>Only the bonus pool is sellable. Accrued blocks are earned by playing,
     * and letting those be cashed out would turn idle time into an infinite
     * item faucet. The same check refuses the negative pools GriefPrevention's
     * own /sellclaimblocks and /adjustbonusclaimblocks can create.
     *
     * <p>The availability figure is GriefPrevention's own
     * getRemainingClaimBlocks(), not the bonus pool alone. It includes
     * permission-group bonus blocks. Its overflow handling errs toward
     * refusal both ways (javap, 16.18.7): an overflowing sum is clamped to
     * Integer.MAX_VALUE before claim areas are subtracted, which can only
     * understate what is free, and an overflowing subtraction returns 0,
     * which reads here as blocks-in-use.
     *
     * <p>A deduction that cannot be undone is deliberately not made durable.
     * Writing it would be the plugin choosing the player's loss; the operator
     * record names the restore command instead.
     */
    Result sell(Player player, int blocks)
    {
        if (!settings.sellingEnabled())
        {
            return Result.fail("selling-disabled");
        }
        if (blocks <= 0)
        {
            return Result.fail("invalid-amount");
        }

        DataStore store = dataStore("a sale", player);
        if (store == null)
        {
            return Result.fail("data-unavailable");
        }

        UUID playerId = player.getUniqueId();
        // Fetched once and never re-fetched, for the same reason as buy().
        PlayerData data = store.getPlayerData(playerId);
        PlayerInventory inventory = player.getInventory();

        // After the warm, claims is non-null, so getRemainingClaimBlocks()
        // below runs no fix-negative pass and reads the same pool this does;
        // the two numbers this method uses then describe one state.
        int purchased = warmClaimData(data, player, "a sale");
        if (purchased < blocks)
        {
            return Result.fail("not-enough-blocks", "have", purchased);
        }

        int available = data.getRemainingClaimBlocks();
        if (available < blocks)
        {
            return Result.fail("blocks-in-use", "available", available);
        }

        // Decimal, not double: the ratio is written in config as a decimal
        // like 0.29, which no double holds exactly, and 10000 / 100 * 0.29
        // evaluates to 28.999999999999996 - one item short once floored.
        // BigDecimal.valueOf reads the ratio back as the shortest decimal that
        // round-trips, which is the value the operator typed. Rounding down
        // is the "the argument is what the player hands over" rule, not a loss.
        int items = BigDecimal.valueOf(blocks)
                .multiply(BigDecimal.valueOf(settings.refundRatio()))
                .divide(BigDecimal.valueOf(settings.blocksPerItem()), 0, RoundingMode.FLOOR)
                .intValueExact();
        if (items <= 0)
        {
            return Result.fail("amount-too-small", "item", settings.currencyName());
        }

        // As in buy(): everything the unwind can need, allocated while a
        // failure still costs nothing. purchased >= blocks >= 1, so the
        // subtraction cannot overflow.
        Integer deductBox;
        Integer restoreBox;
        Handover payout;
        String name;
        try
        {
            deductBox = Integer.valueOf(purchased - blocks);
            restoreBox = Integer.valueOf(purchased);
            payout = new Handover();
            name = player.getName();
            if (data.getBonusClaimBlocks() != purchased)
            {
                logger.warning(name + "'s bonus pool changed from " + purchased
                        + " while preparing a sale; the sale was refused and nothing was charged");
                return Result.fail("transaction-failed");
            }
        }
        catch (Throwable prepFailure)
        {
            return refuseUnprepared("a sale", playerId, prepFailure);
        }

        boolean blocksMutated = false;
        boolean poolRestored = false;
        Throwable failure = null;
        Throwable undoFailure = null;
        Throwable persistFailure = null;

        // Nothing has been handed over at this point, by construction.
        try
        {
            data.setBonusClaimBlocks(deductBox);
            blocksMutated = true;
            store.savePlayerData(playerId, data);
        }
        catch (Throwable thrown)
        {
            failure = thrown;
        }

        if (failure != null)
        {
            try
            {
                poolRestored = !blocksMutated || undo(data, purchased - blocks, restoreBox, blocks);
            }
            catch (Throwable thrown)
            {
                undoFailure = thrown;
            }

            // Skipped when the deduction stands: making an unpaid deduction
            // durable is the plugin choosing the player's loss.
            if (blocksMutated && poolRestored)
            {
                persistFailure = persistRollback(store, playerId, data);
            }

            int liveAfter = readPool(data);

            suppress(failure, undoFailure);
            suppress(failure, persistFailure);

            // One record, composed last and from measured values only.
            try
            {
                String writeReport = !blocksMutated
                        ? "not attempted; nothing of this trade was ever written"
                        : !poolRestored
                                ? "skipped deliberately, so this plugin did not make the unpaid deduction durable"
                                : persistFailure != null
                                        ? "the synchronous write threw"
                                        : "the synchronous write returned; GriefPrevention swallows write"
                                                + " failures, so this does not confirm what the file holds";
                StringBuilder fix = new StringBuilder();
                if (blocksMutated && !poolRestored)
                {
                    clause(fix, "Give the blocks back: run /adjustbonusclaimblocks " + name + " " + blocks
                            + ", or set the pool to " + purchased + " by hand. GriefPrevention may persist"
                            + " the deduction on this player's next save before you act.");
                }
                // As in buy(): a save already in flight can have serialized
                // the deduction, so the disk may still be wrong.
                if (blocksMutated && poolRestored)
                {
                    clause(fix, "Verify the player's file: if it shows bonus=" + (purchased - blocks)
                            + ", a save that was already in flight serialized the deducted total;"
                            + " run /adjustbonusclaimblocks " + name + " " + blocks + ".");
                }
                if (fix.length() == 0)
                {
                    fix.append("nothing");
                }
                logger.log(Level.SEVERE, "Sale failed for " + name + " (" + playerId + "): "
                        + (blocksMutated
                                ? "deducted " + blocks + " claim blocks in memory, "
                                        + (poolRestored ? "undone" : "NOT undone")
                                : "the deduction was never applied")
                        + ", pool now " + liveAfter + " (was " + purchased + "); no items were paid out"
                        + "; durable write: " + writeReport
                        + ". Fix by hand: " + fix, failure);
            }
            catch (Throwable ignored)
            {
                // As in buy(): every state step has already run.
            }

            // As in buy(): the measured outcome, Errors included.
            if (!poolRestored)
            {
                throw new IllegalStateException(
                        "the sale is in a state no configured message describes truthfully", failure);
            }
            // Truthful: nothing was handed over and the pool reads its
            // pre-sale value.
            return Result.fail("transaction-failed");
        }

        // The deduction is applied and its save thread started, so from here
        // the server can no longer lose; only the payout can fall short.
        warnIfForeignWrite(data, name, "sale", purchased - blocks);
        deliver(player, items, "payout for a sale", payout);
        int paid = payout.delivered;

        // A shortfall is already logged by deliver with its throwable. A
        // payout that arrived in full despite a failure is not, and an Error
        // that is no longer rethrown must not vanish.
        if (payout.failure != null && paid >= items)
        {
            try
            {
                logger.log(Level.SEVERE, name + "'s sale paid out in full, but the payout raised this;"
                        + " nothing needs fixing by hand", payout.failure);
            }
            catch (Throwable ignored)
            {
                // The sale stands either way.
            }
        }
        // Saying "sold" while the items never arrived is the item-loss case
        // SECURITY.md treats as a vulnerability rather than a bug.
        if (paid < items)
        {
            return Result.fail("items-lost",
                    "lost", items - paid, "item", settings.currencyName(items - paid));
        }
        // As in buy(): the reconciliation handle for a swallowed write.
        try
        {
            logger.info(name + " sold " + blocks + " claim blocks for " + items + " "
                    + settings.currencyName(items) + "; bonus pool " + purchased + " -> " + (purchased - blocks));
        }
        catch (Throwable ignored)
        {
            // Diagnostics only; the sale stands either way.
        }
        return Result.ok("sold",
                "blocks", blocks, "items", items, "item", settings.currencyName(items));
    }

    /**
     * Reports the exchange rate and the player's own pool.
     *
     * <p>Not side-effect-free, and it cannot be made so from here: the warm
     * lets GriefPrevention flush accrued blocks and run its fix-negative pass
     * in memory, as any claims query does - a shovel click included. ClaimBarter
     * does not persist it, and the warm's WARNING, the same one buy() and
     * sell() emit, is the record. Because the warm runs first, the two numbers
     * reported describe one state.
     *
     * <p>Deliberately without a try/catch. Nothing ClaimBarter owns is live,
     * so a throw belongs to the dispatcher, and a lookup must never be able to
     * answer with a trade-failure message.
     */
    Result info(Player player)
    {
        DataStore store = dataStore("a rate lookup", player);
        if (store == null)
        {
            return Result.fail("data-unavailable");
        }

        PlayerData data = store.getPlayerData(player.getUniqueId());
        int purchased = warmClaimData(data, player, "a rate lookup");
        int available = data.getRemainingClaimBlocks();
        return Result.ok("info",
                "item", settings.currencyName(),
                "blocks", settings.blocksPerItem(),
                "purchased", purchased,
                "available", available);
    }

    /**
     * Forces GriefPrevention's lazy claim bookkeeping to run now, on this
     * thread, and returns the bonus pool as it stands afterwards.
     *
     * <p>getClaims() is called for its side effect. Its first call per
     * PlayerData runs the fix-negative pass, which credits bonusClaimBlocks,
     * and leaves claims non-null so no save thread this trade starts can ever
     * take that branch and write the pool behind the trade's back. In 16.18.7
     * claims is assigned only by the constructor and at getClaims offset 15,
     * and the credit at offset 428 sits inside the claims == null branch. The value
     * returned is the post-fix one, so the trade is computed from, and undone
     * to, a pool that already includes GriefPrevention's credit rather than
     * one that would clobber it.
     *
     * <p>No try/catch: a throw here means nothing ClaimBarter owns has moved.
     */
    private int warmClaimData(PlayerData data, Player player, String operation)
    {
        int before = data.getBonusClaimBlocks();
        data.getClaims();
        int after = data.getBonusClaimBlocks();
        if (after != before)
        {
            logger.warning("GriefPrevention corrected " + player.getName() + "'s bonus pool from " + before
                    + " to " + after + " while preparing " + operation + "; ClaimBarter did not write this");
        }
        return after;
    }

    /**
     * GriefPrevention's data store, or null when it cannot be reached.
     *
     * <p>Defence in depth, not an unload-race guard. GriefPrevention 16.18.7
     * never stores null into {@code instance} or {@code dataStore}; what this
     * does catch is a GriefPrevention whose onEnable threw before it assigned
     * its data store, or a different build that behaves differently. Resolving
     * both before anything is taken from the player turns that into a refusal
     * rather than a trade that has to be unwound.
     *
     * <p>It is a narrowing, not a guarantee against a NullPointerException.
     * PlayerData loads lazily: getBonusClaimBlocks() calls
     * loadDataFromSecondaryStorage(), which reads
     * GriefPrevention.instance.dataStore again on its own, after this check has
     * passed. What the check does buy is that any such failure happens before
     * any item or block has moved.
     */
    private DataStore dataStore(String operation, Player player)
    {
        GriefPrevention plugin = GriefPrevention.instance;
        DataStore store = plugin == null ? null : plugin.dataStore;
        if (store == null)
        {
            // WARNING, not SEVERE. Nothing moved, and /claimbarter info needs
            // no permission and has no cooldown, so a player can repeat this
            // at will for as long as the data store is unreachable. At SEVERE
            // it would bury the lines that report real item loss.
            logger.warning("Refused " + operation + " for " + player.getName()
                    + ": GriefPrevention's data store is unavailable");
        }
        return store;
    }

    /**
     * Writes a rollback on the calling thread and hands back whatever that
     * threw, or null.
     *
     * <p>On a purchase whose grant could not be undone, what it writes is the
     * grant that stands rather than a correction, because the items were kept
     * as its payment. The ordering argument below is the same either way.
     *
     * <p>In the usual case there is nothing on disk to correct:
     * savePlayerData's whole body is {@code new SavePlayerDataThread(...).start()},
     * so if it threw, no thread was started and nothing of this trade reached
     * the file. What this covers is the other case. GriefPrevention saves the
     * same PlayerData from several places, and overrideSavePlayerData, which
     * is not synchronized, reads getBonusClaimBlocks() at serialization time, so an unrelated save that
     * happened to serialize between the mutation and the failure will have
     * written the abandoned total. Then the file is wrong and only a write
     * fixes it.
     *
     * <p>Synchronous, unlike the trade itself, so the write is ordered against
     * every writer that starts after it returns. It is not ordered against one
     * already in flight, which can still truncate the file afterwards; that is
     * why the caller's record always names the value to look for.
     *
     * <p>Throwable, not RuntimeException: the write itself is wrapped by
     * GriefPrevention's own catch, so what realistically escapes is an Error.
     * It is returned rather than thrown so the steps still owed after it run,
     * and rather than logged so the caller's single record can report it with
     * everything else. A null return means only that the call returned.
     * GriefPrevention swallows write failures, so it does not mean the file
     * was written.
     */
    private Throwable persistRollback(DataStore store, UUID playerId, PlayerData data)
    {
        try
        {
            store.savePlayerDataSync(playerId, data);
            return null;
        }
        catch (Throwable thrown)
        {
            return thrown;
        }
    }

    /**
     * Takes back one trade's own change to the pool and reports whether it
     * could. {@code written} is what the trade set, {@code restoreBox} the
     * pre-trade value boxed before anything moved, and {@code delta} the
     * correction that reverses the trade (negative for a grant).
     *
     * <p>A pool still reading {@code written} was touched by nothing else, so
     * the relative and absolute undo coincide and the pre-boxed value is
     * written without allocating. Otherwise someone credited or debited the
     * pool meanwhile, and only this trade's own change is reversed so theirs
     * survives. A correction outside int range is not written, and the caller
     * reports the pool as not restored. The caller catches what this throws.
     */
    private static boolean undo(PlayerData data, int written, Integer restoreBox, long delta)
    {
        int live = data.getBonusClaimBlocks();
        if (live == written)
        {
            data.setBonusClaimBlocks(restoreBox);
            return true;
        }
        long relative = (long) live + delta;
        if (relative < Integer.MIN_VALUE || relative > Integer.MAX_VALUE)
        {
            return false;
        }
        data.setBonusClaimBlocks(Integer.valueOf((int) relative));
        return true;
    }

    /**
     * Logs that a trade could not be prepared and refuses it.
     *
     * <p>Named by UUID: getName() may be the call that just threw. Nothing has
     * moved, so a record lost to the logger strands nothing.
     */
    private Result refuseUnprepared(String operation, UUID playerId, Throwable failure)
    {
        try
        {
            logger.log(Level.SEVERE, "Refused " + operation + " for " + playerId
                    + ": the transaction could not be prepared; nothing moved", failure);
        }
        catch (Throwable ignored)
        {
            // See above.
        }
        return Result.fail("transaction-failed");
    }

    /**
     * After a completed trade, warns if the pool no longer reads what the
     * trade wrote. Diagnostics only: the trade has completed, so a failure
     * here must not turn it into an error.
     */
    private void warnIfForeignWrite(PlayerData data, String name, String trade, int written)
    {
        try
        {
            int live = data.getBonusClaimBlocks();
            if (live != written)
            {
                logger.warning("After " + name + "'s " + trade + " the bonus pool reads " + live
                        + " in memory but " + written
                        + " was written; something outside ClaimBarter wrote it during the trade");
            }
        }
        catch (Throwable ignored)
        {
            // See above.
        }
    }

    /**
     * Hands items to a player, dropping at their feet whatever will not fit,
     * and records in {@code out} how many actually arrived. Never throws.
     *
     * <p>Never throwing matters because both callers are past the point of no
     * return: one is unwinding a failed purchase, the other has already
     * deducted and saved the blocks. An escaping exception would replace the
     * configured message with Bukkit's generic error and lose the record of
     * what was owed.
     *
     * <p>The count is what the caller reports, so it is built from what was
     * observed to land, never from what was requested. Reporting the amount
     * requested would send an operator to restore items the player already
     * had, turning an item loss into item duplication. The stack whose drop
     * threw is counted as not received and called out separately, so the
     * operator looks at the ground before restoring it.
     *
     * <p>What it closes. What entered the inventory is measured by recounting
     * it rather than trusted from addItem's return map, so an addItem that
     * throws after placing part of a stack is credited for exactly what it
     * placed. A dropped stack counts only once the entity exists: World
     * .dropItemNaturally returns the Item whether or not ItemSpawnEvent was
     * cancelled, so isValid() is what tells a cancelled spawn from a real one.
     * A dropped stack is credited the smaller of what was handed over and what
     * the valid entity holds afterwards, so a listener that shrinks it is
     * caught and one that merges into it cannot inflate the count past what
     * this trade handed over. World and location are re-read for every drop,
     * because a listener is free to teleport the player. A player who
     * disconnects stops the loop rather than having items pushed into an
     * inventory they may never see.
     *
     * <p>What it does not capture: (a) whether addItem ever places outside the
     * 36 storage slots - the API is silent and every relevant method is
     * abstract, so this must be confirmed on a live server, and a miss would
     * over-report loss; (b) whether isValid() is false for a never-added
     * entity, which is paper-server behaviour absent from the API jar; (c) the
     * true fate of the one unconfirmed stack; (d) a valid drop later
     * despawning, burning, falling into the void, being cleared by an
     * item-clear plugin or picked up by someone else - "delivered" means
     * "entered the inventory or existed as a valid entity when read", not "is
     * in the player's possession"; (e) a listener that adds or removes plain
     * currency from the main inventory during the event, which skews the
     * recount in whichever direction it moved, though the clamp keeps the
     * reported loss non-negative and never above the amount; (f) under heap
     * exhaustion the counts and the log line themselves allocate and may be
     * lost.
     */
    private void deliver(Player player, int amount, String context, Handover out)
    {
        PlayerInventory inventory = null;
        int before = UNKNOWN;
        int placedTally = 0;
        int dropped = 0;
        int inFlight = 0;

        // Counted first so a recount has something to diff against. A failure
        // here only degrades the accounting, never the payout.
        try
        {
            inventory = player.getInventory();
            before = countCurrency(inventory);
        }
        catch (Throwable thrown)
        {
            out.failure = thrown;
        }

        try
        {
            int remaining = amount;
            while (remaining > 0)
            {
                if (!player.isConnected())
                {
                    break;
                }
                int size = Math.min(remaining, settings.currency().getMaxStackSize());
                // Cannot fail for validated settings: BarterSettings already
                // rejects air and non-items, and size is at least 1.
                ItemStack stack = new ItemStack(settings.currency(), size);
                Map<Integer, ItemStack> leftover = inventory.addItem(stack);
                remaining -= size;
                // The leftover map's values are the argument stacks mutated in
                // place, which is why size was read before the call.
                int notStored = 0;
                for (ItemStack rest : leftover.values())
                {
                    notStored += rest.getAmount();
                }
                placedTally += size - notStored;
                for (ItemStack drop : leftover.values())
                {
                    int handedOver = drop.getAmount();
                    if (handedOver <= 0)
                    {
                        continue;
                    }
                    // Held until the call returns, so a drop that throws is
                    // reported as unconfirmed rather than as delivered or lost.
                    inFlight = handedOver;
                    Item entity = player.getWorld().dropItemNaturally(player.getLocation(), drop);
                    inFlight = 0;
                    if (entity != null && entity.isValid())
                    {
                        ItemStack onGround = entity.getItemStack();
                        dropped += Math.max(0,
                                Math.min(handedOver, onGround == null ? 0 : onGround.getAmount()));
                    }
                }
            }
        }
        catch (Throwable thrown)
        {
            out.failure = combine(out.failure, thrown);
        }

        int placed = placedTally;
        boolean exact = false;
        if (before != UNKNOWN)
        {
            try
            {
                placed = Math.max(0, Math.min(amount, countCurrency(inventory) - before));
                exact = true;
            }
            catch (Throwable ignored)
            {
                // The tally from addItem's own return stands. It never exceeds
                // the truth, so the reported loss is never under-stated.
            }
        }

        out.unconfirmed = inFlight;
        out.delivered = (int) Math.min(amount, (long) placed + dropped);
        int lost = (int) Math.max(0, (long) amount - out.delivered - out.unconfirmed);

        if (out.delivered < amount)
        {
            try
            {
                logger.log(Level.SEVERE, "Handing " + amount + " " + settings.currencyName(amount)
                        + " to " + player.getName() + " as a " + context + " fell short: "
                        + out.delivered + " confirmed (" + placed + " into the inventory, "
                        + dropped + " dropped at their feet), "
                        + (out.unconfirmed > 0
                                ? out.unconfirmed + " unconfirmed in a stack whose drop threw and may or may"
                                        + " not be on the ground"
                                : "0 unconfirmed")
                        + ", " + lost + " lost and must be restored by hand. The inventory figure is "
                        + (exact
                                ? "exact, measured by recounting the inventory"
                                : "counted from addItem's own return because the inventory could not be"
                                        + " recounted, so this is a lower bound")
                        + (out.failure == null
                                ? "; nothing threw, so a drop was most likely destroyed by another plugin"
                                        + " cancelling ItemSpawnEvent"
                                : "")
                        + (out.unconfirmed > 0
                                ? "; check the ground at the player's position before restoring"
                                : ""),
                        out.failure);
            }
            catch (Throwable ignored)
            {
                // java.util.logging allocates; the counts in out still reach
                // the caller.
            }
        }
    }

    /**
     * Attaches a secondary failure to the one being reported.
     *
     * <p>Guarded because HotSpot can hand out the same preallocated
     * OutOfMemoryError twice, and Throwable.addSuppressed throws on
     * self-suppression; a failure here must not skip the steps after it.
     */
    private static void suppress(Throwable primary, Throwable secondary)
    {
        if (primary == null || secondary == null || secondary == primary)
        {
            return;
        }
        try
        {
            primary.addSuppressed(secondary);
        }
        catch (Throwable ignored)
        {
            // The secondary is lost from the record; nothing else depends on it.
        }
    }

    /** The pool as it reads now, for a log line only; UNKNOWN if it cannot be read. */
    private static int readPool(PlayerData data)
    {
        try
        {
            return data.getBonusClaimBlocks();
        }
        catch (Throwable ignored)
        {
            return UNKNOWN;
        }
    }

    /** The first failure recorded wins; any later one is attached to it. */
    private static Throwable combine(Throwable primary, Throwable next)
    {
        if (primary == null)
        {
            return next;
        }
        suppress(primary, next);
        return primary;
    }

    /** Appends one fix-by-hand clause, space-joined to any before it. */
    private static void clause(StringBuilder fix, String text)
    {
        if (fix.length() > 0)
        {
            fix.append(' ');
        }
        fix.append(text);
    }

    /**
     * Counts currency in the main inventory only, and only plain stacks.
     *
     * <p>Anything carrying item metadata is skipped: a renamed or enchanted
     * ingot may be a keepsake or a quest item, and spending it because it
     * shares a material would be a bug the player pays for.
     */
    private int countCurrency(PlayerInventory inventory)
    {
        int total = 0;
        for (ItemStack stack : inventory.getStorageContents())
        {
            if (isPlainCurrency(stack))
            {
                total += stack.getAmount();
                if (total < 0)
                {
                    return Integer.MAX_VALUE;
                }
            }
        }
        return total;
    }

    private boolean isPlainCurrency(ItemStack stack)
    {
        return stack != null
                && stack.getType() == settings.currency()
                && !stack.hasItemMeta();
    }

    /**
     * Returns how many items the array it wrote back holds fewer than the one
     * it read. That is exact once setStorageContents returns, and it is the
     * fallback buy() uses when the recount after it cannot run. It can be less
     * than amount if the inventory shrank since it was last counted.
     */
    private int removeCurrency(PlayerInventory inventory, int amount)
    {
        int remaining = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++)
        {
            ItemStack stack = contents[slot];
            if (!isPlainCurrency(stack))
            {
                continue;
            }
            int taken = Math.min(stack.getAmount(), remaining);
            remaining -= taken;
            if (taken == stack.getAmount())
            {
                contents[slot] = null;
            }
            else
            {
                stack.setAmount(stack.getAmount() - taken);
            }
        }
        inventory.setStorageContents(contents);
        return amount - remaining;
    }
}
