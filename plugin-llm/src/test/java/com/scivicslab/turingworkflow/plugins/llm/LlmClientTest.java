package com.scivicslab.turingworkflow.plugins.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the JSON-parsing helpers of {@link LlmClient}, the plain object the actor wraps.
 */
class LlmClientTest {

    @Test
    void extractJsonStringField_japaneseValueExtracted() {
        String json = "{\"agent\":\"chat-ui-28010\",\"prompt\":\"SPIFFEは有効？\",\"caller\":\"test\"}";
        assertThat(LlmClient.extractJsonStringField(json, "prompt")).isEqualTo("SPIFFEは有効？");
    }

    @Test
    void extractJsonStringField_unicodeEscapeInPromptDecoded() {
        String json = "{\"prompt\":\"\\u30c6\\u30b9\\u30c8\"}";
        assertThat(LlmClient.extractJsonStringField(json, "prompt")).isEqualTo("テスト");
    }

    @Test
    void extractJsonStringField_newlinesInPromptDecoded() {
        String json = "{\"prompt\":\"line1\\nline2\"}";
        assertThat(LlmClient.extractJsonStringField(json, "prompt")).isEqualTo("line1\nline2");
    }
}
