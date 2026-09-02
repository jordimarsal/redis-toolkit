package net.jordimp.redistoolkit.gateway.backend;

public final class StubBackend implements InferenceBackend {

    private static final int MAX_PROMPT_LENGTH = 480;

    @Override
    public Completion complete(CompletionRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isEmpty()) {
            return new Completion("stub-1", "Stub completion for empty prompt");
        }
        String sanitizedPrompt = sanitizePrompt(request.prompt());
        return new Completion("stub-1", "Stub completion for: \"" + sanitizedPrompt + "\"");
    }

    static String sanitizePrompt(String prompt) {
        if (prompt.length() > MAX_PROMPT_LENGTH) {
            prompt = prompt.substring(0, MAX_PROMPT_LENGTH);
        }
        // Escape order matters: '&' first so previously inserted entities are not double-escaped.
        return prompt.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
