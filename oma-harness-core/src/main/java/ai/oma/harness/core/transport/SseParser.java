package ai.oma.harness.core.transport;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.function.LongConsumer;

public final class SseParser {
    private final BufferedReader reader;
    private final LongConsumer onFrame;

    public SseParser(BufferedReader reader, LongConsumer onFrame) {
        this.reader = reader;
        this.onFrame = onFrame == null ? sequence -> { } : onFrame;
    }

    public SseFrame next() throws IOException {
        String id = null;
        String event = null;
        StringBuilder data = null;
        boolean comment = false;
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.startsWith("data:")) {
                String part = line.length() > 5 && line.charAt(5) == ' ' ? line.substring(6) : line.substring(5);
                if (data == null) {
                    data = new StringBuilder();
                } else {
                    data.append('\n');
                }
                data.append(part);
                continue;
            }
            if (line.startsWith("id:")) {
                String value = line.length() > 3 && line.charAt(3) == ' ' ? line.substring(4) : line.substring(3);
                if (!value.contains("\0")) {
                    id = value;
                }
                continue;
            }
            if (line.startsWith("event:")) {
                event = line.length() > 6 && line.charAt(6) == ' ' ? line.substring(7) : line.substring(6);
                continue;
            }
            if (line.startsWith(":")) {
                comment = true;
                continue;
            }
            if (line.isEmpty()) {
                if (data != null || comment) {
                    long sequence = parseSequence(id);
                    onFrame.accept(sequence);
                    return new SseFrame(id, event, data == null ? null : data.toString(), sequence);
                }
                id = null;
                event = null;
                data = null;
                comment = false;
            }
        }
    }

    private static long parseSequence(String id) {
        if (id == null || id.isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(id.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public record SseFrame(String id, String event, String data, long sequenceNum) {
        public boolean isClientEvent() {
            return "client_event".equals(event) && data != null && !data.isBlank();
        }

        public JsonNode json() {
            return ai.oma.harness.core.json.Json.parse(data);
        }
    }
}
