package com.sclera.applicationplane.procedure.controller;

import com.sclera.applicationplane.procedure.dto.GlobalLibraryBrowseDtos.GlobalTemplateDetail;
import com.sclera.applicationplane.procedure.dto.GlobalLibraryBrowseDtos.GlobalTemplateSummary;
import com.sclera.applicationplane.procedure.dto.GlobalLibraryBrowseDtos.QuestionBankEntry;
import com.sclera.applicationplane.procedure.service.GlobalLibraryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Browsing the Sclera-wide library. Responses are wrapped in the standard
 * envelope by sclera-common's ResponseEnvelopeAdvice.
 *
 * <p><b>No {@code @PreAuthorize}, on purpose.</b> Every other endpoint here asks
 * OpenFGA whether the caller may see an organization's or a procedure's data. The
 * library belongs to no organization: it is Sclera's own content, the same for
 * everyone, so there is no organization relation to check and none worth
 * inventing just to guard a read. Any signed-in user may browse it, which the
 * security configuration already requires of every request. An endpoint that
 * looks unguarded here is a decision and not an oversight.
 */
@RestController
@RequestMapping("/api/v1/global-procedure-templates")
public class GlobalLibraryController {

    private final GlobalLibraryService library;

    public GlobalLibraryController(GlobalLibraryService library) {
        this.library = library;
    }

    /** The library, optionally narrowed by a word in the name or description and by consumer. */
    @GetMapping
    public Page<GlobalTemplateSummary> list(@RequestParam(required = false) String search,
                                            @RequestParam(required = false) String consumer,
                                            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return library.browse(search, consumer, pageable);
    }

    /**
     * The question bank: questions from every template in the library, found by words
     * in the question and/or the standard it came from. The literal path wins over
     * {@code /{id}}, so this is never read as a template id.
     *
     * <p>Takes a bare page and size and no sort: the order is fixed by relevance, then
     * template name and key, and a size above 100 is cut to 100.
     */
    @GetMapping("/questions")
    public Page<QuestionBankEntry> questions(@RequestParam(required = false) String search,
                                             @RequestParam(required = false) String standard,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return library.searchQuestions(search, standard, page, size);
    }

    /** One template with its current version's whole form, to read before importing it. */
    @GetMapping("/{id}")
    public GlobalTemplateDetail get(@PathVariable UUID id) {
        return library.get(id);
    }
}
