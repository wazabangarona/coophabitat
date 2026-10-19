package sn.coophabitat.allocation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Entity
@Table(name = "allocation_commission_member")
@IdClass(CommissionMember.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class CommissionMember {

    @Id
    private Long campaignId;
    @Id
    private Long userId;
    private String roleInCommission;
    private boolean conflictDeclared;
    private String conflictDetails;

    public record Key(Long campaignId, Long userId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }
}
