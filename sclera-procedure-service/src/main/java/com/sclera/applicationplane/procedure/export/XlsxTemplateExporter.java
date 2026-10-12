package com.sclera.applicationplane.procedure.export;

import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Item;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.Option;
import com.sclera.applicationplane.procedure.definition.DefinitionDocument.RangeRule;
import com.sclera.controlplane.common.exception.BusinessRuleException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The structured format: one row per item with everything the definition
 * knows, so the checklist can be filtered and analysed outside Sclera.
 */
@Component
public class XlsxTemplateExporter implements TemplateExporter {

    private static final String[] HEADERS = {
            "#", "Section", "Question", "Type", "Required", "Shown when",
            "Answers", "Unit", "Min", "Max", "Bands", "Work order", "Standard", "Help"
    };

    @Override
    public ExportFormat format() {
        return ExportFormat.XLSX;
    }

    @Override
    public ExportFile export(TemplateExport template) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Checklist");
            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowNo = 1;
            String section = "";
            for (TemplateExport.Row item : template.rows()) {
                if (item.section()) {
                    section = item.item().text();
                    continue;
                }
                Item q = item.item();
                Row row = sheet.createRow(rowNo++);
                int col = 0;
                row.createCell(col++).setCellValue(item.number());
                row.createCell(col++).setCellValue(section);
                row.createCell(col++).setCellValue(q.text());
                row.createCell(col++).setCellValue(String.valueOf(q.type()));
                row.createCell(col++).setCellValue(q.required() ? "Yes" : "No");
                row.createCell(col++).setCellValue(item.whenLabel() == null ? "" : item.whenLabel());
                row.createCell(col++).setCellValue(answers(q));
                row.createCell(col++).setCellValue(q.unit() == null ? "" : q.unit());
                row.createCell(col++).setCellValue(q.min() == null ? "" : String.valueOf(q.min()));
                row.createCell(col++).setCellValue(q.max() == null ? "" : String.valueOf(q.max()));
                row.createCell(col++).setCellValue(bands(q));
                row.createCell(col++).setCellValue(q.workOrder() ? "Yes" : "No");
                row.createCell(col++).setCellValue(q.standard() == null ? "" : q.standard());
                row.createCell(col).setCellValue(q.help() == null ? "" : q.help());
            }
            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            Sheet about = workbook.createSheet("Procedure");
            about(about, headerStyle, template);

            workbook.write(out);
            return new ExportFile(ExportNames.filename(template, format()), format().mediaType(), out.toByteArray());
        } catch (IOException e) {
            throw new BusinessRuleException("XLSX export failed: " + e.getMessage());
        }
    }

    private static void about(Sheet sheet, CellStyle headerStyle, TemplateExport template) {
        String[][] fields = {
                {"Name", template.name()},
                {"Description", template.description() == null ? "" : template.description()},
                {"Version", "v" + template.versionNo() + " (" + template.state() + ")"},
                {"Published", template.publishedAt() == null ? "" : template.publishedAt().toString()},
                {"Questions", String.valueOf(template.definition().questionCount())},
        };
        for (int i = 0; i < fields.length; i++) {
            Row row = sheet.createRow(i);
            Cell label = row.createCell(0);
            label.setCellValue(fields[i][0]);
            label.setCellStyle(headerStyle);
            row.createCell(1).setCellValue(fields[i][1]);
        }
        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
    }

    private static String answers(Item q) {
        if (q.options().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Option option : q.options()) {
            parts.add(option.label() + (option.result() == null ? "" : " = " + option.result()));
        }
        return String.join("; ", parts);
    }

    private static String bands(Item q) {
        if (q.rules().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (RangeRule rule : q.rules()) {
            parts.add((rule.min() == null ? "" : rule.min()) + ".." + (rule.max() == null ? "" : rule.max())
                    + (rule.result() == null ? "" : " = " + rule.result()));
        }
        return String.join("; ", parts);
    }
}
