package sn.coophabitat.assembly;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;

@Entity
@Table(name = "attendance")
@IdClass(Attendance.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class Attendance {

    public enum Status { PRESENT, REPRESENTE, ABSENT, EXCUSE }

    @Id
    private Long assemblyId;
    @Id
    private Long memberId;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Long proxyMemberId;
    private Long recordedBy;
    private Instant recordedAt = Instant.now();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long assemblyId;
        private Long memberId;
    }
}
