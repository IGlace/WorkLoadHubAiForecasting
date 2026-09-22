package com.workloadhub.forecastweb.api;

import com.workloadhub.forecast.api.BacktestScore;
import com.workloadhub.forecast.api.MemberDayForecast;
import com.workloadhub.forecast.api.MemberWindowForecast;
import com.workloadhub.forecast.api.NarrativeStatus;
import com.workloadhub.forecast.api.RunSummary;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** The response bodies this host adds to the module's own records: names joined, JSON strings parsed. */
public final class Views {

    private Views() {
    }

    public record SystemView(LocalDate today, boolean clockPinned, boolean clockAdjustable, int windows, double defaultWeeklyHours, int runThreads,
            String actingUserHeader, UUID bootstrapUserId) {
    }

    public record ClockBody(LocalDate today) {
    }

    public record TeamRelation(UUID teamId, String name, String relation) {
    }

    public record UserView(UUID id, String fullName, String role, String jobTitle, String department, boolean active, List<TeamRelation> teams) {
    }

    /** {@code reportsToId} is the leader's own manager — a user, who usually keys no team (see Directory.Team). */
    public record TeamView(UUID id, String name, UUID managerId, String managerName, UUID reportsToId, String reportsToName, int memberCount) {
    }

    public record MemberView(UUID id, String fullName, String role, String jobTitle) {
    }

    public record TeamDetailView(UUID id, String name, UUID managerId, String managerName, UUID reportsToId, String reportsToName,
            List<MemberView> members) {
    }

    public record TeamPermission(UUID teamId, String name, boolean canRun, boolean canView, String runReason, String viewReason) {
    }

    public record MeView(MemberView user, boolean hasToken, List<TeamPermission> teams) {
    }

    public record PermissionView(UUID userId, String role, UUID teamId, boolean canRun, boolean canView, String runReason, String viewReason) {
    }

    public record StartedRun(UUID id) {
    }

    public record RunView(RunSummary run, List<BacktestScore> scores, List<MemberWindowForecast> memberWindows, List<MemberDayForecast> memberDays,
            JsonNode facts, List<MemberView> members) {
    }

    public record NarrateBody(String language, String model) {
    }

    public record NarrationStarted(UUID runId, String language, String phase) {
    }

    public record NarrativeView(UUID id, UUID runId, String language, NarrativeStatus status, String model, JsonNode narrative, String rawText,
            JsonNode verification, JsonNode usage, String error, int attempts, int toolCalls, LocalDateTime createdAt) {
    }

    public record TokenBody(String token) {
    }

    public record TokenState(boolean hasToken) {
    }
}
