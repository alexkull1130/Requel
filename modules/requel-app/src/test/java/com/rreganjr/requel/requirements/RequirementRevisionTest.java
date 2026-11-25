package com.rreganjr.requel.requirements;

import com.rreganjr.platform.command.AuthorizationException;
import com.rreganjr.requel.assistant.ai.IdeaToRequirementsService;
import com.rreganjr.requel.assistant.api.AssistantContext;
import com.rreganjr.requel.assistant.api.AssistantException;
import com.rreganjr.requel.project.*;
import com.rreganjr.requel.project.exception.NoSuchProjectException;
import com.rreganjr.requel.project.impl.IdeaImpl;
import com.rreganjr.requel.project.impl.RequirementImpl;
import com.rreganjr.requel.service.auth.CurrentUserResolver;
import com.rreganjr.requel.user.User;
import com.rreganjr.requel.user.WorkflowRole;
import jakarta.persistence.EntityManager;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Authorization, isolation and actual Spring transaction boundaries of AI revision previews. */
class RequirementRevisionTest {
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final CurrentUserResolver users = mock(CurrentUserResolver.class);
    private final EntityManager em = mock(EntityManager.class);
    private final Project project = mock(Project.class);
    private final User user = mock(User.class);
    private final UserStakeholder stakeholder = mock(UserStakeholder.class);
    private final StakeholderPermission edit = mock(StakeholderPermission.class);
    private final IdeaToRequirementsService ai = mock(IdeaToRequirementsService.class);
    private RequirementAiPreviewService snapshots;
    private RequirementController controller;
    private IdeaImpl idea;
    private RequirementImpl requirement;
    private final RequirementController.RevisionPreviewRequest request =
        new RequirementController.RevisionPreviewRequest("Include signed CSV", 0L, 0L);

    @BeforeEach void setUp() {
        when(projects.findProjectByName("alpha")).thenReturn(project);
        when(project.getId()).thenReturn(1L);
        when(users.resolve()).thenReturn(user);
        when(user.getId()).thenReturn(42L);
        when(user.getUsername()).thenReturn("pm");
        when(user.getWorkflowRole()).thenReturn(WorkflowRole.PRODUCT_MANAGER);
        when(project.getUserStakeholder(user)).thenReturn(stakeholder);
        when(edit.getPermissionKey()).thenReturn(Project.class.getName() + "[Edit]");
        when(stakeholder.getStakeholderPermissions()).thenReturn(Set.of(edit));
        idea = new IdeaImpl(1L, "Export reports", null, user);
        ReflectionTestUtils.setField(idea, "id", 8L);
        idea.setStatus(Idea.Status.BREAKDOWN_REVIEW);
        requirement = new RequirementImpl(idea, "The system shall export CSV.", "pm");
        ReflectionTestUtils.setField(requirement, "id", 7L);
        idea.getDerivedRequirementsInternal().add(requirement);
        when(em.find(IdeaImpl.class, 8L)).thenReturn(idea);
        when(em.find(RequirementImpl.class, 7L)).thenReturn(requirement);
        snapshots = new RequirementAiPreviewService(projects, users);
        ReflectionTestUtils.setField(snapshots, "em", em);
        controller = new RequirementController(projects, users, null, snapshots, ai);
    }

    @Test void previewUsesAuthenticatedDetachedValuesWithoutSavingAnything() throws Exception {
        when(ai.previewRevision(any(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> {
                AssistantContext context = invocation.getArgument(0);
                assertEquals(42L, context.triggeringUser().userId());
                assertEquals("pm", context.triggeringUser().username());
                assertEquals(1L, context.projectRef().entityId());
                assertEquals(7L, context.attributes().get("requirementId"));
                assertEquals("Export reports", invocation.getArgument(1));
                assertEquals("The system shall export CSV.", invocation.getArgument(2));
                assertEquals("Include signed CSV", invocation.getArgument(3));
                return "The system shall export signed CSV.";
            });
        var response = controller.previewRevision("alpha", 8, 7, request);
        assertEquals("The system shall export signed CSV.", response.revisedShall());
        assertEquals(0, response.ideaVersion());
        assertEquals(0, response.requirementVersion());
        assertEquals("The system shall export CSV.", requirement.getShallText());
        assertEquals(Requirement.Status.DRAFT, requirement.getStatus());
        assertNull(requirement.getBreakdownAction());
        assertNull(requirement.getBreakdownFeedback());
        assertEquals(Idea.Status.BREAKDOWN_REVIEW, idea.getStatus());
        verify(em, never()).persist(any());
        verify(em, never()).merge(any());
        verify(em, never()).flush();
        verify(em, never()).remove(any());
    }

    @Test void requiresBothEditPermissionAndProductManagerRole() {
        when(user.getWorkflowRole()).thenReturn(WorkflowRole.ENGINEER);
        assertThrows(AuthorizationException.class, () -> controller.previewRevision("alpha", 8, 7, request));
        when(user.getWorkflowRole()).thenReturn(WorkflowRole.PRODUCT_MANAGER);
        when(stakeholder.getStakeholderPermissions()).thenReturn(Set.of());
        assertThrows(AuthorizationException.class, () -> controller.previewRevision("alpha", 8, 7, request));
        when(project.getUserStakeholder(user)).thenReturn(null);
        assertThrows(AuthorizationException.class, () -> controller.previewRevision("alpha", 8, 7, request));
        verifyNoInteractions(ai, em);
    }

    @Test void rejectsMissingInvalidFeedbackAndVersionsBeforeCallingAI() {
        assertThrows(IllegalArgumentException.class, () -> controller.previewRevision("alpha", 8, 7, null));
        for (var body : java.util.List.of(
                new RequirementController.RevisionPreviewRequest(" ", 0L, 0L),
                new RequirementController.RevisionPreviewRequest("x".repeat(10001), 0L, 0L),
                new RequirementController.RevisionPreviewRequest("feedback", null, 0L),
                new RequirementController.RevisionPreviewRequest("feedback", 0L, null),
                new RequirementController.RevisionPreviewRequest("feedback", -1L, 0L),
                new RequirementController.RevisionPreviewRequest("feedback", 0L, -1L))) {
            assertThrows(IllegalArgumentException.class, () -> controller.previewRevision("alpha", 8, 7, body));
        }
        verifyNoInteractions(ai, em);
    }

    @Test void absentProjectsHaveANotFoundResponse() {
        when(projects.findProjectByName("alpha")).thenThrow(NoSuchProjectException.forName("alpha"));
        assertStatus(404, () -> controller.previewRevision("alpha", 8, 7, request));
        verifyNoInteractions(ai, em);
    }

    @Test void crossProjectAndWrongIdeaIdsAreNotExposed() {
        ReflectionTestUtils.setField(idea, "projectId", 2L);
        assertStatus(404, () -> controller.previewRevision("alpha", 8, 7, request));
        ReflectionTestUtils.setField(idea, "projectId", 1L);
        ReflectionTestUtils.setField(requirement, "projectId", 2L);
        assertStatus(404, () -> controller.previewRevision("alpha", 8, 7, request));
        ReflectionTestUtils.setField(requirement, "projectId", 1L);
        ReflectionTestUtils.setField(requirement, "idea", null);
        assertStatus(404, () -> controller.previewRevision("alpha", 8, 7, request));
        var other = new IdeaImpl(1L, "Other idea", null, user);
        ReflectionTestUtils.setField(other, "id", 9L);
        ReflectionTestUtils.setField(requirement, "idea", other);
        assertStatus(404, () -> controller.previewRevision("alpha", 8, 7, request));
        when(em.find(RequirementImpl.class, 7L)).thenReturn(null);
        assertStatus(404, () -> controller.previewRevision("alpha", 8, 7, request));
        verifyNoInteractions(ai);
    }

    @Test void rejectsCompletedIdeasNonDraftProposalsAndStaleVersions() {
        idea.setStatus(Idea.Status.COMPLETE);
        assertThrows(IllegalStateException.class, () -> controller.previewRevision("alpha", 8, 7, request));
        idea.setStatus(Idea.Status.BREAKDOWN_REVIEW);
        ReflectionTestUtils.setField(requirement, "status", Requirement.Status.APPROVED);
        assertThrows(IllegalStateException.class, () -> controller.previewRevision("alpha", 8, 7, request));
        ReflectionTestUtils.setField(requirement, "status", Requirement.Status.DRAFT);
        assertStatus(409, () -> controller.previewRevision("alpha", 8, 7,
            new RequirementController.RevisionPreviewRequest("feedback", 1L, 0L)));
        assertStatus(409, () -> controller.previewRevision("alpha", 8, 7,
            new RequirementController.RevisionPreviewRequest("feedback", 0L, 1L)));
        verifyNoInteractions(ai);
    }

    @Test void failedAILeavesInputsAndReviewUnchangedForRetry() throws Exception {
        when(ai.previewRevision(any(), anyString(), anyString(), anyString()))
            .thenThrow(new AssistantException("provider secret must not appear in the HTTP response"));
        var error = assertThrows(ResponseStatusException.class,
            () -> controller.previewRevision("alpha", 8, 7, request));
        assertEquals(503, error.getStatusCode().value());
        assertTrue(error.getReason().contains("Try again"));
        assertFalse(error.getReason().contains("provider secret"));
        assertEquals(Idea.Status.BREAKDOWN_REVIEW, idea.getStatus());
        assertEquals("The system shall export CSV.", requirement.getShallText());
        doReturn("Retry worked").when(ai).previewRevision(any(), anyString(), anyString(), anyString());
        assertEquals("Retry worked", controller.previewRevision("alpha", 8, 7, request).revisedShall());
    }

    @Test void disabledAIIsExplicitAndDoesNotChangeTheReview() {
        var noAI = new RequirementController(projects, users, null, snapshots, null);
        assertStatus(503, () -> noAI.previewRevision("alpha", 8, 7, request));
        assertEquals(Idea.Status.BREAKDOWN_REVIEW, idea.getStatus());
        verifyNoInteractions(ai);
    }

    @Test void rechecksVersionAndPermissionsAfterSlowGeneration() throws Exception {
        when(ai.previewRevision(any(), anyString(), anyString(), anyString())).thenAnswer(i -> {
            ReflectionTestUtils.setField(requirement, "version", 1L);
            return "An obsolete suggestion";
        });
        assertStatus(409, () -> controller.previewRevision("alpha", 8, 7, request));
        ReflectionTestUtils.setField(requirement, "version", 0L);
        doAnswer(i -> {
            when(user.getWorkflowRole()).thenReturn(WorkflowRole.ENGINEER);
            return "An unauthorized suggestion";
        }).when(ai).previewRevision(any(), anyString(), anyString(), anyString());
        assertThrows(AuthorizationException.class, () -> controller.previewRevision("alpha", 8, 7, request));
    }

    @Test void httpEndpointMapsTheRequestAndReturnsUsefulErrors() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(ai.previewRevision(any(), anyString(), anyString(), anyString())).thenReturn("The system shall sign CSV.");
        String url = "/api/requirements/ideas/8/requirements/7/ai-preview?projectName=alpha";
        mvc.perform(post(url).contentType("application/json")
            .content("{\"feedback\":\"Sign it\",\"expectedIdeaVersion\":0,\"expectedRequirementVersion\":0}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revisedShall").value("The system shall sign CSV."))
            .andExpect(jsonPath("$.ideaVersion").value(0)).andExpect(jsonPath("$.requirementVersion").value(0));
        mvc.perform(post(url).contentType("application/json").content("{\"feedback\":\"Sign it\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration { }

    @Test void springProxiesEndValidationTransactionsBeforeCallingAIAndResumeTheCaller() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionConfiguration.class);
            // This focused context uses the manually injected mock EntityManager; the full
            // database integration test exercises the real JPA persistence context separately.
            String persistenceProcessor = org.springframework.context.annotation.AnnotationConfigUtils
                .PERSISTENCE_ANNOTATION_PROCESSOR_BEAN_NAME;
            if (context.containsBeanDefinition(persistenceProcessor)) context.removeBeanDefinition(persistenceProcessor);
            context.registerBean("transactionManager", PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(new DriverManagerDataSource(
                    "jdbc:h2:mem:revision-boundary;DB_CLOSE_DELAY=-1", "sa", "")));
            context.registerBean("snapshotService", RequirementAiPreviewService.class, () -> snapshots);
            context.registerBean("controller", RequirementController.class, () ->
                new RequirementController(projects, users, null,
                    context.getBean(RequirementAiPreviewService.class), ai));
            context.refresh();
            var proxy = context.getBean(RequirementController.class);
            assertTrue(AopUtils.isAopProxy(proxy));
            assertTrue(AopUtils.isAopProxy(context.getBean(RequirementAiPreviewService.class)));
            when(em.find(IdeaImpl.class, 8L)).thenAnswer(i -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                return idea;
            });
            when(ai.previewRevision(any(), anyString(), anyString(), anyString())).thenAnswer(i -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "The slow AI call must run outside both the controller and validation transactions");
                return "The system shall sign CSV.";
            });
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("The system shall sign CSV.", proxy.previewRevision("alpha", 8, 7, request).revisedShall());
            new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                proxy.previewRevision("alpha", 8, 7, request);
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "Resume the caller transaction");
            });
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            verify(ai, times(2)).previewRevision(any(), anyString(), anyString(), anyString());
        }
    }

    private static void assertStatus(int status, org.junit.jupiter.api.function.Executable action) {
        assertEquals(status, assertThrows(ResponseStatusException.class, action).getStatusCode().value());
    }
}
