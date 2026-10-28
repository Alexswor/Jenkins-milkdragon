package cn.localops.jda.service;

public final class GerritException extends RuntimeException {
    public GerritException(String message) {
        super(message);
    }

    public GerritException(String message, Throwable cause) {
        super(message, cause);
    }
}
