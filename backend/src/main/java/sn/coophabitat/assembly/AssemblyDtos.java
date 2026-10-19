package sn.coophabitat.assembly;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

public final class AssemblyDtos {

    private AssemblyDtos() {
    }

    public record AssemblyRequest(@NotBlank @Size(max = 300) String title, @NotNull GeneralAssembly.Type type,
                                  @NotNull Instant scheduledAt, @Size(max = 300) String location,
                                  @NotNull GeneralAssembly.Modality modality, @Size(max = 500) String quorumRule,
                                  @Size(max = 8000) String convocationText) {
    }

    public record AgendaRequest(@NotNull @Valid List<AgendaEntry> items) {
    }

    public record AgendaEntry(@NotBlank @Size(max = 300) String title, @Size(max = 4000) String description) {
    }

    public record AttendanceRequest(@NotNull @Valid List<AttendanceEntry> entries) {
    }

    public record AttendanceEntry(@NotNull Long memberId, @NotNull Attendance.Status status, Long proxyMemberId) {
    }

    public record ResolutionRequest(Long agendaItemId, @NotBlank @Size(max = 20) String number,
                                    @NotBlank @Size(max = 8000) String text, @PositiveOrZero Integer votesFor,
                                    @PositiveOrZero Integer votesAgainst, @PositiveOrZero Integer abstentions,
                                    @NotNull Resolution.Outcome outcome) {
    }

    public record MinutesRequest(@NotBlank @Size(min = 50, max = 200000) String content) {
    }

    public record CancelRequest(@NotBlank @Size(min = 10, max = 1000) String reason) {
    }

    public record AssemblyResponse(Long id, String reference, String title, GeneralAssembly.Type type,
                                   Instant scheduledAt, String location, GeneralAssembly.Modality modality,
                                   GeneralAssembly.Status status, String quorumRule, String convocationText,
                                   Instant convenedAt, Instant heldAt, String cancelReason,
                                   List<AgendaItemResponse> agenda, List<ResolutionResponse> resolutions,
                                   Long convocationDocumentId, Long publishedMinutesDocumentId,
                                   QuorumInfo quorum, boolean demo, long version) {
    }

    public record AgendaItemResponse(Long id, int position, String title, String description) {
    }

    public record ResolutionResponse(Long id, Long agendaItemId, String number, String text, Integer votesFor,
                                     Integer votesAgainst, Integer abstentions, Resolution.Outcome outcome,
                                     Instant recordedAt) {
    }

    /** Indicateur de présence : la règle de quorum statutaire reste celle saisie en texte et appréciée par le bureau. */
    public record QuorumInfo(long activeMembers, long present, long represented, long absent, long excused,
                             Integer participationPercent) {
    }

    public record AttendanceResponse(Long memberId, String memberNumber, String memberName, Attendance.Status status,
                                     Long proxyMemberId, String proxyMemberNumber, Instant recordedAt) {
    }

    public record MinutesResponse(Long id, int versionNo, AssemblyMinutes.Status status, String content,
                                  Instant createdAt, Instant publishedAt, Long documentId) {
    }
}
