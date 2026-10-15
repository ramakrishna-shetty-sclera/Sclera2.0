package com.sclera.applicationplane.procedure.export;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.RangeRule;
import com.sclera.applicationplane.procedure.domain.VersionState;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The readable format: the checklist laid out the way the read-only view shows
 * it — numbered questions, follow-ups indented under the answer that reveals
 * them, options with their results.
 */
@Component
public class PdfTemplateExporter implements TemplateExporter {

    private static final PDFont REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final float MARGIN = 54;
    private static final float LEADING = 1.45f;

    @Override
    public ExportFormat format() {
        return ExportFormat.PDF;
    }

    @Override
    public ExportFile export(TemplateExport template) {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Cursor cursor = new Cursor(document);

            cursor.write(template.name(), BOLD, 18, 0);
            String meta = "Version " + template.versionNo()
                    + (template.state() == VersionState.PUBLISHED ? " - Published" : " - " + template.state())
                    + (template.publishedAt() != null
                            ? " " + template.publishedAt().format(DateTimeFormatter.ISO_LOCAL_DATE) : "")
                    + " - " + template.definition().questionCount() + " questions";
            cursor.write(meta, REGULAR, 10, 0);
            if (template.description() != null && !template.description().isBlank()) {
                cursor.gap(4);
                cursor.write(template.description(), REGULAR, 10, 0);
            }
            cursor.gap(10);

            for (TemplateExport.Row row : template.rows()) {
                if (row.section()) {
                    cursor.gap(8);
                    cursor.write(row.item().text().toUpperCase(), BOLD, 10, 0);
                    cursor.gap(2);
                    continue;
                }
                Item q = row.item();
                float indent = row.depth() * 18;
                cursor.gap(6);
                if (row.whenLabel() != null) {
                    cursor.write("If " + parentNumber(row.number()) + " is " + row.whenLabel() + ":",
                            REGULAR, 8.5f, indent);
                }
                cursor.write(row.number() + ". " + q.text() + (q.required() ? " *" : ""), BOLD, 11, indent);
                if (q.help() != null && !q.help().isBlank()) {
                    cursor.write(q.help(), REGULAR, 9, indent + 14);
                }
                cursor.write(answerLine(q), REGULAR, 9, indent + 14);
                String extras = extrasLine(q);
                if (!extras.isEmpty()) {
                    cursor.write(extras, REGULAR, 8.5f, indent + 14);
                }
            }

            cursor.close();
            document.save(out);
            return new ExportFile(ExportNames.filename(template, format()), format().mediaType(), out.toByteArray());
        } catch (IOException e) {
            throw new BusinessRuleException("PDF export failed: " + e.getMessage());
        }
    }

    private static String parentNumber(String number) {
        int dot = number.lastIndexOf('.');
        return dot < 0 ? number : number.substring(0, dot);
    }

    private static String answerLine(Item q) {
        if (!q.options().isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Option option : q.options()) {
                parts.add(option.label() + (option.result() == null ? "" : " = " + option.result()));
            }
            return "Answers: " + String.join("  |  ", parts);
        }
        String line = "Answer: " + q.type();
        if (q.unit() != null) {
            line += " (" + q.unit() + ")";
        }
        if (q.min() != null || q.max() != null) {
            line += "  range " + (q.min() == null ? "-" : q.min()) + " to " + (q.max() == null ? "-" : q.max());
        }
        return line;
    }

    private static String extrasLine(Item q) {
        List<String> parts = new ArrayList<>();
        if (!q.rules().isEmpty()) {
            List<String> bands = new ArrayList<>();
            for (RangeRule rule : q.rules()) {
                bands.add((rule.min() == null ? "" : rule.min()) + ".." + (rule.max() == null ? "" : rule.max())
                        + (rule.result() == null ? "" : " = " + rule.result()));
            }
            parts.add("Bands: " + String.join(", ", bands));
        }
        if (q.workOrder()) {
            parts.add("Raises a work order on failure");
        }
        if (q.standard() != null && !q.standard().isBlank()) {
            parts.add("Standard: " + q.standard());
        }
        return String.join("  -  ", parts);
    }

    /** Page/line state: wraps text to the content width and breaks pages as it goes. */
    private static final class Cursor {
        private final PDDocument document;
        private PDPageContentStream stream;
        private float y;
        private final float width = PDRectangle.A4.getWidth() - 2 * MARGIN;

        Cursor(PDDocument document) throws IOException {
            this.document = document;
            newPage();
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PDRectangle.A4.getHeight() - MARGIN;
        }

        void gap(float points) {
            y -= points;
        }

        void write(String text, PDFont font, float size, float indent) throws IOException {
            for (String line : wrap(sanitize(text), font, size, width - indent)) {
                float lineHeight = size * LEADING;
                if (y - lineHeight < MARGIN) {
                    newPage();
                }
                stream.beginText();
                stream.setFont(font, size);
                stream.newLineAtOffset(MARGIN + indent, y - size);
                stream.showText(line);
                stream.endText();
                y -= lineHeight;
            }
        }

        void close() throws IOException {
            stream.close();
        }

        private static List<String> wrap(String text, PDFont font, float size, float maxWidth) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (font.getStringWidth(candidate) / 1000 * size > maxWidth && !line.isEmpty()) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
            return lines.isEmpty() ? List.of("") : lines;
        }

        /** Standard-14 fonts are WinAnsi only; swap what they cannot encode. */
        private static String sanitize(String text) {
            StringBuilder out = new StringBuilder(text.length());
            for (char c : text.toCharArray()) {
                out.append(switch (c) {
                    case '‘', '’' -> '\'';
                    case '“', '”' -> '"';
                    case '–', '—' -> '-';
                    case '•' -> '*';
                    default -> c < 32 || c > 255 ? '?' : c;
                });
            }
            return out.toString();
        }
    }
}
