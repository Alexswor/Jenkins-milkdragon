package cn.localops.jda.model;

import java.util.Objects;
import java.util.List;
import java.net.URI;

public record ManagedRuntime(
        Kind kind,
        String id,
        String displayName,
        State state,
        boolean autoStart,
        String detail,
        List<String> addresses) {
    public ManagedRuntime(Kind kind, String id, String displayName, State state, boolean autoStart, String detail) {
        this(kind, id, displayName, state, autoStart, detail, List.of());
    }

    public ManagedRuntime {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        id = Objects.requireNonNull(id, "id").strip();
        displayName = displayName == null || displayName.isBlank() ? id : displayName.strip();
        detail = detail == null ? "" : detail.strip();
        addresses = addresses == null ? List.of() : addresses.stream().filter(ManagedRuntime::safeAddress).distinct().toList();
        if (id.isBlank()) {
            throw new IllegalArgumentException("运行项 ID 不能为空。");
        }
    }

    private static boolean safeAddress(String value) {
        if (value == null || value.length() > 2048) { return false; }
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getRawQuery() == null
                    && uri.getFragment() == null && uri.getPort() != 0 && uri.getPort() <= 65535;
        } catch (IllegalArgumentException invalid) { return false; }
    }

    public enum Kind {
        BACKEND_SERVICE,
        IIS_SITE
    }

    public enum State {
        RUNNING,
        STOPPED,
        UNKNOWN
    }
}
