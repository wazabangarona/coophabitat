package sn.coophabitat.dashboard;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.coophabitat.security.CurrentUserProvider;

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Tableaux de bord")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/bureau")
    @PreAuthorize("hasAuthority('DASHBOARD_BUREAU')")
    public DashboardDtos.Dashboard bureau() {
        return service.bureau(CurrentUserProvider.require());
    }
}
