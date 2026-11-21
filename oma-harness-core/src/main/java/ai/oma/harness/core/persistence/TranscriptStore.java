package ai.oma.harness.core.persistence;

import ai.oma.harness.core.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** transcript 存储：把 user/assistant 消息写入会话 transcript，供服务端展示与历史恢复。core 内部使用。 */
public final class TranscriptStore {
    private static final Logger LOG = LoggerFactory.getLogger(TranscriptStore.class);
    public static final String DIVERGED = "Local and remote log histories diverged";
    private static final int VERSION = 1;
    private static final String STATE_DIR_ENV = "OMA_HARNESS_STATE_DIR";
    private static final String DEFAULT_STATE_DIR = ".oma-harness";

    public record Record(long seq, String type, String uuid, JsonNode payload) {
    }

    private final String sessionId;
    private final Path file;
    private final List<Record> records;
    private final ObjectNode header;
    private FileChannel channel;
    private long nextSeq;

    private TranscriptStore(String sessionId, Path file, ObjectNode header, List<Record> records) {
        this.sessionId = sessionId;
        this.file = file;
        this.header = header;
        this.records = records;
        this.nextSeq = records.isEmpty() ? 0 : records.get(records.size() - 1).seq() + 1;
    }

    public static TranscriptStore open(String sessionId) {
        return open(root(), sessionId);
    }

    public static TranscriptStore open(Path root, String sessionId) {
        Path file = Paths.get(root.toString(), "sessions", sessionId, "transcript.jsonl");
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("transcript directory is not writable: " + file.getParent(), e);
        }
        repairTail(file);
        List<Record> records = new ArrayList<>();
        ObjectNode header = null;
        if (Files.exists(file)) {
            List<String> lines = readLines(file);
            if (!lines.isEmpty()) {
                JsonNode parsed = parseOrNull(lines.get(0));
                if (parsed instanceof ObjectNode object) {
                    header = object;
                }
            }
            for (int index = header == null ? 0 : 1; index < lines.size(); index++) {
                Record record = recordOf(lines.get(index));
                if (record != null) {
                    records.add(record);
                }
            }
        }
        if (header == null) {
            header = Json.object();
            header.put("version", VERSION);
            header.put("session_id", sessionId);
            header.put("created_at", Instant.now().toString());
        }
        TranscriptStore store = new TranscriptStore(sessionId, file, header, records);
        store.ensureHeader();
        LOG.info("transcript opened file={} records={}", file, records.size());
        return store;
    }

    public String sessionId() {
        return sessionId;
    }

    public Path file() {
        return file;
    }

    public ObjectNode header() {
        return header.deepCopy();
    }

    public List<Record> records() {
        synchronized (records) {
            return List.copyOf(records);
        }
    }

    public void append(List<JsonNode> payloads) {
        if (payloads.isEmpty()) {
            return;
        }
        List<Record> appended = new ArrayList<>(payloads.size());
        StringBuilder batch = new StringBuilder();
        synchronized (records) {
            for (JsonNode payload : payloads) {
                long seq = nextSeq++;
                ObjectNode line = Json.object();
                line.put("seq", seq);
                line.put("type", typeOf(payload));
                line.put("uuid", uuidOf(payload));
                line.set("payload", payload);
                batch.append(Json.write(line)).append('\n');
                appended.add(new Record(seq, typeOf(payload), uuidOf(payload), payload));
            }
        }
        try {
            FileChannel open = channel();
            long before = open.size();
            try {
                open.position(before);
                open.write(ByteBuffer.wrap(batch.toString().getBytes(StandardCharsets.UTF_8)));
                open.force(true);
            } catch (IOException e) {
                rollback(open, before);
                throw e;
            }
        } catch (IOException e) {
            discard(appended);
            throw new IllegalStateException("transcript append failed: " + file, e);
        }
        synchronized (records) {
            records.addAll(appended);
        }
        LOG.debug("transcript appended session={} count={} next_seq={}", sessionId, appended.size(), nextSeq);
    }

    public static boolean isPrefix(List<Record> prefix, List<Record> log) {
        if (prefix.size() > log.size()) {
            return false;
        }
        for (int index = 0; index < prefix.size(); index++) {
            Record left = prefix.get(index);
            Record right = log.get(index);
            if (!left.uuid().equals(right.uuid()) || !left.payload().equals(right.payload())) {
                return false;
            }
        }
        return true;
    }

    public static List<Record> asRecords(List<JsonNode> payloads) {
        List<Record> records = new ArrayList<>(payloads.size());
        for (int index = 0; index < payloads.size(); index++) {
            JsonNode payload = payloads.get(index);
            records.add(new Record(index, typeOf(payload), uuidOf(payload), payload));
        }
        return records;
    }

    public synchronized void close() {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
        }
        channel = null;
    }

    private void discard(List<Record> appended) {
        synchronized (records) {
            records.removeAll(appended);
            nextSeq -= appended.size();
        }
    }

    private void ensureHeader() {
        try {
            if (Files.exists(file) && Files.size(file) > 0) {
                return;
            }
            FileChannel open = channel();
            open.position(open.size());
            open.write(ByteBuffer.wrap((Json.write(header) + "\n").getBytes(StandardCharsets.UTF_8)));
            open.force(true);
        } catch (IOException e) {
            throw new IllegalStateException("transcript header write failed: " + file, e);
        }
    }

    private FileChannel channel() throws IOException {
        if (channel == null || !channel.isOpen()) {
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
        }
        return channel;
    }

    private static void rollback(FileChannel open, long size) {
        try {
            open.truncate(size);
            open.force(true);
        } catch (IOException ignored) {
        }
    }

    private static Record recordOf(String line) {
        JsonNode node = parseOrNull(line);
        if (node == null) {
            return null;
        }
        JsonNode seq = node.get("seq");
        JsonNode payload = node.get("payload");
        if (seq == null || !seq.isNumber() || payload == null) {
            return null;
        }
        String uuid = Json.text(node, "uuid");
        if (uuid == null || uuid.isBlank()) {
            uuid = uuidOf(payload);
        }
        String type = Json.text(node, "type");
        return new Record(seq.asLong(), type == null ? typeOf(payload) : type, uuid, payload);
    }

    private static String uuidOf(JsonNode payload) {
        String uuid = payload == null ? null : Json.text(payload, "uuid");
        return uuid == null ? "" : uuid;
    }

    private static String typeOf(JsonNode payload) {
        String type = payload == null ? null : Json.text(payload, "type");
        return type == null || type.isBlank() ? "event" : type;
    }

    private static JsonNode parseOrNull(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            return Json.parse(line);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void repairTail(Path file) {
        if (!Files.exists(file)) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0 || bytes[bytes.length - 1] == '\n') {
                return;
            }
            int end = bytes.length;
            while (end > 0 && bytes[end - 1] != '\n') {
                end--;
            }
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.truncate(end);
                channel.force(true);
            }
        } catch (IOException e) {
            throw new IllegalStateException("transcript tail repair failed: " + file, e);
        }
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("transcript read failed: " + file, e);
        }
    }

    private static Path root() {
        String configured = System.getenv(STATE_DIR_ENV);
        return configured == null || configured.isBlank()
                ? Paths.get(System.getProperty("user.home"), DEFAULT_STATE_DIR)
                : Paths.get(configured.trim());
    }
}
