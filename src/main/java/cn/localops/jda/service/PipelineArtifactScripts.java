package cn.localops.jda.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;

final class PipelineArtifactScripts {
    private PipelineArtifactScripts() {
    }

    static String base64() {
        try (var input = PipelineArtifactScripts.class.getResourceAsStream("/pipeline/artifacts.ps1")) {
            if (input == null) {
                throw new IllegalStateException("Pipeline artifact helpers are missing.");
            }
            return Base64.getEncoder().encodeToString(input.readAllBytes());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
