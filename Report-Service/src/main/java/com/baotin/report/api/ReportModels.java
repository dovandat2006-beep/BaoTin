package com.baotin.report.api;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import com.baotin.report.domain.ReportStatus;

public final class ReportModels {
    private ReportModels() {}
    public record Create(
        @NotNull @Pattern(regexp="URL|MESSAGE_TEMPLATE") String targetType,
        @NotBlank @Size(max=10000) String targetValue,
        @NotNull @Pattern(regexp="SUSPICIOUS_MESSAGE|PHISHING_URL|SPAM|OTHER") String category,
        @NotBlank @Size(min=6,max=200) String title,
        @NotBlank @Size(min=16,max=10000) String description,
        @PastOrPresent Instant occurredAt, UUID scanId) {}
    public record Decision(@NotNull @Min(0) Long version, @NotBlank @Size(max=2000) String note) {}
    public record Supplement(@NotNull @Min(0) Long version, @NotBlank @Size(max=2000) String note) {}
    public record Evidence(UUID id, String originalFilename, String mimeType, long sizeBytes, String sha256, Instant createdAt) {}
    public record History(String fromStatus, String toStatus, UUID changedByUserId, String note, Instant createdAt) {}
    public record Report(UUID id, UUID reporterUserId, UUID threatTargetId, String targetTypeSnapshot,
        String targetDisplaySnapshot, String category, String title, String description, Instant occurredAt,
        ReportStatus status, String priority, String moderatorNote, Instant createdAt, Instant updatedAt,
        long version, List<Evidence> evidence, List<History> history, UUID scanId) {}
    public record Page<T>(List<T> items, int page, int size, long totalItems, long totalPages) {}
}
