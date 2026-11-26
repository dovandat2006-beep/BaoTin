package com.baotin.modelinference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"model.text-path=src/test/resources/fixtures/text", "model.url-path=src/test/resources/fixtures/url", "model.service-token=TEST-ONLY-service-credential-0123456789"})
@AutoConfigureMockMvc
class InferenceApiTests {
    private static final String TOKEN = "Bearer TEST-ONLY-service-credential-0123456789";
    @Autowired MockMvc mvc;

    @Test void requiresSeparateServiceCredential() throws Exception {
        mvc.perform(get("/internal/v1/models")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/models").header("Authorization", "Bearer not-a-service-token")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/models").header("X-User-Id", "admin")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/inference/text").header("Authorization", TOKEN)).andExpect(status().isUnauthorized());
    }
    @Test void servesVersionedSignalsAndSmsAlias() throws Exception {
        for (String route : new String[]{"text", "sms"}) mvc.perform(post("/internal/v1/inference/" + route).header("Authorization", TOKEN)
                .header("X-Correlation-Id", "integration-123").contentType("application/json").content("{\"content\":\"Xin chào bạn\"}"))
                .andExpect(status().isOk()).andExpect(header().string("X-Correlation-Id", "integration-123"))
                .andExpect(jsonPath("$.modelVersion").value("TEST-FIXTURE")).andExpect(jsonPath("$.probabilities.HAM").isNumber());
        mvc.perform(post("/internal/v1/inference/url").header("Authorization", TOKEN).contentType("application/json").content("{\"url\":\"https://example.test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.probabilities.phishing_gov").isNumber());
    }
    @Test void rejectsInvalidAndOversizedInputsWithoutReflectingContent() throws Exception {
        for (String body : new String[]{"{}", "{\"content\":\" \"}", "{\"content\":\"valid\",\"userId\":\"admin\"}", "not-json"}) {
            mvc.perform(post("/internal/v1/inference/text").header("Authorization", TOKEN).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        mvc.perform(post("/internal/v1/inference/text").header("Authorization", TOKEN).contentType("application/json").content("x".repeat(65537)))
                .andExpect(status().isPayloadTooLarge());
    }
    @Test void readinessOnlyBecomesUpAfterBothModelsPassParity() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/internal/v1/models").header("Authorization", TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.text.status").value("READY")).andExpect(jsonPath("$.url.status").value("READY"));
    }
}
