package sn.coophabitat.admin;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sn.coophabitat.admin.AdminDtos.*;
import sn.coophabitat.common.PageResponse;
import sn.coophabitat.security.CurrentUserProvider;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Administration")
public class AdminController {

    private final AdminService service;

    public AdminController(AdminService service) {
        this.service = service;
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('USER_ADMIN')")
    public PageResponse<UserResponse> users(@RequestParam(required = false) String q,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(service.searchUsers(q, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))),
                service::toResponse);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('USER_ADMIN')")
    public UserResponse createUser(@Valid @RequestBody UserCreateRequest body) {
        return service.toResponse(service.createUser(body, CurrentUserProvider.require()));
    }

    @PutMapping("/users/{id}/roles")
    @PreAuthorize("hasAuthority('USER_ADMIN')")
    public UserResponse roles(@PathVariable Long id, @Valid @RequestBody RolesRequest body) {
        return service.toResponse(service.setRoles(id, body.roles(), CurrentUserProvider.require()));
    }

    @PutMapping("/users/{id}/enabled")
    @PreAuthorize("hasAuthority('USER_ADMIN')")
    public UserResponse enabled(@PathVariable Long id, @Valid @RequestBody EnabledRequest body) {
        return service.toResponse(service.setEnabled(id, body.enabled(), body.reason(), CurrentUserProvider.require()));
    }

    @PostMapping("/users/{id}/invitation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('USER_ADMIN')")
    public void resendInvitation(@PathVariable Long id) {
        service.resendInvitation(id);
    }

    @PostMapping("/users/{id}/unlock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('USER_ADMIN')")
    public void unlock(@PathVariable Long id) {
        service.unlock(id);
    }

    @GetMapping("/bureau-setup")
    @PreAuthorize("hasAuthority('USER_ADMIN')")
    public BureauSetup bureauSetup() {
        return service.bureauSetup();
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('USER_ADMIN')")
    public List<RoleResponse> roles() {
        return service.roles();
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('SETTINGS_ADMIN') or hasAuthority('DASHBOARD_BUREAU')")
    public CoopSettings settings() {
        return service.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('SETTINGS_ADMIN')")
    public CoopSettings updateSettings(@Valid @RequestBody SettingsRequest body) {
        return service.updateSettings(body);
    }

    @GetMapping("/retention-policies")
    @PreAuthorize("hasAuthority('SETTINGS_ADMIN') or hasAuthority('DATA_REQUEST_HANDLE')")
    public List<DataRetentionPolicy> retention() {
        return service.retentionPolicies();
    }

    @GetMapping("/data-requests")
    @PreAuthorize("hasAuthority('DATA_REQUEST_HANDLE')")
    public List<DataRequestResponse> dataRequests() {
        return service.dataRequests(null);
    }

    @PostMapping("/data-requests/{id}/decision")
    @PreAuthorize("hasAuthority('DATA_REQUEST_HANDLE')")
    public List<DataRequestResponse> decide(@PathVariable Long id, @Valid @RequestBody DataRequestDecision body) {
        service.decideDataRequest(id, body, CurrentUserProvider.require());
        return service.dataRequests(null);
    }
}
