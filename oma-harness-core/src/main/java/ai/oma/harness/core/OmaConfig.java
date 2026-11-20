package ai.oma.harness.core;

import java.net.URI;
import java.time.Duration;

public record OmaConfig(
        URI endpoint,
        String sessionId,
        String ingressToken,
        String modelToken,
        long workerEpoch,
        String threadId,
        Duration heartbeatInterval,
        Duration streamIdleTimeout,
        Duration reconnectBudget,
        Duration maxReconnectBackoff,
        Duration requestTimeout) {

    public OmaConfig {
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint is required");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (ingressToken == null || ingressToken.isBlank()) {
            throw new IllegalArgumentException("ingressToken is required");
        }
        if (workerEpoch <= 0) {
            throw new IllegalArgumentException("workerEpoch must be positive");
        }
        threadId = threadId == null || threadId.isBlank() ? "root" : threadId;
        heartbeatInterval = heartbeatInterval == null ? Duration.ofSeconds(10) : heartbeatInterval;
        streamIdleTimeout = streamIdleTimeout == null ? Duration.ofSeconds(45) : streamIdleTimeout;
        reconnectBudget = reconnectBudget == null ? Duration.ofMinutes(10) : reconnectBudget;
        maxReconnectBackoff = maxReconnectBackoff == null ? Duration.ofSeconds(30) : maxReconnectBackoff;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(35) : requestTimeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String effectiveModelToken() {
        return modelToken == null || modelToken.isBlank() ? ingressToken : modelToken;
    }

    public URI workerBase() {
        String base = endpoint.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/v1/code/sessions/" + sessionId + "/worker");
    }

    public URI workerUri(String suffix) {
        return URI.create(workerBase().toString() + "/" + suffix);
    }

    public URI sessionBase() {
        String base = endpoint.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/v2/ccr-sessions/" + sessionId);
    }

    public URI apiBase() {
        String base = endpoint.toString();
        return URI.create(base.endsWith("/") ? base : base + "/");
    }

    public static final class Builder {
        private URI endpoint;
        private String sessionId;
        private String ingressToken;
        private String modelToken;
        private long workerEpoch;
        private String threadId = "root";
        private Duration heartbeatInterval;
        private Duration streamIdleTimeout;
        private Duration reconnectBudget;
        private Duration maxReconnectBackoff;
        private Duration requestTimeout;

        public Builder endpoint(URI value) {
            this.endpoint = value;
            return this;
        }

        public Builder endpoint(String value) {
            this.endpoint = URI.create(value);
            return this;
        }

        public Builder sessionId(String value) {
            this.sessionId = value;
            return this;
        }

        public Builder ingressToken(String value) {
            this.ingressToken = value;
            return this;
        }

        public Builder modelToken(String value) {
            this.modelToken = value;
            return this;
        }

        public Builder workerEpoch(long value) {
            this.workerEpoch = value;
            return this;
        }

        public Builder threadId(String value) {
            this.threadId = value;
            return this;
        }

        public Builder heartbeatInterval(Duration value) {
            this.heartbeatInterval = value;
            return this;
        }

        public Builder streamIdleTimeout(Duration value) {
            this.streamIdleTimeout = value;
            return this;
        }

        public Builder reconnectBudget(Duration value) {
            this.reconnectBudget = value;
            return this;
        }

        public Builder maxReconnectBackoff(Duration value) {
            this.maxReconnectBackoff = value;
            return this;
        }

        public Builder requestTimeout(Duration value) {
            this.requestTimeout = value;
            return this;
        }

        public OmaConfig build() {
            return new OmaConfig(endpoint, sessionId, ingressToken, modelToken, workerEpoch, threadId,
                    heartbeatInterval, streamIdleTimeout, reconnectBudget, maxReconnectBackoff, requestTimeout);
        }
    }
}
