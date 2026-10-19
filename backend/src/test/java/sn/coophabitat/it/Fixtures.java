package sn.coophabitat.it;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import sn.coophabitat.contribution.*;
import sn.coophabitat.contribution.ContributionDtos.PlanRequest;
import sn.coophabitat.member.*;
import sn.coophabitat.member.MemberDtos.CreateMemberRequest;
import sn.coophabitat.property.*;
import sn.coophabitat.security.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Création de données de test au travers des services métier réels. */
@Component
public class Fixtures {

    public static final String PASSWORD = "Mot-de-passe-de-test-2026";
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final AuthService auth;
    private final JwtService jwt;
    private final MemberService members;
    private final PlanService plans;
    private final PropertyService property;

    public Fixtures(UserAccountRepository users, PasswordEncoder encoder, AuthService auth, JwtService jwt,
                    MemberService members, PlanService plans, PropertyService property) {
        this.users = users;
        this.encoder = encoder;
        this.auth = auth;
        this.jwt = jwt;
        this.members = members;
        this.plans = plans;
        this.property = property;
    }

    public static int next() {
        return SEQ.incrementAndGet();
    }

    public UserAccount user(Long memberId, String... roles) {
        UserAccount u = new UserAccount();
        u.setEmail("u" + next() + "-" + System.nanoTime() + "@test.invalid");
        u.setDisplayName("Utilisateur de test " + String.join("/", roles));
        u.setRoles(new HashSet<>(Set.of(roles)));
        u.setMemberId(memberId);
        u.setPasswordHash(encoder.encode(PASSWORD));
        u.setEmailVerified(true);
        return users.save(u);
    }

    public CurrentUser current(UserAccount u) {
        return auth.toCurrentUser(users.findById(u.getId()).orElseThrow());
    }

    public String token(UserAccount u) {
        return jwt.issueAccessToken(users.findById(u.getId()).orElseThrow());
    }

    /** Place l'utilisateur dans le contexte de sécurité (appels directs aux services). */
    public CurrentUser actAs(UserAccount u) {
        CurrentUser cu = current(u);
        SecurityContextHolder.getContext().setAuthentication(new CoopAuthentication(cu, null));
        return cu;
    }

    public ContributionPlan activePlan(BigDecimal amount, LocalDate validFrom, int graceDays, PenaltyMethod method,
                                       BigDecimal penaltyValue) {
        actAs(user(null, "TRESORIER"));
        ContributionPlan p = plans.create(new PlanRequest("PLAN-" + next(), "Plan de test", null, amount,
                Periodicity.MENSUELLE, 5, validFrom, null, graceDays, method, penaltyValue, null, RoundingMode.HALF_UP,
                "Décision de test"));
        return plans.activate(p.getId());
    }

    /** Membre actif avec un compte MEMBRE ; renvoie le membre. */
    public Member activeMember(Long planId, LocalDate membershipDate) {
        UserAccount sec = user(null, "SECRETAIRE");
        CurrentUser cu = actAs(sec);
        var created = members.create(new CreateMemberRequest("Prénom" + next(), "Nom de test", null, null, null,
                "Ville", membershipDate, MemberType.ORDINAIRE, planId, null, false), cu);
        members.changeStatus(created.member().id(), new MemberDtos.StatusChangeRequest(MemberStatus.ACTIF,
                "Activation pour le test"), cu);
        return members.get(created.member().id());
    }

    public UserAccount memberAccount(Member m) {
        return user(m.getId(), "MEMBRE");
    }

    public PropertyLot availableLot() {
        actAs(user(null, "SECRETAIRE"));
        int n = next();
        PropertyProject project = property.createProject(new PropertyDtos.ProjectRequest("PRJ-" + n, "Programme de test",
                null, "Dakar", "Dakar", "Commune de test", PropertyProject.Status.EN_COURS));
        PropertyLot lot = property.createLot(new PropertyDtos.LotRequest("LOT-" + n, project.getId(), LotType.TERRAIN,
                "Dakar", "Dakar", "Commune de test", null, BigDecimal.valueOf(150), null, null, null, false, null));
        return property.changeStatus(lot.getId(), new PropertyDtos.LotStatusRequest(LotStatus.DISPONIBLE, "Ouverture test"));
    }
}
