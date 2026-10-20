package sn.coophabitat.member;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import sn.coophabitat.member.RegistrationService.RegistrationRequest;

/** Demande d'adhésion déposée en ligne par la personne elle-même (route publique, limitée par adresse IP). */
@RestController
@RequestMapping("/api/v1/public/registration")
@Tag(name = "Inscription")
public class RegistrationController {

    private final RegistrationService service;

    public RegistrationController(RegistrationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Crée un compte membre en attente de validation par le bureau")
    public void register(@Valid @RequestBody RegistrationRequest body) {
        service.register(body);
    }
}
