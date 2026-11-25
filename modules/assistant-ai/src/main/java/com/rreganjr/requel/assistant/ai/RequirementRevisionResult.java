package com.rreganjr.requel.assistant.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;

/** Dedicated provider output for one unsaved wording suggestion, not a review finding. */
public record RequirementRevisionResult(String revisedShall) {
    public static final String SCHEMA_NAME = "RequirementRevisionPreviewOutput";
    public static final int MAX_LENGTH = 2000;

    /** Build a transport DTO; validation is explicit so malformed provider output is a checked error. */
    public RequirementRevisionResult { }

    /** Parse CLI JSON without discarding unexpected fields or coercing non-string values. */
    public static RequirementRevisionResult parse(String reply, ObjectMapper mapper) throws AiAnalysisException {
        String json = ReviewResultMapper.stripCodeFence(reply);
        if (json == null || json.isBlank()) throw new AiAnalysisException("Revision reply was empty");
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.isObject() || node.size() != 1
                    || !node.path("revisedShall").isTextual())
                throw new AiAnalysisException("Revision reply must contain only a revisedShall string");
            var result = new RequirementRevisionResult(node.path("revisedShall").textValue());
            result.validatedText();
            return result;
        } catch (JsonProcessingException e) {
            throw new AiAnalysisException("Revision reply was not valid JSON", e);
        }
    }

    /** Preserve transport usage/metadata while returning no review findings or persistence actions. */
    public AiAnalysisResponse toResponse(AiUsage usage, Map<String, Object> metadata) throws AiAnalysisException {
        String text = validatedText();
        return new AiAnalysisResponse("Requirement revision preview",
            JsonNodeFactory.instance.objectNode().put("revisedShall", text),
            List.of(), List.of(), usage, metadata);
    }

    private String validatedText() throws AiAnalysisException {
        if (revisedShall == null || revisedShall.isBlank() || revisedShall.length() > MAX_LENGTH)
            throw new AiAnalysisException("Revision wording must be nonblank and at most 2000 characters");
        return revisedShall.strip();
    }
}
