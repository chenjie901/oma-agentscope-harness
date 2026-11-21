package ai.oma.harness.core.compaction;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;

import java.util.List;
import java.util.Map;

/** 基于字符数的 token 估算工具，移植自 AgentScope harness，零外部依赖。 */
public final class TokenCounterUtil {
    private static final double CHARS_PER_TOKEN = 2.5;
    private static final int MESSAGE_OVERHEAD = 5;
    private static final int TOOL_CALL_OVERHEAD = 10;
    private static final int TOOL_RESULT_OVERHEAD = 8;

    private TokenCounterUtil() {
    }

    public static int calculateToken(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Msg msg : messages) {
            total += estimateMessageTokens(msg);
        }
        return total;
    }

    private static int estimateMessageTokens(Msg msg) {
        if (msg == null) {
            return 0;
        }
        int tokens = MESSAGE_OVERHEAD;
        if (msg.getRole() != null) {
            tokens += estimateTextTokens(msg.getRole().name());
        }
        if (msg.getName() != null) {
            tokens += estimateTextTokens(msg.getName());
        }
        List<ContentBlock> content = msg.getContent();
        if (content != null) {
            for (ContentBlock block : content) {
                tokens += estimateContentBlockTokens(block);
            }
        }
        return tokens;
    }

    private static int estimateContentBlockTokens(ContentBlock block) {
        if (block == null) {
            return 0;
        }
        if (block instanceof TextBlock textBlock) {
            return estimateTextTokens(textBlock.getText());
        }
        if (block instanceof ThinkingBlock thinkingBlock) {
            return estimateTextTokens(thinkingBlock.getThinking());
        }
        if (block instanceof ToolUseBlock toolUseBlock) {
            return estimateToolUseBlockTokens(toolUseBlock);
        }
        if (block instanceof ToolResultBlock toolResultBlock) {
            return estimateToolResultBlockTokens(toolResultBlock);
        }
        return 5;
    }

    private static int estimateToolUseBlockTokens(ToolUseBlock toolUseBlock) {
        int tokens = TOOL_CALL_OVERHEAD;
        if (toolUseBlock.getName() != null) {
            tokens += estimateTextTokens(toolUseBlock.getName());
        }
        if (toolUseBlock.getId() != null) {
            tokens += estimateTextTokens(toolUseBlock.getId());
        }
        Map<String, Object> input = toolUseBlock.getInput();
        if (input != null && !input.isEmpty()) {
            tokens += estimateTextTokens(estimateMapAsJson(input));
        }
        if (toolUseBlock.getContent() != null) {
            tokens += estimateTextTokens(toolUseBlock.getContent());
        }
        return tokens;
    }

    private static int estimateToolResultBlockTokens(ToolResultBlock toolResultBlock) {
        int tokens = TOOL_RESULT_OVERHEAD;
        if (toolResultBlock.getName() != null) {
            tokens += estimateTextTokens(toolResultBlock.getName());
        }
        if (toolResultBlock.getId() != null) {
            tokens += estimateTextTokens(toolResultBlock.getId());
        }
        List<ContentBlock> output = toolResultBlock.getOutput();
        if (output != null) {
            for (ContentBlock outputBlock : output) {
                tokens += estimateContentBlockTokens(outputBlock);
            }
        }
        return tokens;
    }

    private static int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    private static String estimateMapAsJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                builder.append(",");
            }
            first = false;
            builder.append('"').append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof String) {
                builder.append('"').append(value).append('"');
            } else {
                builder.append(value != null ? value : "null");
            }
        }
        return builder.append("}").toString();
    }
}
