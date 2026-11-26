package com.baotin.modelinference.api;

import com.baotin.modelinference.application.ModelRegistry;
import com.baotin.modelinference.domain.Prediction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1")
public class InferenceController {
    private final ModelRegistry models;
    public InferenceController(ModelRegistry models) { this.models = models; }
    public record TextInput(@NotBlank @Size(max = 10000) String content) {}
    public record UrlInput(@NotBlank @Size(max = 4096) String url) {}
    @PostMapping({"/inference/text", "/inference/sms"})
    public Prediction text(@Valid @RequestBody TextInput request) { return models.infer("text", request.content()); }
    @PostMapping("/inference/url")
    public Prediction url(@Valid @RequestBody UrlInput request) { return models.infer("url", request.url()); }
    @GetMapping("/models")
    public Map<String, Object> models() { return models.describe(); }
}
