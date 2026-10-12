package com.sclera.applicationplane.procedure.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the versioned prompt files under classpath:/prompts and substitutes
 * {@code {{variable}}} placeholders. Prompts are files, not inline strings, so
 * a prompt change is a reviewable diff and never a recompile.
 */
@Component
@ConditionalOnProperty(prefix = "sclera.ai", name = "enabled", havingValue = "true")
public class PromptLoader {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** Renders prompts/{name} with the given variables. Unknown placeholders are left intact. */
    public String render(String name, Map<String, String> variables) {
        String prompt = cache.computeIfAbsent(name, this::read);
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            prompt = prompt.replace("{{" + variable.getKey() + "}}",
                    variable.getValue() == null ? "" : variable.getValue());
        }
        return prompt;
    }

    private String read(String name) {
        try {
            return new ClassPathResource("prompts/" + name)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Prompt file not found: prompts/" + name, e);
        }
    }
}
