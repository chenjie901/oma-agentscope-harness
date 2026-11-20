package ai.oma.harness.core.persistence;

import ai.oma.harness.core.json.Json;
import ai.oma.harness.core.transport.WorkerTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InternalEventStore {
    private static final Logger LOG = LoggerFactory.getLogger(InternalEventStore.class);
    private static final int SEEN_CAPACITY = 4096;
    private static final int MAX_READ_PAGES = 10;
    private static final int MAX_UPLOAD_ATTEMPTS = 10;
    private static final long RETRY_BASE_MILLIS = 100;
    private static final long RETRY_MAX_MILLIS = 30_000;

    private final WorkerTransport transport;
    private final LongSupplier epoch;
    private final TranscriptStore store;
    private final int batchSize;
    private final List<JsonNode> buffer = new ArrayList<>();
    private final Map<String, Boolean> seen = new LinkedHashMap<>(SEEN_CAPACITY, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SEEN_CAPACITY;
        }
    };
    private volatile RuntimeException terminal;

    public InternalEventStore(WorkerTransport transport, LongSupplier epoch, int batchSize, TranscriptStore store) {
        this.transport = transport;
        this.epoch = epoch;
        this.batchSize = Math.max(1, batchSize);
        this.store = store;
    }

    public void record(ObjectNode payload) {
        if (terminal != null) {
            throw new IllegalStateException("transcript replication stopped", terminal);
        }
        String uuid = Json.text(payload, "uuid");
        if (uuid == null || uuid.isBlank()) {
            return;
        }
        synchronized (buffer) {
            if (seen.putIfAbsent(uuid, Boolean.TRUE) != null) {
                return;
            }
        }
        try {
            store.append(List.of(payload));
        } catch (RuntimeException e) {
            terminal = e;
            throw new IllegalStateException("transcript append failed", e);
        }
        synchronized (buffer) {
            buffer.add(payload);
        }
        if (pending() >= batchSize) {
            flush();
        }
    }

    public int pending() {
        synchronized (buffer) {
            return buffer.size();
        }
    }

    public boolean isTerminal() {
        return terminal != null;
    }

    public void flush() {
        List<JsonNode> batch;
        synchronized (buffer) {
            if (buffer.isEmpty()) {
                return;
            }
            batch = List.copyOf(buffer);
        }
        RuntimeException failure = upload(batch);
        if (failure != null) {
            terminal = failure;
            LOG.error("internal event replication failed", failure);
            throw new IllegalStateException("internal event replication failed", failure);
        }
        synchronized (buffer) {
            buffer.subList(0, batch.size()).clear();
        }
    }

    public List<JsonNode> synchronize(boolean subagents) {
        List<JsonNode> remote = payloads(readRemote(subagents));
        List<TranscriptStore.Record> local = store.records();
        List<TranscriptStore.Record> remoteRecords = TranscriptStore.asRecords(remote);
        boolean localPrefix = TranscriptStore.isPrefix(local, remoteRecords);
        boolean remotePrefix = TranscriptStore.isPrefix(remoteRecords, local);
        if (!localPrefix && !remotePrefix) {
            throw new IllegalStateException(TranscriptStore.DIVERGED);
        }
        List<JsonNode> merged = localPayloads(local);
        if (localPrefix && remote.size() > merged.size()) {
            List<JsonNode> missing = remote.subList(merged.size(), remote.size());
            store.append(missing);
            merged = remote;
        }
        if (remote.size() < merged.size()) {
            enqueueTail(merged.subList(remote.size(), merged.size()));
        }
        remember(merged);
        return merged;
    }

    private static List<JsonNode> localPayloads(List<TranscriptStore.Record> records) {
        List<JsonNode> payloads = new ArrayList<>(records.size());
        for (TranscriptStore.Record record : records) {
            payloads.add(record.payload());
        }
        return payloads;
    }

    private RuntimeException upload(List<JsonNode> batch) {
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= MAX_UPLOAD_ATTEMPTS; attempt++) {
            try {
                transport.appendInternalEvents(epoch.getAsLong(), batch);
                return null;
            } catch (RuntimeException e) {
                failure = e;
                if (attempt == MAX_UPLOAD_ATTEMPTS) {
                    break;
                }
                sleep(backoffMillis(attempt));
            }
        }
        return failure;
    }

    private static long backoffMillis(int attempt) {
        long delay = RETRY_BASE_MILLIS << Math.min(attempt - 1, 20);
        return Math.min(RETRY_MAX_MILLIS, delay);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void enqueueTail(List<JsonNode> payloads) {
        synchronized (buffer) {
            buffer.addAll(payloads);
        }
    }

    public void remember(List<JsonNode> events) {
        synchronized (buffer) {
            for (JsonNode payload : events) {
                String uuid = Json.text(payload, "uuid");
                if (uuid != null && !uuid.isBlank()) {
                    seen.putIfAbsent(uuid, Boolean.TRUE);
                }
            }
        }
    }

    private List<JsonNode> readRemote(boolean subagents) {
        List<JsonNode> events = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_READ_PAGES; page++) {
            WorkerTransport.InternalEventsPage result = transport.readInternalEvents(epoch.getAsLong(), cursor, subagents);
            events.addAll(result.events());
            if (!result.hasMore()) {
                break;
            }
            cursor = result.nextCursor();
        }
        return events;
    }

    private static List<JsonNode> payloads(List<JsonNode> rows) {
        List<JsonNode> payloads = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            JsonNode payload = row.get("payload");
            if (payload != null && payload.isObject()) {
                payloads.add(payload);
            }
        }
        return payloads;
    }
}
