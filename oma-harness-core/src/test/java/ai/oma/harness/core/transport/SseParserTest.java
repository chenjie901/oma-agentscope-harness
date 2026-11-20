package ai.oma.harness.core.transport;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseParserTest {

    @Test
    void parsesClientEventWithSequence() throws Exception {
        String body = """
                id: 7
                event: client_event
                data: {"event_id":"evt_1","event_type":"user.message","payload":{"text":"hi"},"sequence_num":7}

                """;
        List<Long> frames = new ArrayList<>();
        SseParser parser = new SseParser(new BufferedReader(new StringReader(body)), frames::add);

        SseParser.SseFrame frame = parser.next();

        assertNotNull(frame);
        assertEquals(7, frame.sequenceNum());
        assertEquals("client_event", frame.event());
        assertTrue(frame.isClientEvent());
        assertEquals("evt_1", frame.json().get("event_id").asText());
        assertEquals(List.of(7L), frames);
    }

    @Test
    void treatsKeepaliveCommentAsActivity() throws Exception {
        String body = """
                : keepalive

                """;
        List<Long> frames = new ArrayList<>();
        SseParser parser = new SseParser(new BufferedReader(new StringReader(body)), frames::add);

        SseParser.SseFrame frame = parser.next();

        assertNotNull(frame);
        assertNull(frame.data());
        assertEquals(1, frames.size());
    }

    @Test
    void stopsAtEndOfStream() throws Exception {
        SseParser parser = new SseParser(new BufferedReader(new StringReader("")), sequence -> { });

        assertNull(parser.next());
    }

    @Test
    void joinsMultilineData() throws Exception {
        String body = """
                id: 3
                event: client_event
                data: {"event_id":"evt_3",
                data: "event_type":"agent.message"}

                """;
        SseParser parser = new SseParser(new BufferedReader(new StringReader(body)), sequence -> { });

        SseParser.SseFrame frame = parser.next();

        assertNotNull(frame);
        assertEquals("agent.message", frame.json().get("event_type").asText());
    }
}
