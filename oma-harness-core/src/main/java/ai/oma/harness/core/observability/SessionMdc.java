package ai.oma.harness.core.observability;

import java.util.Map;
import org.slf4j.MDC;

/** 把当前 code session id 注入 SLF4J MDC，使日志 pattern 能在每行输出 session。 */
public final class SessionMdc {
    public static final String SESSION_ID = "session_id";

    private SessionMdc() {
    }

    public static void put(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            MDC.put(SESSION_ID, sessionId);
        }
    }

    public static Map<String, String> captureAndPut(String sessionId) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        put(sessionId);
        return previous;
    }

    public static void restore(Map<String, String> previous) {
        if (previous == null) {
            MDC.remove(SESSION_ID);
        } else {
            MDC.setContextMap(previous);
        }
    }

    public static Runnable wrap(String sessionId, Runnable task) {
        return () -> {
            Map<String, String> previous = captureAndPut(sessionId);
            try {
                task.run();
            } finally {
                restore(previous);
            }
        };
    }
}
