package sn.coophabitat.member;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.coophabitat.audit.AuditCategory;
import sn.coophabitat.audit.AuditService;
import sn.coophabitat.common.BusinessException;
import sn.coophabitat.member.MemberDtos.CreateMemberRequest;
import sn.coophabitat.member.MemberDtos.MemberResponse;
import sn.coophabitat.member.MemberDtos.CreatedMember;
import sn.coophabitat.security.PasswordPolicy;
import sn.coophabitat.security.RoleCode;
import sn.coophabitat.security.UserAccount;
import sn.coophabitat.security.UserAccountRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Demande d'adhésion en ligne : la personne crée elle-même son compte, rattaché à un dossier de membre
 * « en attente ». Elle peut se connecter tout de suite, mais tant que le bureau n'a pas activé son adhésion
 * (changement de statut tracé), les règles existantes lui interdisent de payer ou de candidater.
 * <p>
 * Ouverte uniquement si COOPHABITAT_PUBLIC_REGISTRATION=true. Pour ne pas révéler quelles adresses sont déjà
 * inscrites, une adresse ou un téléphone déjà utilisés reçoivent la même réponse qu'une demande acceptée.
 * </p>
 */
@Service
public class RegistrationService {

    public record RegistrationRequest(@NotBlank @Size(max = 100) String firstName,
                                      @NotBlank @Size(max = 100) String lastName,
                                      @NotBlank @Email @Size(max = 200) String email,
                                      @Pattern(regexp = MemberDtos.PHONE_PATTERN, message = "Numéro de téléphone invalide") String phone,
                                      @Size(max = 100) String city,
                                      @NotBlank @Size(max = PasswordPolicy.MAX_LENGTH) String password,
                                      @AssertTrue(message = "L'acceptation est nécessaire pour déposer la demande") boolean consent) {
    }

    private final boolean enabled;
    private final MemberService members;
    private final MemberRepository memberRepository;
    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService audit;
    private final Clock clock;

    public RegistrationService(@Value("${coophabitat.registration.enabled:false}") boolean enabled, MemberService members,
                               MemberRepository memberRepository, UserAccountRepository users, PasswordEncoder encoder,
                               PasswordPolicy passwordPolicy, AuditService audit, Clock clock) {
        this.enabled = enabled;
        this.members = members;
        this.memberRepository = memberRepository;
        this.users = users;
        this.encoder = encoder;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    public boolean enabled() {
        return enabled;
    }

    @Transactional
    public void register(RegistrationRequest r) {
        if (!enabled) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "INSCRIPTION_FERMEE",
                    "Les demandes d'adhésion en ligne ne sont pas ouvertes. Adressez-vous au bureau de la coopérative.");
        }
        String email = r.email().trim().toLowerCase(Locale.ROOT);
        passwordPolicy.validate(r.password(), email);
        String phone = MemberService.normalizePhone(r.phone());
        boolean duplicate = users.findByEmailIgnoreCase(email).isPresent()
                || memberRepository.findByEmailIgnoreCase(email).isPresent()
                || (phone != null && memberRepository.findByPhone(phone).isPresent());
        if (duplicate) {
            audit.recordAs(AuditCategory.SECURITE, "INSCRIPTION_EN_DOUBLE", "UserAccount", null, Map.of(), null, email);
            return;
        }
        CreatedMember created = members.create(new CreateMemberRequest(r.firstName(), r.lastName(), email, phone, null,
                r.city(), LocalDate.now(clock), MemberType.ORDINAIRE, null, "Demande d'adhésion déposée en ligne", false), null);
        MemberResponse m = created.member();
        UserAccount u = new UserAccount();
        u.setEmail(email);
        u.setDisplayName(m.firstName() + " " + m.lastName());
        u.setMemberId(m.id());
        u.setRoles(Set.of(RoleCode.MEMBRE.name()));
        u.setPasswordHash(encoder.encode(r.password()));
        u.setPasswordChangedAt(Instant.now(clock));
        u = users.save(u);
        audit.recordAs(AuditCategory.SECURITE, "INSCRIPTION_EN_LIGNE", "UserAccount", u.getId(),
                Map.of("membre", m.memberNumber()), u.getId(), email);
    }
}
