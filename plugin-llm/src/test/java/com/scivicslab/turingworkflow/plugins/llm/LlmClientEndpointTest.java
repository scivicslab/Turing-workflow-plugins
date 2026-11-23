package com.scivicslab.turingworkflow.plugins.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * The endpoint is an http or https URL and nothing else (HIGH 11, scope set by chat-98: the text the workflow gives
 * {@code setOpenAiUrl} becomes an argument of curl).
 *
 * <p>curl speaks many protocols. With {@code file://} a POST writes the request body into the named file; a text
 * that begins with {@code -} is read as an option. A workflow that took its URL from a document or a model's answer
 * could overwrite a file of the user running it.</p>
 */
@Tag("LlmEndpointScheme_261123_oo01")
@DisplayName("LlmClient.setOpenAiUrl - only an http or https URL is accepted")
class LlmClientEndpointTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a file:// URL is refused and a call does not write the file")
    void fileUrl_isRefused_andTheFileIsNotWritten() throws Exception {
        Path target = dir.resolve("victim.txt");
        Files.writeString(target, "precious", StandardCharsets.UTF_8);
        LlmClient client = new LlmClient();

        ActionResult set = client.setOpenAiUrl(target.toUri().toString(), "m");
        ActionResult call = client.callOpenAi("hello");

        assertThat(set.isSuccess()).as(set.getResult()).isFalse();
        assertThat(call.isSuccess()).as(call.getResult()).isFalse();
        assertThat(Files.readString(target)).isEqualTo("precious");
    }

    @Test
    @DisplayName("the one-argument form and the JSON array form refuse the same URLs")
    void otherForms_refuseToo() {
        LlmClient client = new LlmClient();

        assertThat(client.setOpenAiUrl("file:///etc/passwd").isSuccess()).isFalse();
        assertThat(client.setOpenAiUrl("[\"gopher://127.0.0.1:70/x\", \"m\"]").isSuccess()).isFalse();
        assertThat(client.setOpenAiUrl("--output /tmp/x").isSuccess()).isFalse();
    }

    @Test
    @DisplayName("an http and an https URL are accepted")
    void httpAndHttps_areAccepted() {
        LlmClient client = new LlmClient();

        assertThat(client.setOpenAiUrl("http://127.0.0.1:8000/v1/chat/completions", "m").isSuccess()).isTrue();
        assertThat(client.setOpenAiUrl("https://llm.example.org/v1/chat/completions", "m").isSuccess()).isTrue();
        assertThat(client.setOpenAiUrl("[\"http://h:8000/v1/chat/completions\", \"m\"]").isSuccess()).isTrue();
    }
}
