package ai.oma.harness.core.claim;

public record ClaimedSession(
        String sessionId,
        long workerEpoch,
        String ingressToken,
        String modelToken,
        String claimExpiresAt,
        String traceParent) {
}
