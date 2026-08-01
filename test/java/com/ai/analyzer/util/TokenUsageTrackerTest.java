package com.ai.analyzer.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenUsageTrackerTest {

    @Test
    void recordAccumulatesCounters() {
        TokenUsageTracker tracker = new TokenUsageTracker();
        tracker.record(100, 50, 0, 150);
        tracker.record(200, 60, 40, 300);

        TokenUsageTracker.UsageSnapshot s = tracker.snapshot();
        assertEquals(2, s.modelCalls());
        assertEquals(300, s.inputTokens());
        assertEquals(110, s.outputTokens());
        assertEquals(40, s.cachedTokens());
        assertEquals(450, s.totalTokens());
        assertFalse(s.overBudget());
    }

    @Test
    void recordDerivesTotalWhenTotalNonPositive() {
        TokenUsageTracker tracker = new TokenUsageTracker();
        tracker.record(100, 50, 30, 0);

        TokenUsageTracker.UsageSnapshot s = tracker.snapshot();
        assertEquals(180, s.totalTokens());
    }

    @Test
    void recordIgnoresNegativeValues() {
        TokenUsageTracker tracker = new TokenUsageTracker();
        tracker.record(-5, -1, 0, 10);

        TokenUsageTracker.UsageSnapshot s = tracker.snapshot();
        assertEquals(0, s.inputTokens());
        assertEquals(0, s.outputTokens());
        assertEquals(10, s.totalTokens());
    }

    @Test
    void budgetTripOnInputPlusCached() {
        TokenUsageTracker tracker = new TokenUsageTracker();
        tracker.setBudget(1000);
        tracker.record(600, 200, 100, 900);
        assertFalse(tracker.isOverBudget());
        assertEquals(300, tracker.remainingTokens());

        tracker.record(300, 10, 0, 310);
        assertTrue(tracker.isOverBudget());
        assertEquals(0, tracker.remainingTokens());
    }

    @Test
    void budgetZeroMeansUnlimited() {
        TokenUsageTracker tracker = new TokenUsageTracker();
        tracker.setBudget(0);
        tracker.record(100_000, 10_000, 0, 110_000);

        assertFalse(tracker.isOverBudget());
        assertEquals(Long.MAX_VALUE, tracker.remainingTokens());
    }

    @Test
    void resetClearsCountersAndBudgetFlag() {
        TokenUsageTracker tracker = new TokenUsageTracker();
        tracker.setBudget(100);
        tracker.record(200, 50, 0, 250);
        assertTrue(tracker.isOverBudget());

        tracker.reset();

        TokenUsageTracker.UsageSnapshot s = tracker.snapshot();
        assertEquals(0, s.modelCalls());
        assertEquals(0, s.totalTokens());
        assertFalse(s.overBudget());
        assertEquals(100, s.budgetTokens());
    }

    @Test
    void setBudgetClearsOverBudgetFlag() {
        TokenUsageTracker tracker = new TokenUsageTracker();
        tracker.setBudget(100);
        tracker.record(300, 0, 0, 300);
        assertTrue(tracker.isOverBudget());

        tracker.setBudget(1000);
        assertFalse(tracker.isOverBudget());
    }

    @Test
    void listenerNotifiedOnRecordResetAndBudgetChange() {
        TokenUsageTracker tracker = new TokenUsageTracker();
        AtomicInteger notifications = new AtomicInteger();
        tracker.setListener(s -> notifications.incrementAndGet());

        tracker.record(10, 5, 0, 15);
        tracker.reset();
        tracker.setBudget(500);

        assertEquals(3, notifications.get());
    }

    @Test
    void summaryFormatsHumanReadable() {
        TokenUsageTracker tracker = new TokenUsageTracker();
        tracker.record(45_000, 12_800, 0, 57_800);

        String summary = tracker.snapshot().summary();
        assertTrue(summary.contains("1 次调用"));
        assertTrue(summary.contains("45.0K 输入"));
        assertTrue(summary.contains("12.8K 输出"));
    }
}
