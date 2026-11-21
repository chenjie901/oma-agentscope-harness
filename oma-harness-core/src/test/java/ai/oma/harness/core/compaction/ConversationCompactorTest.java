package ai.oma.harness.core.compaction;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationCompactorTest {

    private static final SummaryProvider STUB_SUMMARY = (prefix, prompt) -> Mono.just("SUMMARY");

    @Test
    void doesNotCompactBelowThreshold() {
        CompactionConfig config = CompactionConfig.builder()
                .triggerMessages(10)
                .triggerTokens(0)
                .build();
        ConversationCompactor compactor = new ConversationCompactor(STUB_SUMMARY);

        Optional<CompactionResult> result = compactor
                .compactIfNeeded(textMessages(3), config, 0)
                .block();

        assertTrue(result.isEmpty());
    }

    @Test
    void compactsPrefixAndKeepsTailByMessageCount() {
        CompactionConfig config = CompactionConfig.builder()
                .triggerMessages(5)
                .keepMessages(2)
                .keepTokens(0)
                .prune(null)
                .build();
        ConversationCompactor compactor = new ConversationCompactor(STUB_SUMMARY);

        Optional<CompactionResult> result = compactor
                .compactIfNeeded(textMessages(6), config, 0)
                .block();

        assertTrue(result.isPresent());
        CompactionResult compacted = result.get();
        assertEquals(3, compacted.messages().size());
        assertEquals("SUMMARY", compacted.summary());
        assertEquals(4, compacted.droppedMessages());
        assertEquals(ConversationCompactor.SUMMARY_MSG_NAME, compacted.messages().get(0).getName());
        assertEquals(MsgRole.USER, compacted.messages().get(0).getRole());
        assertEquals("m4", compacted.messages().get(1).getTextContent());
        assertEquals("m5", compacted.messages().get(2).getTextContent());
    }

    @Test
    void summaryMessageIsRecognizedByMarker() {
        CompactionConfig config = CompactionConfig.builder()
                .triggerMessages(5)
                .keepMessages(2)
                .keepTokens(0)
                .prune(null)
                .build();
        ConversationCompactor compactor = new ConversationCompactor(STUB_SUMMARY);

        Optional<CompactionResult> result = compactor
                .compactIfNeeded(textMessages(6), config, 0)
                .block();

        assertTrue(result.isPresent());
        List<Msg> kept = ConversationCompactor.filterSummaryMessages(result.get().messages());
        assertEquals(2, kept.size());
    }

    @Test
    void summarizationFailurePropagates() {
        CompactionConfig config = CompactionConfig.builder()
                .triggerMessages(2)
                .keepMessages(1)
                .keepTokens(0)
                .prune(null)
                .build();
        ConversationCompactor compactor = new ConversationCompactor((prefix, prompt) ->
                Mono.error(new IllegalStateException("boom")));

        try {
            compactor.compactIfNeeded(textMessages(3), config, 0).block();
            org.junit.jupiter.api.Assertions.fail("expected summarization failure to propagate");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("summarization failed"));
        }
    }

    @Test
    void interruptedSummaryPropagates() {
        CompactionConfig config = CompactionConfig.builder()
                .triggerMessages(2)
                .keepMessages(1)
                .keepTokens(0)
                .prune(null)
                .build();
        ConversationCompactor compactor = new ConversationCompactor((prefix, prompt) ->
                Mono.error(new InterruptedException("stop")));

        try {
            compactor.compactIfNeeded(textMessages(3), config, 0).block();
            org.junit.jupiter.api.Assertions.fail("expected interruption to propagate");
        } catch (RuntimeException e) {
            assertTrue(containsInterrupted(e));
        }
    }

    @Test
    void dynamicTriggerFallsBackWhenNoContextWindow() {
        CompactionConfig config = CompactionConfig.builder()
                .triggerMessages(0)
                .triggerTokens(0)
                .keepMessages(2)
                .keepTokens(0)
                .prune(null)
                .build();
        ConversationCompactor compactor = new ConversationCompactor(STUB_SUMMARY);

        Optional<CompactionResult> result = compactor
                .compactIfNeeded(textMessages(3), config, 0)
                .block();

        assertTrue(result.isEmpty());
    }

    private static boolean containsInterrupted(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static List<Msg> textMessages(int count) {
        List<Msg> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(Msg.builder()
                    .role(i % 2 == 0 ? MsgRole.USER : MsgRole.ASSISTANT)
                    .textContent("m" + i)
                    .build());
        }
        return messages;
    }
}
