package com.sclera.applicationplane.procedure.ai;

import com.sclera.controlplane.common.exception.ValidationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Text out of an uploaded PDF / DOCX / TXT, and chunking for generation.
 * Extraction happens once per document (the result is persisted on
 * ai_document); generation re-runs never come back here.
 */
@Component
@ConditionalOnProperty(prefix = "sclera.ai", name = "enabled", havingValue = "true")
public class DocumentExtractionService {

    /** File types the upload endpoint accepts; checked by extension. */
    public static boolean isSupported(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        return name.endsWith(".pdf") || name.endsWith(".docx") || name.endsWith(".txt");
    }

    public String extract(String filename, byte[] content) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".pdf")) {
                try (PDDocument document = Loader.loadPDF(content)) {
                    return new PDFTextStripper().getText(document);
                }
            }
            if (name.endsWith(".docx")) {
                try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content));
                     XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                    return extractor.getText();
                }
            }
            if (name.endsWith(".txt")) {
                return new String(content, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new ValidationException("Could not read '" + filename + "': " + e.getMessage());
        }
        throw new ValidationException("Unsupported document type: '" + filename
                + "'. Upload a PDF, DOCX or TXT file.");
    }

    /**
     * Splits on paragraph boundaries where possible, hard-splits where not.
     * Capped at {@code maxChunks} — a document past the cap is truncated, and
     * the caller says so rather than silently generating from part of it.
     */
    public List<String> chunk(String text, int chunkChars, int maxChunks) {
        String whole = text == null ? "" : text.strip();
        if (whole.length() <= chunkChars) {
            return whole.isEmpty() ? List.of() : List.of(whole);
        }
        List<String> chunks = new ArrayList<>();
        int from = 0;
        while (from < whole.length() && chunks.size() < maxChunks) {
            int to = Math.min(from + chunkChars, whole.length());
            if (to < whole.length()) {
                // Prefer ending a chunk at a paragraph break in its second half.
                int breakAt = whole.lastIndexOf("\n\n", to);
                if (breakAt > from + chunkChars / 2) {
                    to = breakAt;
                }
            }
            String chunk = whole.substring(from, to).strip();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            from = to;
        }
        return chunks;
    }

    /** True when {@link #chunk} hit its cap and dropped the tail of the text. */
    public boolean truncates(String text, int chunkChars, int maxChunks) {
        return text != null && text.strip().length() > (long) chunkChars * maxChunks;
    }
}
