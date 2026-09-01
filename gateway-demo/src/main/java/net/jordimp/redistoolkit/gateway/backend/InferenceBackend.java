package net.jordimp.redistoolkit.gateway.backend;

public interface InferenceBackend {
    Completion complete(CompletionRequest request);
}
