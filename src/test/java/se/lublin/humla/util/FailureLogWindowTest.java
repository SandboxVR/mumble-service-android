package se.lublin.humla.util;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class FailureLogWindowTest {
    @Test public void reportsFirstAndCountsBurstsAcrossWindowBoundaries() {
        FailureLogWindow failures = new FailureLogWindow();
        assertEquals(1, failures.record(0));
        for (int i = 0; i < 750; ++i) assertEquals(0, failures.record(i));
        assertEquals(751, failures.record(5_000_000_000L));
        assertEquals(0, failures.record(5_000_000_001L));
        assertEquals(2, failures.record(10_000_000_000L));
        assertEquals(1, new FailureLogWindow().record(0));
    }
}
