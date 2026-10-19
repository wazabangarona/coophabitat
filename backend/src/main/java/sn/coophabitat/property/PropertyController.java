package sn.coophabitat.property;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sn.coophabitat.common.PageResponse;
import sn.coophabitat.property.PropertyDtos.*;
import sn.coophabitat.security.CurrentUserProvider;
import sn.coophabitat.security.Permission;

import java.util.List;

@RestController
@RequestMapping("/api/v1/property")
@Tag(name = "Terrains et logements")
public class PropertyController {

    private final PropertyService service;

    public PropertyController(PropertyService service) {
        this.service = service;
    }

    @GetMapping("/projects")
    public List<ProjectResponse> projects() {
        return service.listProjects().stream().map(p -> ProjectResponse.of(p, service.lotCount(p.getId()))).toList();
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('LOT_WRITE')")
    public ProjectResponse createProject(@Valid @RequestBody ProjectRequest body) {
        return ProjectResponse.of(service.createProject(body), 0);
    }

    @PutMapping("/projects/{id}")
    @PreAuthorize("hasAuthority('LOT_WRITE')")
    public ProjectResponse updateProject(@PathVariable Long id, @Valid @RequestBody ProjectRequest body) {
        return ProjectResponse.of(service.updateProject(id, body), service.lotCount(id));
    }

    @GetMapping("/lots")
    @Operation(summary = "Catalogue des lots (vue complète pour le bureau, vue restreinte pour les membres)")
    public PageResponse<?> lots(@RequestParam(required = false) Long projectId,
                                @RequestParam(required = false) LotStatus status,
                                @RequestParam(required = false) LotType type,
                                @RequestParam(required = false) String q,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        var result = service.search(projectId, status, type, q, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        if (CurrentUserProvider.require().has(Permission.LOT_READ_ALL)) {
            return PageResponse.of(result, service::toResponse);
        }
        return PageResponse.of(result, service::toPublicView);
    }

    @GetMapping("/lots/{id}")
    public Object lot(@PathVariable Long id) {
        PropertyLot l = service.get(id);
        return CurrentUserProvider.require().has(Permission.LOT_READ_ALL) ? service.toResponse(l) : service.toPublicView(l);
    }

    @PostMapping("/lots")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('LOT_WRITE')")
    public LotResponse createLot(@Valid @RequestBody LotRequest body) {
        return service.toResponse(service.createLot(body));
    }

    @PutMapping("/lots/{id}")
    @PreAuthorize("hasAuthority('LOT_WRITE')")
    public LotResponse updateLot(@PathVariable Long id, @Valid @RequestBody LotRequest body) {
        return service.toResponse(service.updateLot(id, body));
    }

    @PostMapping("/lots/{id}/status")
    @PreAuthorize("hasAuthority('LOT_STATUS_CHANGE')")
    @Operation(summary = "Changement manuel d'état (RESERVE et ATTRIBUE exclus : procédure d'attribution obligatoire)")
    public LotResponse changeStatus(@PathVariable Long id, @Valid @RequestBody LotStatusRequest body) {
        return service.toResponse(service.changeStatus(id, body));
    }

    @GetMapping("/lots/{id}/history")
    @PreAuthorize("hasAuthority('LOT_READ_ALL')")
    public List<LotHistoryItem> history(@PathVariable Long id) {
        return service.history(id).stream().map(LotHistoryItem::of).toList();
    }
}
