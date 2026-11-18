package se.lublin.humla.util;

import java.util.concurrent.TimeUnit;

/** Counts failures between reports. Each instance belongs to one stream and failure cause. */
public final class FailureLogWindow {
    private static final long INTERVAL_NS = TimeUnit.SECONDS.toNanos(5);
    private boolean reported;
    private long lastReportNs;
    private long count;

    /** Returns the batch size when a report is due, or zero while suppressing repeats. */
    public synchronized long record(long nowNs) {
        ++count;
        if (reported && nowNs - lastReportNs < INTERVAL_NS) return 0;
        reported = true;
        lastReportNs = nowNs;
        long batch = count;
        count = 0;
        return batch;
    }
}
