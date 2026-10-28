package cn.localops.jda.service;

public final class JenkinsException extends RuntimeException {
    private final int statusCode;
    private final String diagnosticDetail;

    public JenkinsException(String message) {
        this(message, null, -1, "");
    }

    public JenkinsException(String message, Throwable cause) {
        this(message, cause, -1, "");
    }

    public JenkinsException(String message, int statusCode) {
        this(message, null, statusCode, "");
    }

    public JenkinsException(String message, int statusCode, String diagnosticDetail) {
        this(message, null, statusCode, diagnosticDetail);
    }

    private JenkinsException(String message, Throwable cause, int statusCode, String diagnosticDetail) {
        super(message, cause);
        this.statusCode = statusCode;
        this.diagnosticDetail = diagnosticDetail == null ? "" : diagnosticDetail;
    }

    public int statusCode() {
        return statusCode;
    }

    public String diagnosticDetail() {
        return diagnosticDetail;
    }
}
