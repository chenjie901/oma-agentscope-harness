package ai.oma.harness.core.agent;

import ai.oma.harness.core.attachments.AttachmentStore;
import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 把 OMA user.message 的 content 块解析为 AgentScope 内容块：文本原样保留，图片物化为 base64/URL ImageBlock。 */
public final class UserContentResolver {
    private static final Logger LOG = LoggerFactory.getLogger(UserContentResolver.class);
    private static final String DEFAULT_MEDIA_TYPE = "image/png";

    private UserContentResolver() {
    }

    /** 解析 user 消息 payload，返回可直接构造 UserMessage 的有序内容块。 */
    public static List<ContentBlock> resolve(JsonNode payload, AttachmentStore attachments) {
        JsonNode content = payload == null ? null : Json.nullable(payload.get("message"), "content");
        if (content == null) {
            return List.of();
        }
        if (content.isTextual()) {
            return List.of(TextBlock.builder().text(content.asText()).build());
        }
        if (!content.isArray()) {
            return List.of();
        }
        List<ContentBlock> blocks = new ArrayList<>();
        for (JsonNode block : content) {
            if (block == null || !block.isObject()) {
                continue;
            }
            String type = Json.text(block, "type");
            if ("text".equals(type)) {
                String text = Json.text(block, "text");
                if (text != null && !text.isBlank()) {
                    blocks.add(TextBlock.builder().text(text).build());
                }
            } else if ("image".equals(type)) {
                ImageBlock image = resolveImage(block, attachments);
                if (image != null) {
                    blocks.add(image);
                }
            }
        }
        return List.copyOf(blocks);
    }

    /** 从 payload 提取纯文本，用于 transcript 持久化与日志；图片不写入 internal events。 */
    public static String extractText(JsonNode payload) {
        JsonNode content = payload == null ? null : Json.nullable(payload.get("message"), "content");
        if (content == null) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode block : content) {
            if (block == null) {
                continue;
            }
            if (block.isTextual()) {
                builder.append(block.asText());
            } else if ("text".equals(Json.text(block, "type"))) {
                String text = Json.text(block, "text");
                if (text != null) {
                    builder.append(text);
                }
            }
        }
        return builder.toString();
    }

    private static ImageBlock resolveImage(JsonNode block, AttachmentStore attachments) {
        JsonNode source = block.get("source");
        if (source == null || !source.isObject()) {
            LOG.warn("image block has no source; skipping");
            return null;
        }
        String sourceType = Json.text(source, "type");
        String mediaType = firstNonBlank(Json.text(block, "media_type"), Json.text(source, "media_type"));
        try {
            if ("file".equals(sourceType)) {
                String fileId = Json.text(source, "file_id");
                if (fileId == null || fileId.isBlank()) {
                    LOG.warn("image file source has no file_id; skipping");
                    return null;
                }
                if (attachments == null) {
                    LOG.warn("image attachment is not available; skipping file_id={}", fileId);
                    return null;
                }
                byte[] bytes = attachments.readMessageAttachment(fileId);
                String encoded = Base64.getEncoder().encodeToString(bytes);
                LOG.debug("image attachment resolved file_id={} bytes={} media_type={}", fileId, bytes.length, mediaType);
                return new ImageBlock(new Base64Source(
                        mediaType == null || mediaType.isBlank() ? DEFAULT_MEDIA_TYPE : mediaType, encoded));
            }
            if ("base64".equals(sourceType)) {
                String data = Json.text(source, "data");
                if (data == null || data.isBlank()) {
                    LOG.warn("image base64 source has no data; skipping");
                    return null;
                }
                return new ImageBlock(new Base64Source(
                        mediaType == null || mediaType.isBlank() ? DEFAULT_MEDIA_TYPE : mediaType, data));
            }
            if ("url".equals(sourceType)) {
                String url = Json.text(source, "url");
                if (url == null || url.isBlank()) {
                    LOG.warn("image url source has no url; skipping");
                    return null;
                }
                return new ImageBlock(new URLSource(url, mediaType));
            }
            LOG.warn("unsupported image source type={}; skipping", sourceType);
        } catch (RuntimeException e) {
            LOG.warn("failed to resolve image source type={}: {}", sourceType, e.getMessage());
        }
        return null;
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
