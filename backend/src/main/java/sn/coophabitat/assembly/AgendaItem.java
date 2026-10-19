package sn.coophabitat.assembly;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "agenda_item")
@Getter
@Setter
@NoArgsConstructor
public class AgendaItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long assemblyId;
    private int position;
    private String title;
    private String description;
}
