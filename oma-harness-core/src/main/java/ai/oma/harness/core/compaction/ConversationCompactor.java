package ai.oma.harness.core.compaction;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 移植自 AgentScope harness 的对话压缩器，去掉 workspace/memory/offload 依赖，摘要通过 SummaryProvider 完成。 */
public final class ConversationCompactor {
    private static final Logger log = LoggerFactory.getLogger(ConversationCompactor.class);

    public static final String SUMMARY_MSG_NAME = "__compaction_summary__";

    private final SummaryProvider summaryProvider;

    public ConversationCompactor(SummaryProvider summaryProvider) {
        this.summaryProvider = summaryProvider;
    }

    /** 若达到触发条件则压缩对话，返回摘要消息加保留尾部的替换列表。 */
    public Mono<Optional<CompactionResult>> compactIfNeeded(
            List<Msg> conversationMessages, CompactionConfig config, int contextWindowSize) {
        if (conversationMessages == null || conversationMessages.isEmpty()) {
            return Mono.just(Optional.empty());
        }

        CompactionConfig effective = resolveEffectiveConfig(config, contextWindowSize);
        List<Msg> messages =
                pruneToolResults(
                        truncateArgs(conversationMessages, effective.truncateArgsConfig()),
                        effective.pruneConfig());

        int totalTokens = TokenCounterUtil.calculateToken(messages);
        if (!shouldCompact(messages, totalTokens, effective)) {
            return Mono.just(Optional.empty());
        }

        int cutoff = determineCutoffIndex(messages, totalTokens, effective);
        if (cutoff <= 0) {
            log.debug("compaction triggered but safe cutoff is 0, skipping");
            return Mono.just(Optional.empty());
        }

        List<Msg> summaryInput = new ArrayList<>(messages.subList(0, cutoff));
        List<Msg> tail = new ArrayList<>(messages.subList(cutoff, messages.size()));
        log.info("compaction triggered total_msgs={} total_tokens={} cutoff={} keep_msgs={}",
                messages.size(), totalTokens, cutoff, tail.size());

        return summarizePrefix(summaryInput, effective)
                .map(summary -> {
                    Msg summaryMsg = buildSummaryMessage(summary);
                    List<Msg> compacted = new ArrayList<>();
                    compacted.add(summaryMsg);
                    compacted.addAll(tail);
                    log.info("compaction complete {} msgs -> 1 summary + {} tail", messages.size(), tail.size());
                    return Optional.of(new CompactionResult(compacted, summary, cutoff));
                });
    }

    private CompactionConfig resolveEffectiveConfig(CompactionConfig config, int contextWindowSize) {
        boolean needsDynamic = config.triggerTokens() == 0 || config.keepTokens() == -1;
        if (!needsDynamic) {
            return config;
        }

        int effectiveTrigger = config.triggerTokens();
        if (effectiveTrigger == 0) {
            if (contextWindowSize > 0) {
                effectiveTrigger = contextWindowSize - config.reserved();
                if (effectiveTrigger <= 0) {
                    effectiveTrigger = Math.max(1, contextWindowSize / 2);
                    log.warn("dynamic compaction trigger clamped context_window={} reserved={} trigger={}",
                            contextWindowSize, config.reserved(), effectiveTrigger);
                }
            } else {
                effectiveTrigger = CompactionConfig.FALLBACK_TRIGGER_TOKENS;
                log.debug("model has no context window, using fallback trigger={}", effectiveTrigger);
            }
        }

        int effectiveKeep = config.keepTokens();
        if (effectiveKeep == -1) {
            if (contextWindowSize > 0) {
                int usable = contextWindowSize - config.reserved();
                effectiveKeep = (int) Math.min(
                        config.keepTokensMax(),
                        Math.max(config.keepTokensMin(), usable * config.keepTokensRatio()));
            } else {
                effectiveKeep = 0;
            }
        }

        return config.withEffective(effectiveTrigger, effectiveKeep);
    }

    private static boolean shouldCompact(List<Msg> messages, int totalTokens, CompactionConfig config) {
        if (config.triggerMessages() > 0 && messages.size() >= config.triggerMessages()) {
            log.debug("compaction trigger message_count={} threshold={}", messages.size(), config.triggerMessages());
            return true;
        }
        if (config.triggerTokens() > 0 && totalTokens >= config.triggerTokens()) {
            log.debug("compaction trigger total_tokens={} threshold={}", totalTokens, config.triggerTokens());
            return true;
        }
        return false;
    }

    private static int determineCutoffIndex(List<Msg> messages, int totalTokens, CompactionConfig config) {
        int rawCutoff;
        if (config.keepTokens() > 0) {
            rawCutoff = findTokenBasedCutoff(messages, totalTokens, config.keepTokens());
        } else {
            rawCutoff = findMessageBasedCutoff(messages, config.keepMessages());
        }
        return findSafeCutoffPoint(messages, rawCutoff);
    }

    private static int findTokenBasedCutoff(List<Msg> messages, int totalTokens, int keepTokens) {
        if (totalTokens <= keepTokens) {
            return 0;
        }
        int left = 0;
        int right = messages.size();
        int candidate = messages.size();
        int maxIter = Integer.SIZE - Integer.numberOfLeadingZeros(messages.size()) + 1;
        for (int i = 0; i < maxIter && left < right; i++) {
            int mid = (left + right) / 2;
            if (TokenCounterUtil.calculateToken(messages.subList(mid, messages.size())) <= keepTokens) {
                candidate = mid;
                right = mid;
            } else {
                left = mid + 1;
            }
        }
        return Math.min(candidate, messages.size() - 1);
    }

    private static int findMessageBasedCutoff(List<Msg> messages, int keepMessages) {
        if (messages.size() <= keepMessages) {
            return 0;
        }
        return messages.size() - keepMessages;
    }

    private static int findSafeCutoffPoint(List<Msg> messages, int cutoffIndex) {
        if (cutoffIndex <= 0 || cutoffIndex >= messages.size()) {
            return cutoffIndex;
        }
        Msg atCutoff = messages.get(cutoffIndex);
        if (atCutoff.getRole() != MsgRole.TOOL) {
            return cutoffIndex;
        }
        List<String> toolCallIds = new ArrayList<>();
        int idx = cutoffIndex;
        while (idx < messages.size() && messages.get(idx).getRole() == MsgRole.TOOL) {
            for (ContentBlock block : messages.get(idx).getContent()) {
                if (block instanceof ToolResultBlock tr && tr.getId() != null) {
                    toolCallIds.add(tr.getId());
                }
            }
            idx++;
        }
        if (toolCallIds.isEmpty()) {
            return idx;
        }
        for (int i = cutoffIndex - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.ASSISTANT) {
                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof ToolUseBlock tu && toolCallIds.contains(tu.getId())) {
                        return i;
                    }
                }
            }
        }
        return idx;
    }

    private Mono<String> summarizePrefix(List<Msg> prefix, CompactionConfig config) {
        if (prefix.isEmpty()) {
            return Mono.just("No previous conversation history.");
        }
        String formatted = formatMessagesForSummary(prefix);
        String prompt = config.summaryPrompt().replace("{messages}", formatted);
        return summaryProvider.summarize(prefix, prompt)
                .map(value -> value == null ? "" : value.strip())
                .filter(value -> !value.isBlank())
                .switchIfEmpty(Mono.error(new IllegalStateException("empty summarization result")))
                .onErrorResume(error -> Mono.error(new IllegalStateException(
                        "summarization failed: " + error.getMessage(), error)));
    }

    static String formatMessagesForSummary(List<Msg> messages) {
        return messages.stream()
                .filter(message -> message.getRole() != null && message.getRole() != MsgRole.SYSTEM)
                .map(ConversationCompactor::renderMessageForSummary)
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    private static String renderMessageForSummary(Msg msg) {
        String roleLabel = switch (msg.getRole()) {
            case USER -> "Human";
            case ASSISTANT -> "AI";
            case TOOL -> "Tool";
            default -> msg.getRole().name();
        };
        StringBuilder builder = new StringBuilder(roleLabel).append(": ");
        boolean first = true;
        for (ContentBlock block : msg.getContent()) {
            if (!first) {
                builder.append(' ');
            }
            first = false;
            if (block instanceof TextBlock tb && tb.getText() != null && !tb.getText().isBlank()) {
                builder.append(tb.getText().strip());
            } else if (block instanceof ToolUseBlock tu) {
                builder.append("[tool_call: ").append(tu.getName()).append(']');
            } else if (block instanceof ToolResultBlock tr) {
                String text = extractToolResultText(tr);
                builder.append("[tool_result: ")
                        .append(tr.getName() != null ? tr.getName() : "?")
                        .append("] ");
                if (!text.isBlank()) {
                    builder.append(text.length() > 500 ? text.substring(0, 500) + "..." : text);
                }
            }
        }
        return builder.toString().strip();
    }

    private static String extractToolResultText(ToolResultBlock tr) {
        if (tr.getOutput() == null) {
            return "";
        }
        return tr.getOutput().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining(" "));
    }

    private static Msg buildSummaryMessage(String summary) {
        String content = "Here is a summary of the conversation to date:\n\n" + summary;
        return Msg.builder()
                .id(buildSummaryMessageId(content))
                .role(MsgRole.USER)
                .name(SUMMARY_MSG_NAME)
                .content(TextBlock.builder().text(content).build())
                .build();
    }

    private static String buildSummaryMessageId(String content) {
        return SUMMARY_MSG_NAME + ":" + UUID.nameUUIDFromBytes(content.getBytes(StandardCharsets.UTF_8));
    }

    static List<Msg> filterSummaryMessages(List<Msg> messages) {
        return messages.stream()
                .filter(message -> !SUMMARY_MSG_NAME.equals(message.getName()))
                .collect(Collectors.toList());
    }

    List<Msg> pruneToolResults(List<Msg> messages, CompactionConfig.PruneConfig pruneConfig) {
        if (pruneConfig == null || messages == null || messages.isEmpty()) {
            return messages;
        }
        int protectBudget = pruneConfig.protectTokens();
        int maxChars = pruneConfig.maxOutputChars();
        Set<String> excluded = pruneConfig.excludedTools();

        int protectedTokens = 0;
        int prunableTokens = 0;
        List<int[]> toPrune = new ArrayList<>();

        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() != MsgRole.TOOL || msg.getContent() == null) {
                continue;
            }
            List<ContentBlock> blocks = msg.getContent();
            for (int j = blocks.size() - 1; j >= 0; j--) {
                if (!(blocks.get(j) instanceof ToolResultBlock tr)) {
                    continue;
                }
                if (tr.getName() != null && excluded.contains(tr.getName())) {
                    continue;
                }
                String text = extractToolResultText(tr);
                if (text.isBlank()) {
                    continue;
                }
                int tokens = TokenCounterUtil.calculateToken(List.of(msg));
                if (protectedTokens < protectBudget) {
                    protectedTokens += tokens;
                    continue;
                }
                if (text.length() > maxChars) {
                    prunableTokens += tokens;
                    toPrune.add(new int[] {i, j});
                }
            }
        }

        if (prunableTokens < pruneConfig.minimumTokens() || toPrune.isEmpty()) {
            return messages;
        }

        List<Msg> result = new ArrayList<>(messages);
        for (int[] pos : toPrune) {
            int msgIdx = pos[0];
            int blockIdx = pos[1];
            Msg msg = result.get(msgIdx);
            List<ContentBlock> newBlocks = new ArrayList<>(msg.getContent());
            ToolResultBlock tr = (ToolResultBlock) newBlocks.get(blockIdx);
            String preview = buildPrunePreview(extractToolResultText(tr), maxChars);
            ToolResultBlock pruned = ToolResultBlock.builder()
                    .id(tr.getId())
                    .name(tr.getName())
                    .output(List.of(TextBlock.builder().text(preview).build()))
                    .build();
            newBlocks.set(blockIdx, pruned);
            result.set(msgIdx, Msg.builder()
                    .id(msg.getId())
                    .name(msg.getName())
                    .role(msg.getRole())
                    .content(newBlocks)
                    .metadata(msg.getMetadata())
                    .timestamp(msg.getTimestamp())
                    .build());
        }
        log.info("pruned tool_results={} tokens_freed={}", toPrune.size(), prunableTokens);
        return result;
    }

    private static String buildPrunePreview(String text, int maxChars) {
        int half = maxChars / 2;
        String head = text.substring(0, Math.min(half, text.length()));
        String tail = text.length() > half ? text.substring(Math.max(text.length() - half, half)) : "";
        int removed = text.length() - head.length() - tail.length();
        return removed <= 0 ? text : head + "\n\n...(" + removed + " chars pruned)...\n\n" + tail;
    }

    List<Msg> truncateArgs(List<Msg> messages, CompactionConfig.TruncateArgsConfig config) {
        if (config == null || messages == null || messages.isEmpty()) {
            return messages;
        }
        int totalTokens = TokenCounterUtil.calculateToken(messages);
        if (!shouldTruncateArgs(messages, totalTokens, config)) {
            return messages;
        }
        int cutoff = determineTruncateCutoff(messages, config);
        if (cutoff >= messages.size()) {
            return messages;
        }
        boolean anyModified = false;
        List<Msg> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            if (i < cutoff && msg.getRole() == MsgRole.ASSISTANT) {
                Msg truncated = truncateToolUseArgs(msg, config);
                result.add(truncated);
                if (truncated != msg) {
                    anyModified = true;
                }
            } else {
                result.add(msg);
            }
        }
        return anyModified ? result : messages;
    }

    private static boolean shouldTruncateArgs(
            List<Msg> messages, int totalTokens, CompactionConfig.TruncateArgsConfig config) {
        if (config.triggerMessages() > 0 && messages.size() >= config.triggerMessages()) {
            return true;
        }
        return config.triggerTokens() > 0 && totalTokens >= config.triggerTokens();
    }

    private static int determineTruncateCutoff(
            List<Msg> messages, CompactionConfig.TruncateArgsConfig config) {
        if (config.keepTokens() > 0) {
            int tokensKept = 0;
            for (int i = messages.size() - 1; i >= 0; i--) {
                int msgTokens = TokenCounterUtil.calculateToken(List.of(messages.get(i)));
                if (tokensKept + msgTokens > config.keepTokens()) {
                    return i + 1;
                }
                tokensKept += msgTokens;
            }
            return 0;
        }
        return Math.max(0, messages.size() - config.keepMessages());
    }

    private static Msg truncateToolUseArgs(Msg msg, CompactionConfig.TruncateArgsConfig config) {
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return msg;
        }
        boolean anyModified = false;
        List<ContentBlock> newBlocks = new ArrayList<>(blocks.size());
        for (ContentBlock block : blocks) {
            if (block instanceof ToolUseBlock tu) {
                ToolUseBlock truncated = truncateToolUseBlock(tu, config);
                newBlocks.add(truncated);
                if (truncated != tu) {
                    anyModified = true;
                }
            } else {
                newBlocks.add(block);
            }
        }
        return anyModified ? Msg.builder()
                .role(msg.getRole())
                .name(msg.getName())
                .content(newBlocks)
                .build() : msg;
    }

    private static ToolUseBlock truncateToolUseBlock(
            ToolUseBlock tu, CompactionConfig.TruncateArgsConfig config) {
        Map<String, Object> input = tu.getInput();
        if (input == null || input.isEmpty()) {
            return tu;
        }
        boolean anyModified = false;
        Map<String, Object> newInput = new HashMap<>(input);
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry.getValue() instanceof String value && value.length() > config.maxArgLength()) {
                newInput.put(entry.getKey(), value.substring(0, 20) + config.truncationText());
                anyModified = true;
            }
        }
        return anyModified ? ToolUseBlock.builder().id(tu.getId()).name(tu.getName()).input(newInput).build() : tu;
    }
}
