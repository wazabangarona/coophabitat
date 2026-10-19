package sn.coophabitat.assembly;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sn.coophabitat.assembly.AssemblyDtos.*;
import sn.coophabitat.security.CurrentUser;
import sn.coophabitat.security.CurrentUserProvider;
import sn.coophabitat.security.Permission;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/assemblies")
@Tag(name = "Assemblées générales")
public class AssemblyController {

    private final AssemblyService service;

    public AssemblyController(AssemblyService service) {
        this.service = service;
    }

    private static boolean bureau() {
        return CurrentUserProvider.require().has(Permission.ASSEMBLY_MANAGE);
    }

    @GetMapping
    public List<AssemblyResponse> list() {
        boolean b = bureau();
        return service.list(b).stream().map(a -> service.toResponse(a, b)).toList();
    }

    @GetMapping("/{id}")
    public AssemblyResponse get(@PathVariable Long id) {
        boolean b = bureau();
        return service.toResponse(service.get(id, b), b);
    }

    @GetMapping("/{id}/minutes")
    public List<MinutesResponse> minutes(@PathVariable Long id) {
        boolean b = bureau();
        service.get(id, b);
        return service.minutesOf(id, b);
    }

    @GetMapping("/{id}/my-attendance")
    public Map<String, Object> myAttendance(@PathVariable Long id) {
        service.get(id, false);
        return service.myAttendance(id, CurrentUserProvider.requireMemberId())
                .<Map<String, Object>>map(a -> Map.of("status", a.getStatus().name(), "recordedAt", a.getRecordedAt()))
                .orElse(Map.of("status", "NON_RENSEIGNE"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ASSEMBLY_MANAGE')")
    public AssemblyResponse create(@Valid @RequestBody AssemblyRequest body) {
        return service.toResponse(service.create(body, CurrentUserProvider.require()), true);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ASSEMBLY_MANAGE')")
    public AssemblyResponse update(@PathVariable Long id, @Valid @RequestBody AssemblyRequest body) {
        return service.toResponse(service.update(id, body), true);
    }

    @PutMapping("/{id}/agenda")
    @PreAuthorize("hasAuthority('ASSEMBLY_MANAGE')")
    public AssemblyResponse agenda(@PathVariable Long id, @Valid @RequestBody AgendaRequest body) {
        service.setAgenda(id, body.items());
        return service.toResponse(service.get(id, true), true);
    }

    @PostMapping("/{id}/convene")
    @PreAuthorize("hasAuthority('ASSEMBLY_MANAGE')")
    public AssemblyResponse convene(@PathVariable Long id) {
        return service.toResponse(service.convene(id), true);
    }

    @GetMapping("/{id}/attendance")
    @PreAuthorize("hasAuthority('ASSEMBLY_MANAGE')")
    public List<AttendanceResponse> attendance(@PathVariable Long id) {
        return service.attendance(id);
    }

    @PutMapping("/{id}/attendance")
    @PreAuthorize("hasAuthority('ASSEMBLY_MANAGE')")
    public List<AttendanceResponse> recordAttendance(@PathVariable Long id, @Valid @RequestBody AttendanceRequest body) {
        service.recordAttendance(id, body.entries(), CurrentUserProvider.require());
        return service.attendance(id);
    }

    @PostMapping("/{id}/held")
    @PreAuthorize("hasAuthority('ASSEMBLY_MANAGE')")
    public AssemblyResponse held(@PathVariable Long id) {
        return service.toResponse(service.markHeld(id), true);
    }

    @PostMapping("/{id}/resolutions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ASSEMBLY_MANAGE')")
    public AssemblyResponse resolution(@PathVariable Long id, @Valid @RequestBody ResolutionRequest body) {
        service.recordResolution(id, body, CurrentUserProvider.require());
        return service.toResponse(service.get(id, true), true);
    }

    @PostMapping("/{id}/minutes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ASSEMBLY_MANAGE')")
    public List<MinutesResponse> draftMinutes(@PathVariable Long id, @Valid @RequestBody MinutesRequest body) {
        service.draftMinutes(id, body.content(), CurrentUserProvider.require());
        return service.minutesOf(id, true);
    }

    @PostMapping("/{id}/minutes/{minutesId}/publish")
    @PreAuthorize("hasAuthority('ASSEMBLY_MANAGE')")
    public List<MinutesResponse> publishMinutes(@PathVariable Long id, @PathVariable Long minutesId) {
        service.publishMinutes(id, minutesId);
        return service.minutesOf(id, true);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('ASSEMBLY_MANAGE')")
    public AssemblyResponse close(@PathVariable Long id) {
        return service.toResponse(service.close(id), true);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('ASSEMBLY_MANAGE')")
    public AssemblyResponse cancel(@PathVariable Long id, @Valid @RequestBody CancelRequest body) {
        return service.toResponse(service.cancel(id, body.reason()), true);
    }
}
