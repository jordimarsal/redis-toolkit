package net.jordimp.redistoolkit.gateway.backend;

import java.util.regex.Pattern;

public final class StubBackend implements InferenceBackend {

    private static final Pattern HTML_SPECIAL_CHARS = Pattern.compile("[&<>\"']");
    private static final int MAX_PROMPT_LENGTH = 480;

    @Override
    public Completion complete(CompletionRequest request) {
        if (request == null || request.prompt() == null || request.prompt().isEmpty()) {
            return new Completion("stub-1", "Stub completion for empty prompt");
        }
        String sanitizedPrompt = sanitizePrompt(request.prompt());
        return new Completion("stub-1", "Stub completion for: \"" + sanitizedPrompt + "\"");
    }

    private static String sanitizePrompt(String prompt) {
        if (prompt.length() > MAX_PROMPT_LENGTH) {
            prompt = prompt.substring(0, MAX_PROMPT_LENGTH);
        }
        return HTML_SPECIAL_CHARS.matcher(prompt).replaceAll("\\$0");
    }
}
