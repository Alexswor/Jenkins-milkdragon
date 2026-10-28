package cn.localops.jda.model;

import java.util.Objects;

/**
 * Values written only to the selected production directory. This object deliberately
 * redacts secrets from its string representation so accidental logging is harmless.
 */
public final class ProductionRuntimeConfig {
    private final String databaseUrl;
    private final String databaseUsername;
    private final String databasePassword;
    private final String jwtSecret;

    public ProductionRuntimeConfig(
            String databaseUrl,
            String databaseUsername,
            String databasePassword,
            String jwtSecret) {
        this.databaseUrl = Objects.requireNonNullElse(databaseUrl, "");
        this.databaseUsername = Objects.requireNonNullElse(databaseUsername, "");
        this.databasePassword = Objects.requireNonNullElse(databasePassword, "");
        this.jwtSecret = Objects.requireNonNullElse(jwtSecret, "");
    }

    public String databaseUrl() {
        return databaseUrl;
    }

    public String databaseUsername() {
        return databaseUsername;
    }

    public String databasePassword() {
        return databasePassword;
    }

    public String jwtSecret() {
        return jwtSecret;
    }

    @Override
    public String toString() {
        return "ProductionRuntimeConfig[databaseUrl=<redacted>, databaseUsername=<redacted>, "
                + "databasePassword=<redacted>, jwtSecret=<redacted>]";
    }
}
