package sn.coophabitat.document;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface DocumentRepository extends JpaRepository<StoredDocument, Long> {

    List<StoredDocument> findByRelatedTypeAndRelatedIdOrderByIdDesc(String relatedType, Long relatedId);

    @Query("""
            select d from StoredDocument d where d.archivedAt is null
              and (:category is null or d.category = :category)
              and (d.visibility in :visibilities and d.publishedAt is not null
                   or (:memberId is not null and d.ownerMemberId = :memberId))
            order by d.createdAt desc
            """)
    Page<StoredDocument> findVisibleToMember(Collection<DocumentVisibility> visibilities, Long memberId,
                                             DocumentCategory category, Pageable pageable);

    @Query("""
            select d from StoredDocument d where (:includeArchived = true or d.archivedAt is null)
              and (:category is null or d.category = :category)
              and (:visibility is null or d.visibility = :visibility)
            order by d.createdAt desc
            """)
    Page<StoredDocument> searchAll(DocumentCategory category, DocumentVisibility visibility, boolean includeArchived,
                                   Pageable pageable);

    @Query("select d from StoredDocument d where d.visibility = sn.coophabitat.document.DocumentVisibility.PUBLIC "
            + "and d.publishedAt is not null and d.archivedAt is null order by d.publishedAt desc")
    List<StoredDocument> findPublic();
}
