package com.rreganjr.requel.assistant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.rreganjr.requel.assistant.api.AssistantContext;
import com.rreganjr.requel.assistant.api.AssistantException;
import com.rreganjr.requel.assistant.api.EntityRef;
import com.rreganjr.requel.assistant.api.UserRef;
import com.rreganjr.requel.assistant.core.context.EntityContextPackBuilder;
import com.rreganjr.requel.assistant.core.persistence.AssistantUsageRepository;

class IdeaRequirementRevisionTest {

	private static final String IDEA = "Gym members need to book classes.";
	private static final String SHALL = "The system shall let members book a class.";
	private static final String FEEDBACK = "State what confirms the booking.";

	private final EntityContextPackBuilder packBuilder = mock(EntityContextPackBuilder.class);
	private final AssistantUsageRepository usageRepository = mock(AssistantUsageRepository.class);
	private final RecordingAiClient aiClient = new RecordingAiClient();

	@Test
	void previewsOneFocusedRevisionWithDedicatedSchemaAndGovernedRequest() throws Exception {
		String revised = service(enabledProperties()).previewRevision(context(), "  " + IDEA + "  ",
				"  " + SHALL + "  ", "  " + FEEDBACK + "  ");

		assertThat(revised).isEqualTo("The system shall let members book a class and confirm it.");
		AiAnalysisRequest request = aiClient.lastRequest;
		assertThat(request.outputSchemaName()).isEqualTo("RequirementRevisionPreviewOutput");
		assertThat(request.outputSchemaVersion()).isEqualTo("1");
		assertThat(request.outputSchema().path("type").asText()).isEqualTo("object");
		assertThat(request.outputSchema().path("additionalProperties").asBoolean()).isFalse();
		assertThat(request.outputSchema().path("required").get(0).asText()).isEqualTo("revisedShall");
		assertThat(request.outputSchema().path("properties").path("revisedShall").path("type").asText())
				.isEqualTo("string");
		assertThat(request.outputSchema().path("properties").path("revisedShall").path("minLength").asInt())
				.isEqualTo(1);
		assertThat(request.outputSchema().path("properties").path("revisedShall").path("maxLength").asInt())
				.isEqualTo(2000);
		assertThat(request.instructions()).contains("focused replacement")
				.contains("Do not generate a whole draft");
		assertThat(request.dataHandlingFlags()).containsKey("externalProviderAllowed");
		assertThat(request.attributes()).containsEntry("definitionKey", IdeaToRequirements.ASSISTANT_ID);
		verify(usageRepository).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void rejectsInvalidInputsAndMissingContextOrProject() {
		IdeaToRequirementsService service = service(enabledProperties());
		assertThatThrownBy(() -> service.previewRevision(null, IDEA, SHALL, FEEDBACK))
				.isInstanceOf(AssistantException.class);
		assertThatThrownBy(() -> service.previewRevision(contextWithoutProject(), IDEA, SHALL, FEEDBACK))
				.isInstanceOf(AssistantException.class);
		assertThatThrownBy(() -> service.previewRevision(context(), " ", SHALL, FEEDBACK))
				.isInstanceOf(AssistantException.class);
		assertThatThrownBy(() -> service.previewRevision(context(), IDEA, " ", FEEDBACK))
				.isInstanceOf(AssistantException.class);
		assertThatThrownBy(() -> service.previewRevision(context(), IDEA, SHALL, " "))
				.isInstanceOf(AssistantException.class);
		assertThat(aiClient.calls).isZero();
	}

	@Test
	void appliesDefinitionPolicySkipsWithoutCallingTheProvider() {
		assertThatThrownBy(() -> service(enabledProperties()).previewRevision(context("OTHER"), IDEA,
				SHALL, FEEDBACK)).isInstanceOf(AssistantException.class)
				.hasMessageContaining(IdeaToRequirements.TASK_TYPE);
		assertThatThrownBy(() -> service(new AiProperties()).previewRevision(context(), IDEA, SHALL,
				FEEDBACK)).isInstanceOf(AssistantException.class).hasMessageContaining("disabled");
		AiProperties restricted = enabledProperties();
		restricted.setProjectAllowlist(List.of("999"));
		assertThatThrownBy(() -> service(restricted).previewRevision(context(), IDEA, SHALL, FEEDBACK))
				.isInstanceOf(AssistantException.class).hasMessageContaining("projectAllowlist");
		assertThat(aiClient.calls).isZero();
	}

	@Test
	void rejectsInputThatExceedsConfiguredCapIncludingFeedback() {
		AiProperties properties = enabledProperties();
		properties.setMaxInputTokens(-1);

		assertThatThrownBy(() -> service(properties).previewRevision(context(), IDEA, SHALL, FEEDBACK))
				.isInstanceOf(AssistantException.class).hasMessageContaining("maxInputTokens");
		assertThat(aiClient.calls).isZero();
	}

	@Test
	void propagatesProviderFailureWithoutRecordingUsage() {
		aiClient.failure = new AiAnalysisException("provider exploded");

		assertThatThrownBy(() -> service(enabledProperties()).previewRevision(context(), IDEA, SHALL,
				FEEDBACK)).isInstanceOf(AssistantException.class).hasMessageContaining("provider exploded")
				.hasCauseInstanceOf(AiAnalysisException.class);
		verify(usageRepository, never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void rejectsMissingBlankAndOversizeStructuredOutputAfterRecordingUsage() {
		for (JsonNode output : List.of(JsonNodeFactory.instance.objectNode(),
				JsonNodeFactory.instance.objectNode().put("revisedShall", "  "),
				JsonNodeFactory.instance.objectNode().put("revisedShall", "x".repeat(2001)))) {
			aiClient.response = response(output);
			assertThatThrownBy(() -> service(enabledProperties()).previewRevision(context(), IDEA, SHALL,
						FEEDBACK)).isInstanceOf(AssistantException.class);
		}
		verify(usageRepository, org.mockito.Mockito.times(3)).save(org.mockito.ArgumentMatchers.any());
	}

	private IdeaToRequirementsService service(AiProperties properties) {
		return new IdeaToRequirementsService(new AiDefinitionExecutorFactory(aiClient, packBuilder,
				properties, usageRepository, new ObjectMapper()));
	}

	private static AiProperties enabledProperties() {
		AiProperties properties = new AiProperties();
		properties.setEnabled(true);
		return properties;
	}

	private static AssistantContext context() {
		return context(IdeaToRequirements.TASK_TYPE);
	}

	private static AssistantContext context(String taskType) {
		return new AssistantContext(UUID.randomUUID(), new UserRef(3L, "human"),
				new UserRef(4L, "assistant"), EntityRef.of("Project", 2L), taskType, Locale.ROOT,
				Clock.systemUTC(), Map.of());
	}

	private static AssistantContext contextWithoutProject() {
		return new AssistantContext(UUID.randomUUID(), new UserRef(3L, "human"),
				new UserRef(4L, "assistant"), null, IdeaToRequirements.TASK_TYPE, Locale.ROOT,
				Clock.systemUTC(), Map.of());
	}

	private static AiAnalysisResponse response(JsonNode output) {
		return new AiAnalysisResponse("revision", output, List.of(), List.of(),
				AiUsage.noop("noop", Duration.ZERO), Map.of());
	}

	private static final class RecordingAiClient implements AiAnalysisClient {
		private int calls;
		private AiAnalysisRequest lastRequest;
		private AiAnalysisResponse response = response(JsonNodeFactory.instance.objectNode()
				.put("revisedShall", "The system shall let members book a class and confirm it."));
		private AiAnalysisException failure;

		@Override
		public AiAnalysisResponse analyze(AiAnalysisRequest request) throws AiAnalysisException {
			calls++;
			lastRequest = request;
			if (failure != null) {
				throw failure;
			}
			return response;
		}
	}
}
