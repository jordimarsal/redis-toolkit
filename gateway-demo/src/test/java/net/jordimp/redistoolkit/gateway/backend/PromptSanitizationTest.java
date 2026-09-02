package net.jordimp.redistoolkit.gateway.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PromptSanitizationTest {

    private final StubBackend backend = new StubBackend();

    @Test
    void sanitizePrompt_escapesHtmlSpecialCharacters() {
        assertThat(StubBackend.sanitizePrompt("<script>alert(\"x\")</script> & 'ok'"))
                .isEqualTo("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt; &amp; &#39;ok&#39;");
    }

    @Test
    void sanitizePrompt_escapesAmpersandFirst_soIntroducedEntitiesStayIntact() {
        // If '&' were escaped after '<', the result would be "&amp;lt;" instead of "&lt;".
        assertThat(StubBackend.sanitizePrompt("<")).isEqualTo("&lt;");
        assertThat(StubBackend.sanitizePrompt("a & b")).isEqualTo("a &amp; b");
    }

    @Test
    void sanitizePrompt_truncatesToMaxLength() {
        String longPrompt = "a".repeat(1_000);
        assertThat(StubBackend.sanitizePrompt(longPrompt)).isEqualTo("a".repeat(480));
    }

    @Test
    void complete_embedsEscapedPromptInCompletionText() {
        Completion completion = backend.complete(new CompletionRequest("stub", "<b>bold</b>"));
        assertThat(completion.text()).contains("&lt;b&gt;bold&lt;/b&gt;").doesNotContain("<b>");
    }

    @Test
    void complete_emptyOrMissingPrompt_returnsPlaceholder() {
        assertThat(backend.complete(new CompletionRequest("stub", "")).text())
                .isEqualTo("Stub completion for empty prompt");
        assertThat(backend.complete(null).text()).isEqualTo("Stub completion for empty prompt");
    }
}
