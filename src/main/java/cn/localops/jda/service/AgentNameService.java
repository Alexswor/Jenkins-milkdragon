package cn.localops.jda.service;

import cn.localops.jda.config.EnvConfig;
import cn.localops.jda.model.HostSnapshot;

import java.util.Locale;
import java.util.UUID;

public final class AgentNameService {
    public String resolve(EnvConfig config, HostSnapshot host) {
        String existing = config.get("AGENT_NAME").orElse("");
        if (!existing.isBlank()) {
            return existing;
        }

        String instanceId = config.get("AGENT_INSTANCE_ID").orElseGet(() -> {
            String value = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            config.put("AGENT_INSTANCE_ID", value);
            return value;
        });
        String name = expectedPrefix(host) + instanceId;
        config.put("AGENT_NAME", name);
        return name;
    }

    public String expectedPrefix(HostSnapshot host) {
        String hostPart = host.hostName().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (hostPart.isBlank()) {
            hostPart = "windows";
        }
        return "agent-" + hostPart + "-" + host.architecture() + "-";
    }
}
