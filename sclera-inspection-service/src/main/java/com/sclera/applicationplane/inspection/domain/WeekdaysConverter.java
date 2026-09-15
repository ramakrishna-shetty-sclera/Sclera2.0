package com.sclera.applicationplane.inspection.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Set&lt;Weekday&gt; ↔ CSV column ("MON,WED,FRI"). */
@Converter
public class WeekdaysConverter implements AttributeConverter<Set<Weekday>, String> {

    @Override
    public String convertToDatabaseColumn(Set<Weekday> attribute) {
        if (attribute == null || attribute.isEmpty()) return "";
        return attribute.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }

    @Override
    public Set<Weekday> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return EnumSet.noneOf(Weekday.class);
        return Arrays.stream(dbData.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Weekday::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Weekday.class)));
    }
}
