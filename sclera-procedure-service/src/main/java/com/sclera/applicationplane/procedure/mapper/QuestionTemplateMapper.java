package com.sclera.applicationplane.procedure.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sclera.applicationplane.procedure.domain.Question;
import com.sclera.applicationplane.procedure.domain.QuestionTemplate;
import com.sclera.applicationplane.procedure.domain.TemplateSection;
import com.sclera.applicationplane.procedure.dto.QuestionTemplateRequest;
import com.sclera.applicationplane.procedure.dto.QuestionTemplateResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class QuestionTemplateMapper {

    private final ObjectMapper objectMapper;

    public QuestionTemplateMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void applyRequest(QuestionTemplate template, QuestionTemplateRequest request) {
        template.setName(request.name());
        template.setDescription(request.description());
        template.setCategory(request.category());
        template.replaceSections(request.sections().stream().map(this::toSection).toList());
    }

    private TemplateSection toSection(QuestionTemplateRequest.SectionRequest req) {
        TemplateSection section = new TemplateSection();
        section.setTitle(req.title());
        section.setDisplayOrder(req.displayOrder());
        section.replaceQuestions(req.questions().stream().map(this::toQuestion).toList());
        return section;
    }

    private Question toQuestion(QuestionTemplateRequest.QuestionRequest req) {
        Question q = new Question();
        q.setText(req.text());
        q.setHelpText(req.helpText());
        q.setType(req.type());
        q.setRequired(req.required());
        q.setDisplayOrder(req.displayOrder());
        q.setScoreWeight(req.scoreWeight());
        q.setOptions(writeOptions(req.options()));
        return q;
    }

    public QuestionTemplateResponse toResponse(QuestionTemplate template) {
        return new QuestionTemplateResponse(
                template.getId(),
                template.getOrgId(),
                template.getName(),
                template.getDescription(),
                template.getCategory(),
                template.getStatus(),
                template.getVersion(),
                template.getCreatedBy(),
                template.getCreatedAt(),
                template.getUpdatedAt(),
                template.getSections().stream().map(this::toSectionResponse).toList());
    }

    private QuestionTemplateResponse.SectionResponse toSectionResponse(TemplateSection section) {
        return new QuestionTemplateResponse.SectionResponse(
                section.getId(),
                section.getTitle(),
                section.getDisplayOrder(),
                section.getQuestions().stream().map(this::toQuestionResponse).toList());
    }

    private QuestionTemplateResponse.QuestionResponse toQuestionResponse(Question q) {
        return new QuestionTemplateResponse.QuestionResponse(
                q.getId(),
                q.getText(),
                q.getHelpText(),
                q.getType(),
                q.isRequired(),
                q.getDisplayOrder(),
                readOptions(q.getOptions()),
                q.getScoreWeight());
    }

    private String writeOptions(List<String> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(options);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid question options", e);
        }
    }

    private List<String> readOptions(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt options JSON in database", e);
        }
    }
}
