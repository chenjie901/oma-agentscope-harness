package ai.oma.harness.core.model;

import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** 图片内容块在 OMA wire 格式与各模型后端格式之间的转换工具。 */
public final class ImageBlocks {
    public static final String DEFAULT_MEDIA_TYPE = "image/png";

    private ImageBlocks() {
    }

    /** 解析 OMA/Anthropic 的 image source 节点为标准化 source map（type/base64/url）。 */
    public static Map<String, Object> parseSource(JsonNode source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null || !source.isObject()) {
            return result;
        }
        String type = Json.text(source, "type");
        result.put("type", type);
        if ("base64".equals(type)) {
            result.put("media_type", Json.text(source, "media_type"));
            result.put("data", Json.text(source, "data"));
        } else if ("url".equals(type)) {
            result.put("url", Json.text(source, "url"));
        } else if ("file".equals(type)) {
            result.put("file_id", Json.text(source, "file_id"));
        }
        return result;
    }

    /** 解析 OpenAI image_url 里的 url（可为 data URL 或 http(s) URL）为标准化 source map。 */
    public static Map<String, Object> parseImageUrl(String url) {
        Map<String, Object> source = new LinkedHashMap<>();
        if (url == null || url.isBlank()) {
            return source;
        }
        if (url.startsWith("data:")) {
            int comma = url.indexOf(',');
            if (comma < 0) {
                return source;
            }
            String meta = url.substring(5, comma);
            String data = url.substring(comma + 1);
            String mediaType = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;
            source.put("type", "base64");
            source.put("media_type", mediaType.isBlank() ? DEFAULT_MEDIA_TYPE : mediaType);
            source.put("data", data);
        } else {
            source.put("type", "url");
            source.put("url", url);
        }
        return source;
    }

    /** 构造一个 OMA wire 格式的 image 内容块。 */
    public static Map<String, Object> block(Map<String, Object> source) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "image");
        entry.put("source", source);
        return entry;
    }
}
