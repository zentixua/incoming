package ua.zentix.stand;
/** Read-only access to Incoming fields and observer-owned cumulative numeric records. */
public interface WindowClockView {
    long stand$used(); long stand$deadline(); long stand$busyDeadline();
    boolean stand$worked(); boolean stand$late(); boolean stand$counting();
    long stand$stepSum(); long stand$stepCount(); long stand$firstCount(); long stand$lateCount();
    long stand$lastStep(); long stand$lastBegan(); boolean stand$lastFirst(); boolean stand$lastLate();
}
