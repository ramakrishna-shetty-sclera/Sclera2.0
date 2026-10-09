package com.sclera.applicationplane.procedure.repository;

import com.sclera.applicationplane.procedure.domain.GlobalQuestionIndexEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * The question bank's rows. Lives in {@code public}, so there is no tenant to
 * scope a query to: every organization reads the same library.
 */
public interface GlobalQuestionIndexRepository extends JpaRepository<GlobalQuestionIndexEntry, UUID> {

    List<GlobalQuestionIndexEntry> findAllByGlobalVersionIdOrderByQuestionKey(UUID globalVersionId);

    /** Clears one version's rows, so indexing it again replaces them rather than doubling them. */
    @Modifying
    @Query("delete from GlobalQuestionIndexEntry e where e.globalVersionId = :versionId")
    int deleteByGlobalVersionId(@Param("versionId") UUID versionId);

    /** One question of the bank, with where it came from. */
    interface BankRow {
        String getQuestionKey();

        String getText();

        String getStandard();

        String getSectionText();

        UUID getTemplateId();

        String getTemplateName();

        int getVersionNo();
    }

    /**
     * The question bank: questions from the <em>current</em> published version of
     * every active template, optionally narrowed by words in the question and by
     * the standard it came from.
     *
     * <p><b>Current version only.</b> The index holds rows for every version ever
     * published, so without the join to {@code current_published_version_id} one
     * question would appear once per version of its template, and an archived
     * template's questions would stay in the bank for good.
     *
     * <p>Native SQL, because full-text search is not expressible in JPQL, which also means the
     * tables are qualified with {@code public.} by hand: an entity is qualified by its mapping, but a
     * native query is not, and a request's search_path is its own organization's schema with no public
     * fallback. The search matches the same {@code to_tsvector('english', text)} expression the
     * GIN index is built on, so stemming applies ("extinguishers" finds
     * "extinguisher"). {@code websearch_to_tsquery} accepts anything a person can
     * type, quotes and minus signs included, and never raises a syntax error. A
     * query made only of stop words such as "the" matches nothing.
     *
     * <p>As in the template list, neither filter is a nullable parameter: an
     * absent one arrives as {@code ''}, which means "no filter". Results are
     * ordered by relevance when searching, then by template name and question key
     * so a page is stable. The order is fixed here, so callers must pass a
     * {@link Pageable} with no sort of its own: Spring Data would append it to
     * this SQL as written.
     */
    @Query(value = """
            select e.question_key  as questionKey,
                   e.text          as text,
                   e.standard      as standard,
                   e.section_text  as sectionText,
                   t.id            as templateId,
                   t.name          as templateName,
                   v.version_no    as versionNo
            from public.global_question_index e
            join public.global_procedure_template t
              on t.id = e.global_template_id
             and t.current_published_version_id = e.global_version_id
            join public.global_procedure_template_version v on v.id = e.global_version_id
            where t.status = 'ACTIVE'
              and (:search = '' or to_tsvector('english', e.text) @@ websearch_to_tsquery('english', :search))
              and (:standard = '' or lower(e.standard) = lower(:standard))
            order by case when :search = ''
                          then 0
                          else ts_rank(to_tsvector('english', e.text), websearch_to_tsquery('english', :search))
                     end desc,
                     t.name, e.question_key
            """,
            countQuery = """
            select count(*)
            from public.global_question_index e
            join public.global_procedure_template t
              on t.id = e.global_template_id
             and t.current_published_version_id = e.global_version_id
            where t.status = 'ACTIVE'
              and (:search = '' or to_tsvector('english', e.text) @@ websearch_to_tsquery('english', :search))
              and (:standard = '' or lower(e.standard) = lower(:standard))
            """,
            nativeQuery = true)
    Page<BankRow> searchBank(@Param("search") String search, @Param("standard") String standard, Pageable pageable);
}
