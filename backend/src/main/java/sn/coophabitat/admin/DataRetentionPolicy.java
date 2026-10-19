package sn.coophabitat.admin;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "data_retention_policy")
@Getter
@Setter
@NoArgsConstructor
public class DataRetentionPolicy {

    @Id
    private String dataCategory;
    private int retentionMonths;
    private String legalBasis;
    private String description;
}
