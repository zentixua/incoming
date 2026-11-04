package ua.zentix.stand;
/** Existing ImpactTiming fields; no hasTime supplier is invoked by this view. */
public interface WindowTimingView {
    long stand$earlyUsed(); long stand$earlyBudget(); boolean stand$active();
}
