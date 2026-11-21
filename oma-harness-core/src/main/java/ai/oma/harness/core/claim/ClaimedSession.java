package ai.oma.harness.core.claim;

/**
 * worker 成功 claim 一个会话后拿到的凭据：session id、worker 代次、各类 token、租约到期时间与 trace 头。
 * 由 {@link Claims} 解析服务端响应得到，用于构造 {@link OmaConfig}。接入方无需直接处理。
 */
public record ClaimedSession(
        String sessionId,
        long workerEpoch,
        String ingressToken,
        String modelToken,
        String claimExpiresAt,
        String traceParent) {
}
