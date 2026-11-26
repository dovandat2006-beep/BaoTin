package com.baotin.modelinference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "model.service-token=TEST-ONLY-service-credential-0123456789")
@AutoConfigureMockMvc
class ModelInferenceServiceApplicationTests {
    @Autowired MockMvc mvc;
    @Test void missingModelsStayLiveButAreNeverReadyOrFabricated() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/internal/v1/models").header("Authorization", "Bearer TEST-ONLY-service-credential-0123456789"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text.status").value("MISSING"));
        mvc.perform(post("/internal/v1/inference/text").header("Authorization", "Bearer TEST-ONLY-service-credential-0123456789")
                .contentType("application/json").content("{\"content\":\"Xin chào\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"));
    }
}
