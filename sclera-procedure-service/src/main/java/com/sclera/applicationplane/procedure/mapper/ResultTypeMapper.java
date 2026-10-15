package com.sclera.applicationplane.procedure.mapper;

import com.sclera.applicationplane.procedure.domain.ResultType;
import com.sclera.applicationplane.procedure.dto.ResultTypeRequest;
import com.sclera.applicationplane.procedure.dto.ResultTypeResponse;
import com.sclera.applicationplane.procedure.dto.ResultTypeUpdateRequest;
import org.springframework.stereotype.Component;

@Component
public class ResultTypeMapper {

    /** Key is set only here; it is immutable once the row exists. */
    public void applyCreate(ResultType resultType, ResultTypeRequest request) {
        resultType.setKey(request.key());
        resultType.setName(request.name());
        resultType.setColor(request.color());
        resultType.setDescription(request.description());
    }

    public void applyUpdate(ResultType resultType, ResultTypeUpdateRequest request) {
        resultType.setName(request.name());
        resultType.setColor(request.color());
        resultType.setDescription(request.description());
    }

    public ResultTypeResponse toResponse(ResultType resultType) {
        return new ResultTypeResponse(
                resultType.getId(),
                resultType.getKey(),
                resultType.getName(),
                resultType.getColor(),
                resultType.getDescription(),
                resultType.getSeverityOrder(),
                resultType.isSystem(),
                resultType.isActive(),
                resultType.getCreatedAt(),
                resultType.getUpdatedAt());
    }
}
