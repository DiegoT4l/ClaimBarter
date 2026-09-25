package harness.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One ordered call log shared by every stub the harness installs, so an
 * ordering invariant (take-before-grant, grant-before-payout, ...) is asserted
 * from what actually happened across ALL the collaborating objects, never from
 * reading the source of whichever BarterService is under test.
 *
 * <p>Static and global by design: the harness runs one trade per JVM
 * invocation's worth of driver work between resets, and every stub (Bukkit
 * side and GriefPrevention side alike) needs to append to the exact same
 * timeline regardless of which package it lives in.
 */
public final class CallLog
{
    private static final List<String> ENTRIES = new ArrayList<>();

    /**
     * Per-thread allocation counter backing invariant #5 (PRE-BOXED SETTER).
     * A black-box harness cannot compare Integer identity against PREPARE's
     * boxes - getBonusClaimBlocks returns a primitive, so no PREPARE-created
     * Integer ever reaches a stub before the undo - so instead the stubs mark
     * the counter right after the last pool/inventory read and PlayerData's
     * setter measures how many bytes the plugin allocated in between. A
     * pre-boxed value costs 0 bytes; a fresh Integer.valueOf outside the
     * Integer cache costs one box. HotSpot-specific (com.sun.management), and
     * Harness.main refuses to run if the counter is unsupported or disabled.
     */
    private static final com.sun.management.ThreadMXBean MX =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    private static long allocMark;

    static
    {
        // Warm-up: resolve and link every call on the measuring path now, so
        // no one-time linkage allocation ever lands inside a measured window.
        for (int i = 0; i < 1000; i++)
        {
            markAlloc();
            allocatedSinceMark();
        }
    }

    private CallLog()
    {
    }

    /** Deliberately non-synchronized and allocation-free: it sits inside the window it measures. */
    public static void markAlloc()
    {
        allocMark = MX.getCurrentThreadAllocatedBytes();
    }

    /** Bytes this thread allocated since the last markAlloc(); allocation-free for the same reason. */
    public static long allocatedSinceMark()
    {
        return MX.getCurrentThreadAllocatedBytes() - allocMark;
    }

    public static synchronized void record(String entry)
    {
        ENTRIES.add(entry);
    }

    public static synchronized List<String> snapshot()
    {
        return Collections.unmodifiableList(new ArrayList<>(ENTRIES));
    }

    public static synchronized void reset()
    {
        ENTRIES.clear();
    }
}
