package net.jordimp.redistoolkit.gateway.backend;

public final class StubBackend implements InferenceBackend {

    @Override
    public Completion complete(CompletionRequest request) {
        String prompt = (request == null || request.prompt() == null) ? "" : request.prompt();
        return new Completion("stub-1", "Stub completion for: \"" + prompt + "\"");
    }
}
