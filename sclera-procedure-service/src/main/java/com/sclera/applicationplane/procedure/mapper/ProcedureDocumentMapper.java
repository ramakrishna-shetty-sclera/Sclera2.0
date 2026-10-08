package com.sclera.applicationplane.procedure.mapper;

import com.sclera.applicationplane.procedure.domain.ProcedureDocument;
import com.sclera.applicationplane.procedure.dto.ProcedureDocumentResponse;
import org.springframework.stereotype.Component;

@Component
public class ProcedureDocumentMapper {

    public ProcedureDocumentResponse toResponse(ProcedureDocument document) {
        return new ProcedureDocumentResponse(
                document.getId(),
                document.getPropertyId(),
                document.getName(),
                document.getMimeType(),
                document.getSizeBytes(),
                document.getLocation(),
                document.isActive(),
                document.getUploadedBy(),
                document.getUploadedAt());
    }
}
