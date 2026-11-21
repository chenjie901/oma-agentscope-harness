package ai.oma.harness.core.attachments;

import ai.oma.harness.core.transport.WorkerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.LongSupplier;

public final class AttachmentStore {
    private static final Logger LOG = LoggerFactory.getLogger(AttachmentStore.class);

    private final WorkerTransport transport;
    private final LongSupplier epoch;

    public AttachmentStore(WorkerTransport transport, LongSupplier epoch) {
        this.transport = transport;
        this.epoch = epoch;
    }

    public String uploadImage(byte[] bytes, String mediaType) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("attachment bytes are empty");
        }
        String sha256 = sha256(bytes);
        transport.uploadImageAttachment(epoch.getAsLong(), sha256, bytes, mediaType);
        LOG.debug("attachment uploaded sha={} bytes={} type={}", sha256, bytes.length, mediaType);
        return sha256;
    }

    public byte[] readMessageAttachment(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("file id is required");
        }
        return transport.readMessageAttachment(epoch.getAsLong(), fileId);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String text) {
        return sha256(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
    }
}
