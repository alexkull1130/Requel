package com.rreganjr.requel.assistant.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The real provider adapters must not reinterpret wording suggestions as review findings. */
class RequirementRevisionResultTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void parsesOnlyTheDedicatedShapeAndPreservesTransportMetadata() throws Exception {
        var result = RequirementRevisionResult.parse("```json\n{\"revisedShall\":\"  Export signed CSV.  \"}\n```", mapper);
        var usage = AiUsage.noop("fixture", Duration.ZERO);
        var response = result.toResponse(usage, Map.of("model", "fixture"));
        assertEquals("Export signed CSV.", response.structuredOutput().path("revisedShall").asText());
        assertEquals(1, response.structuredOutput().size());
        assertTrue(response.findings().isEmpty());
        assertTrue(response.messages().isEmpty());
        assertSame(usage, response.usage());
        assertEquals("fixture", response.providerMetadata().get("model"));
    }

    @Test void rejectsMissingMalformedNontextualBlankAndUnexpectedFields() {
        for (String json : new String[] { null, "", "not json", "[]", "{}", "null",
                "{\"revisedShall\":null}", "{\"revisedShall\":3}", "{\"revisedShall\":\" \"}",
                "{\"revisedShall\":\"valid\",\"unexpected\":true}" }) {
            assertThrows(AiAnalysisException.class, () -> RequirementRevisionResult.parse(json, mapper));
        }
    }

    @Test void validatesTypedSpringResponsesAsWellAsParsedCliResponses() throws Exception {
        var usage = AiUsage.noop("fixture", Duration.ZERO);
        for (String text : new String[] { null, " ", "x".repeat(2001) }) {
            assertThrows(AiAnalysisException.class, () -> new RequirementRevisionResult(text).toResponse(usage, Map.of()));
        }
        String boundary = "x".repeat(2000);
        assertEquals(boundary, new RequirementRevisionResult(boundary).toResponse(usage, Map.of())
            .structuredOutput().path("revisedShall").asText());
    }
}
