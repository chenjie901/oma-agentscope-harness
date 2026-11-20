package ai.oma.harness.core.transport;

public class OmaHttpException extends RuntimeException {
    private final int status;
    private final String code;

    public OmaHttpException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean epochTerminal() {
        return status == 401 || status == 403 || status == 404 || status == 409 || status == 410;
    }

    public boolean retryable() {
        return status >= 500 || status == 429;
    }
}
