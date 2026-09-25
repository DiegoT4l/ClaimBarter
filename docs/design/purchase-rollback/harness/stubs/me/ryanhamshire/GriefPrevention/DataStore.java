package me.ryanhamshire.GriefPrevention;

import harness.support.CallLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Harness stand-in for me.ryanhamshire.GriefPrevention.DataStore, narrowed to
 * getPlayerData/savePlayerData/savePlayerDataSync.
 *
 * <p>savePlayerData, verified with javap against 16.18.7, is exactly
 * {@code new SavePlayerDataThread(this, id, data); start(); return} - it only
 * constructs and starts a thread. SavePlayerDataThread.run() then executes
 * getAccruedClaimBlocks(), getClaims() and asyncSavePlayerData on that other
 * thread, whose exceptions never reach the caller. This stub runs that body
 * synchronously on the success path, as a deterministic model of run() -
 * which is what lets WARM-DISARMS-SAVE-THREAD prove the warm's fix-negative
 * credit cannot be re-applied by a later save. A configured
 * throwOnSavePlayerData models the only failure the caller can actually see:
 * new/start failing (e.g. OutOfMemoryError: unable to create native thread)
 * BEFORE run() begins, so no PlayerData method is called on that path.
 *
 * <p>savePlayerDataSync runs getAccruedClaimBlocks(), getClaims() and
 * asyncSavePlayerData all on the calling thread, so reading the PlayerData
 * before an in-write failure is faithful there, and its stub keeps that order.
 */
public class DataStore
{
    private PlayerData cached;

    public int getPlayerDataCallCount = 0;

    /** Thrown from savePlayerData; set to an Error or a RuntimeException instance, or leave null. */
    public Throwable throwOnSavePlayerData;
    /** Thrown from savePlayerDataSync; set to an Error or a RuntimeException instance, or leave null. */
    public Throwable throwOnSavePlayerDataSync;

    /** The write "succeeds" (no throw) but simulatedDisk is left untouched - GriefPrevention's own catch(Exception). */
    public boolean swallowWriteSilently = false;

    public final Map<UUID, Integer> simulatedDisk = new HashMap<>();

    public final List<String> saveCallLog = new ArrayList<>();

    /** Set true immediately after arming, then flips false the instant flushStaleWriter() lands the write. */
    private UUID staleWriterPlayerId;
    private Integer staleWriterCapturedValue;

    public void setCachedPlayerData(PlayerData data)
    {
        this.cached = data;
    }

    public synchronized PlayerData getPlayerData(UUID id)
    {
        getPlayerDataCallCount++;
        CallLog.record("DataStore.getPlayerData#" + getPlayerDataCallCount);
        return cached;
    }

    /**
     * Models an in-flight SavePlayerDataThread from an earlier tick: it
     * already holds a reference to this PlayerData and will read
     * bonusClaimBlocks (via the un-synchronized getter, exactly as
     * overrideSavePlayerData does at its own offset) the instant the FIRST
     * setBonusClaimBlocks call of this trade lands - typically the grant or
     * deduction the plugin is about to try to undo. flushStaleWriter() then
     * performs that writer's own truncating write, whenever the harness
     * decides "now" is when that already-running thread finally serializes.
     */
    public void armPendingStaleWriter(UUID playerId, PlayerData data)
    {
        staleWriterPlayerId = playerId;
        boolean[] captured = {false};
        data.onSetterHook = value ->
        {
            if (!captured[0])
            {
                captured[0] = true;
                staleWriterCapturedValue = value;
            }
        };
    }

    public boolean hasCapturedStaleWrite()
    {
        return staleWriterCapturedValue != null;
    }

    public void flushStaleWriter()
    {
        if (staleWriterCapturedValue != null)
        {
            CallLog.record("DataStore.staleWriterFlush(" + staleWriterCapturedValue + ")");
            simulatedDisk.put(staleWriterPlayerId, staleWriterCapturedValue);
        }
    }

    public void savePlayerData(UUID playerId, PlayerData data)
    {
        // Models new SavePlayerDataThread(...)/start() failing before run()
        // begins: no PlayerData method is called on this path.
        if (throwOnSavePlayerData != null)
        {
            saveCallLog.add("savePlayerData(threw before run)");
            CallLog.record("DataStore.savePlayerData(threw before run)");
            if (throwOnSavePlayerData instanceof Error error)
            {
                throw error;
            }
            throw (RuntimeException) throwOnSavePlayerData;
        }
        // Success path: SavePlayerDataThread.run(), modelled synchronously.
        data.getClaims();
        int liveValue = data.getBonusClaimBlocks();
        saveCallLog.add("savePlayerData(" + liveValue + ")");
        CallLog.record("DataStore.savePlayerData(" + liveValue + ")");
        if (!swallowWriteSilently)
        {
            simulatedDisk.put(playerId, liveValue);
        }
    }

    public void savePlayerDataSync(UUID playerId, PlayerData data)
    {
        data.getClaims();
        int liveValue = data.getBonusClaimBlocks();
        saveCallLog.add("savePlayerDataSync(" + liveValue + ")");
        CallLog.record("DataStore.savePlayerDataSync(" + liveValue + ")");
        if (throwOnSavePlayerDataSync instanceof Error error)
        {
            throw error;
        }
        if (throwOnSavePlayerDataSync instanceof RuntimeException runtimeException)
        {
            throw runtimeException;
        }
        if (!swallowWriteSilently)
        {
            simulatedDisk.put(playerId, liveValue);
        }
        // Models the stale writer's own truncating write landing right after
        // this synchronous rollback returns - the one repairable hard break
        // the design's javadoc documents.
        if (staleWriterCapturedValue != null)
        {
            flushStaleWriter();
        }
    }
}
