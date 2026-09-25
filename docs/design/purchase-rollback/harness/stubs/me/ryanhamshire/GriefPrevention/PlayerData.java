package me.ryanhamshire.GriefPrevention;

import harness.support.CallLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.function.IntConsumer;

/**
 * Harness stand-in for me.ryanhamshire.GriefPrevention.PlayerData, modelling
 * the verified 16.18.7 bytecode rather than guessing at it:
 *
 * <ul>
 * <li>getBonusClaimBlocks(): {@code if (bonusClaimBlocks == null)
 * loadDataFromSecondaryStorage();} then unbox and return. A null field models
 * "never loaded"; lazyLoadReturnsZero models the five-failed-retries default
 * that silently yields zero with no way for a caller to tell that apart from a
 * genuinely empty pool.</li>
 * <li>setBonusClaimBlocks(Integer): javap shows a bare
 * aload_0/aload_1/putfield/return - it cannot throw once the argument exists,
 * so this stub's own body cannot either; setterThrows exists only as a canary
 * against a call built from an invalid argument, never as a way to make an
 * ordinary call fail.</li>
 * <li>getClaims(): javap shows {@code claims} assigned a fresh, empty Vector
 * FIRST (offset 15), strictly before anything else in the method runs, and
 * GriefPrevention's own fix-negative credit to bonusClaimBlocks (offset 428)
 * lives entirely inside the {@code claims == null} branch, so it can only ever
 * fire once per lifetime. Both are reproduced here in that order.</li>
 * </ul>
 */
public class PlayerData
{
    private Integer bonusClaimBlocks;
    private Vector<Object> claims;

    public int getClaimsCallCount = 0;
    public int getBonusCallCount = 0;
    public final List<String> log = new ArrayList<>();

    /** Every argument ever passed to setBonusClaimBlocks, kept by reference, in call order. */
    public final List<Integer> setterArguments = new ArrayList<>();

    /**
     * Parallel to setterArguments: for each setter call, the bytes this thread
     * allocated between the last getBonusClaimBlocks()/getStorageContents()
     * return and the setter's entry. 0 proves the argument was boxed earlier.
     */
    public final List<Long> setterAllocBytes = new ArrayList<>();

    // --- knobs -----------------------------------------------------------

    public RuntimeException lazyLoadThrowsRuntime;
    public Error lazyLoadThrowsError;
    /** True only documents intent; a null constructor argument already selects lazy-load mode. */
    public boolean lazyLoadReturnsZero = false;

    /** > 0 applies exactly once, inside getClaims(), the first time claims is populated. */
    public int fixNegativeDeficit = 0;

    /** Non-null forces getRemainingClaimBlocks() to that exact figure (including 0 and MAX_VALUE). */
    public Integer remainingOverride;

    public RuntimeException throwOnGetClaimsRuntime;
    public Error throwOnGetClaimsError;
    public RuntimeException throwOnGetRemainingRuntime;
    public Error throwOnGetRemainingError;

    /** Armed in every run; must never fire. See Harness's invariant #5 check for what this guards. */
    public boolean setterThrows = false;
    public boolean setterThrowsFired = false;

    /** Installed by DataStore.armPendingStaleWriter; fired on every setter call, not just the first. */
    public IntConsumer onSetterHook;

    // One-shot foreign-write triggers, anchored to semantic events rather than
    // raw call counts so the harness does not have to guess the exact number
    // of getBonusClaimBlocks() calls a not-yet-written implementation makes.
    private int foreignWriteAfterGetClaimsSkipReads = -1;
    private int foreignWriteAfterSetterCalls = -1;
    private int foreignWriteDelta;
    private boolean foreignWriteViaGetClaimsPending = false;
    private boolean foreignWriteViaSetterPending = false;

    public PlayerData(Integer initialBonus)
    {
        this.bonusClaimBlocks = initialBonus;
    }

    /** Models a foreign write landing between the warm's own reads and the caller's next read of the pool. */
    public void armForeignWriteAfterGetClaims(int skipReads, int delta)
    {
        this.foreignWriteAfterGetClaimsSkipReads = skipReads;
        this.foreignWriteDelta = delta;
    }

    /** Models a foreign write landing between the grant/deduction and the next read of the pool. */
    public void armForeignWriteAfterSetterCalls(int setterCallsSoFar, int delta)
    {
        this.foreignWriteAfterSetterCalls = setterCallsSoFar;
        this.foreignWriteDelta = delta;
    }

    public int getBonusClaimBlocks()
    {
        if (bonusClaimBlocks == null)
        {
            if (lazyLoadThrowsError != null)
            {
                throw lazyLoadThrowsError;
            }
            if (lazyLoadThrowsRuntime != null)
            {
                throw lazyLoadThrowsRuntime;
            }
            // loadDataFromSecondaryStorage(), the silent-zero path.
            bonusClaimBlocks = Integer.valueOf(0);
        }
        applyPendingForeignWriteIfDue();
        getBonusCallCount++;
        CallLog.record("PlayerData.getBonusClaimBlocks=" + bonusClaimBlocks);
        log.add("getBonusClaimBlocks=" + bonusClaimBlocks);
        int v = bonusClaimBlocks.intValue();
        // After the stub's own logging, so only the plugin's allocations
        // between this read and the next setter call are measured.
        CallLog.markAlloc();
        return v;
    }

    private void applyPendingForeignWriteIfDue()
    {
        if (foreignWriteViaGetClaimsPending)
        {
            if (foreignWriteAfterGetClaimsSkipReads > 0)
            {
                foreignWriteAfterGetClaimsSkipReads--;
                return;
            }
            foreignWriteViaGetClaimsPending = false;
            int base = bonusClaimBlocks == null ? 0 : bonusClaimBlocks.intValue();
            bonusClaimBlocks = Integer.valueOf(base + foreignWriteDelta);
            CallLog.record("PlayerData.foreignWrite(now=" + bonusClaimBlocks + ")");
            log.add("foreignWrite(now=" + bonusClaimBlocks + ")");
        }
        else if (foreignWriteViaSetterPending)
        {
            foreignWriteViaSetterPending = false;
            int base = bonusClaimBlocks == null ? 0 : bonusClaimBlocks.intValue();
            bonusClaimBlocks = Integer.valueOf(base + foreignWriteDelta);
            CallLog.record("PlayerData.foreignWrite(now=" + bonusClaimBlocks + ")");
            log.add("foreignWrite(now=" + bonusClaimBlocks + ")");
        }
    }

    public void setBonusClaimBlocks(Integer value)
    {
        // FIRST statement, before any stub work allocates: bytes the plugin
        // allocated since the last pool/inventory read (invariant #5).
        long allocated = CallLog.allocatedSinceMark();
        setterAllocBytes.add(allocated);
        setterArguments.add(value);
        CallLog.record("PlayerData.setBonusClaimBlocks(" + value + ")");
        log.add("setBonusClaimBlocks(" + value + ")");
        if (setterThrows && value == null)
        {
            // Verified bytecode has no null-check and no throw of its own; a
            // null argument here is exclusively a defect in the caller, which
            // is exactly what this canary is armed to catch.
            setterThrowsFired = true;
            throw new AssertionError("harness: setBonusClaimBlocks called with a null Integer");
        }
        this.bonusClaimBlocks = value;
        if (setterArguments.size() == foreignWriteAfterSetterCalls)
        {
            foreignWriteViaSetterPending = true;
        }
        if (onSetterHook != null)
        {
            onSetterHook.accept(value);
        }
    }

    public Vector<Object> getClaims()
    {
        getClaimsCallCount++;
        CallLog.record("PlayerData.getClaims#" + getClaimsCallCount);
        log.add("getClaims#" + getClaimsCallCount);
        if (throwOnGetClaimsError != null)
        {
            throw throwOnGetClaimsError;
        }
        if (throwOnGetClaimsRuntime != null)
        {
            throw throwOnGetClaimsRuntime;
        }
        if (claims != null)
        {
            return claims;
        }
        // Verified: the field is assigned a fresh, empty Vector before
        // anything else in the method runs.
        claims = new Vector<>();
        if (getClaimsCallCount == 1 && foreignWriteAfterGetClaimsSkipReads >= 0)
        {
            foreignWriteViaGetClaimsPending = true;
        }
        if (fixNegativeDeficit != 0)
        {
            int deficit = fixNegativeDeficit;
            // Verified precondition: this lives inside claims == null, so it
            // can only be taken once per PlayerData lifetime.
            fixNegativeDeficit = 0;
            int before = bonusClaimBlocks == null ? 0 : bonusClaimBlocks.intValue();
            // Offset 428: GriefPrevention's own bare putfield on this thread,
            // not a call the plugin makes, so it bypasses setterArguments/hook.
            bonusClaimBlocks = Integer.valueOf(before + deficit);
            CallLog.record("PlayerData.internalFixNegative(" + before + "->" + bonusClaimBlocks + ")");
            log.add("internalFixNegative(" + before + "->" + bonusClaimBlocks + ")");
        }
        return claims;
    }

    public int getRemainingClaimBlocks()
    {
        CallLog.record("PlayerData.getRemainingClaimBlocks");
        log.add("getRemainingClaimBlocks");
        if (throwOnGetRemainingError != null)
        {
            throw throwOnGetRemainingError;
        }
        if (throwOnGetRemainingRuntime != null)
        {
            throw throwOnGetRemainingRuntime;
        }
        if (remainingOverride != null)
        {
            return remainingOverride.intValue();
        }
        // Verified: getRemainingClaimBlocks reads getBonusClaimBlocks() itself.
        return getBonusClaimBlocks();
    }
}
