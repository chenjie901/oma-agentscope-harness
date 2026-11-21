package ai.oma.harness.core.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMdcTest {

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @Test
    void putSetsSessionId() {
        SessionMdc.put("sesn_123");
        assertEquals("sesn_123", MDC.get(SessionMdc.SESSION_ID));
    }

    @Test
    void putIgnoresBlankSessionId() {
        SessionMdc.put("  ");
        assertNull(MDC.get(SessionMdc.SESSION_ID));

        MDC.put(SessionMdc.SESSION_ID, "sesn_existing");
        SessionMdc.put(null);
        assertEquals("sesn_existing", MDC.get(SessionMdc.SESSION_ID));
    }

    @Test
    void restoreRestoresPreviousContext() {
        MDC.put(SessionMdc.SESSION_ID, "sesn_before");

        Map<String, String> previous = SessionMdc.captureAndPut("sesn_during");
        assertEquals("sesn_during", MDC.get(SessionMdc.SESSION_ID));

        SessionMdc.restore(previous);
        assertEquals("sesn_before", MDC.get(SessionMdc.SESSION_ID));
    }

    @Test
    void restoreClearsSessionIdWhenNoPreviousContext() {
        Map<String, String> previous = SessionMdc.captureAndPut("sesn_during");
        assertEquals("sesn_during", MDC.get(SessionMdc.SESSION_ID));

        SessionMdc.restore(previous);
        assertNull(MDC.get(SessionMdc.SESSION_ID));
    }

    @Test
    void wrapSetsAndRestoresAroundTask() {
        AtomicReference<String> observed = new AtomicReference<>();
        Runnable wrapped = SessionMdc.wrap("sesn_wrapped", () -> observed.set(MDC.get(SessionMdc.SESSION_ID)));

        wrapped.run();

        assertEquals("sesn_wrapped", observed.get());
        assertNull(MDC.get(SessionMdc.SESSION_ID));
    }

    @Test
    void wrapRestoresContextWhenTaskThrows() {
        MDC.put(SessionMdc.SESSION_ID, "sesn_outer");
        AtomicBoolean ran = new AtomicBoolean(false);
        Runnable wrapped = SessionMdc.wrap("sesn_inner", () -> {
            ran.set(true);
            throw new IllegalStateException("boom");
        });

        assertThrows(IllegalStateException.class, wrapped::run);

        assertTrue(ran.get());
        assertEquals("sesn_outer", MDC.get(SessionMdc.SESSION_ID));
    }
}
