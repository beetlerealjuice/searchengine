package searchengine.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "page", indexes = @Index(columnList = "path"))
public class Page {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @ManyToOne(cascade = CascadeType.MERGE, fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id")
    private Site site;

    @Column(columnDefinition = "VARCHAR(255)")
    private String path;

    private int code;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String content;
}
