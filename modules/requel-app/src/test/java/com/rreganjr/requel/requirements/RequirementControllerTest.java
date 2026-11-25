package com.rreganjr.requel.requirements;

import com.rreganjr.requel.assistant.ai.IdeaGenerationService;
import com.rreganjr.requel.project.Idea;
import com.rreganjr.requel.assistant.ai.IdeaToRequirementsService;
import com.rreganjr.requel.project.impl.IdeaImpl;
import com.rreganjr.requel.project.impl.RequirementImpl.BreakdownAction;
import com.rreganjr.requel.service.audit.CommandAuditLog;
import com.rreganjr.requel.assistant.api.UserRef;
import com.rreganjr.platform.command.AuthorizationException;
import com.rreganjr.requel.project.Project;
import com.rreganjr.requel.project.ProjectRepository;
import com.rreganjr.requel.project.Requirement.Department;
import com.rreganjr.requel.project.Requirement.Result;
import com.rreganjr.requel.project.Requirement.Status;
import com.rreganjr.requel.project.StakeholderPermission;
import com.rreganjr.requel.project.UserStakeholder;
import com.rreganjr.requel.project.impl.RequirementImpl;
import com.rreganjr.requel.service.auth.CurrentUserResolver;
import com.rreganjr.requel.user.User;
import com.rreganjr.requel.user.WorkflowRole;
import com.rreganjr.requel.user.impl.SystemAdminUserRole;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequirementControllerTest {
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final CurrentUserResolver users = mock(CurrentUserResolver.class);
    private final EntityManager em = mock(EntityManager.class);
    private final Project project = mock(Project.class);
    private final User user = mock(User.class);
    private final IdeaGenerationService ideaGenerationService = mock(IdeaGenerationService.class);
    private final UserStakeholder stakeholder = mock(UserStakeholder.class);
    private final StakeholderPermission editPermission = mock(StakeholderPermission.class);

    private RequirementController controller(boolean member) {
        return controller(member, ideaGenerationService);
    }

    private RequirementController controller(boolean member, IdeaGenerationService genService) {
        var c = new RequirementController(projects, users, genService,
            mock(RequirementAiPreviewService.class), null);
        ReflectionTestUtils.setField(c, "em", em);
        when(projects.findProjectByName("alpha")).thenReturn(project);
        when(users.resolve()).thenReturn(user);
        when(project.getId()).thenReturn(1L);
        when(project.getUserStakeholder(user)).thenReturn(member ? stakeholder : null);
        when(editPermission.getPermissionKey()).thenReturn(Project.class.getName() + "[Edit]");
        when(stakeholder.getStakeholderPermissions()).thenReturn(Set.of(editPermission));
        return c;
    }

    @Test void nonStakeholdersCannotReadRequirements() {
        var c = controller(false);
        assertThrows(AuthorizationException.class, () -> c.get("alpha", 7));
        verifyNoInteractions(em);
    }

    @Test void idsFromAnotherProjectAreNotExposed() {
        var c = controller(true);
        var r = new RequirementImpl(2L, null, "The system shall export.", "author");
        when(em.find(RequirementImpl.class, 7L)).thenReturn(r);
        var error = assertThrows(ResponseStatusException.class, () -> c.get("alpha", 7));
        assertEquals(404, error.getStatusCode().value());
    }

    @ParameterizedTest
    @CsvSource(value = {"ANALYST, QUALITY", "ENGINEER, FEASIBILITY",
            "TEST_ENGINEER, TESTABILITY", "PRODUCT_MANAGER, NONE"}, nullValues = "NONE")
    void workflowRoleRestrictsDepartmentReviews(WorkflowRole role, Department allowedDepartment) {
        var c = controller(true);
        when(user.getWorkflowRole()).thenReturn(role);
        when(user.getUsername()).thenReturn("reviewer");
        var requirement = requirementInProject();

        var view = c.get("alpha", 7);
        assertEquals(allowedDepartment == null ? List.of() : List.of(allowedDepartment),
                view.get("allowedReviewDepartments"));
        assertEquals(role == WorkflowRole.PRODUCT_MANAGER, view.get("canApproveOrReopen"));

        for (Department department : Department.values()) {
            var request = new RequirementController.ReviewRequest(department, Result.PASSED, null, 0L);
            if (department == allowedDepartment) {
                c.review("alpha", 7, request);
                var review = requirement.getReviews().get(department);
                assertEquals("reviewer", review.getReviewer());
                assertEquals(role.getDisplayName(), review.getReviewerRole());
            } else {
                assertThrows(AuthorizationException.class, () -> c.review("alpha", 7, request));
                assertFalse(requirement.getReviews().containsKey(department));
            }
        }
    }

    @Test void reviewerOutsideTheProjectCannotSubmitEvenTheirOwnReview() {
        var c = controller(false);
        when(user.getWorkflowRole()).thenReturn(WorkflowRole.ANALYST);
        var request = new RequirementController.ReviewRequest(Department.QUALITY, Result.PASSED, null, 0L);
        assertThrows(AuthorizationException.class, () -> c.review("alpha", 7, request));
        verifyNoInteractions(em);
    }

    @Test void systemAdministratorCanSeeAndSubmitAllDepartmentReviews() {
        var c = controller(false);
        when(user.hasRole(SystemAdminUserRole.class)).thenReturn(true);
        when(user.getWorkflowRole()).thenReturn(WorkflowRole.ENGINEER);
        when(user.getUsername()).thenReturn("admin");
        var requirement = requirementInProject();

        assertEquals(List.of(Department.values()), c.get("alpha", 7).get("allowedReviewDepartments"));
        for (Department department : Department.values()) {
            var request = new RequirementController.ReviewRequest(department, Result.PASSED, null, 0L);
            c.review("alpha", 7, request);
            assertEquals("System Administrator", requirement.getReviews().get(department).getReviewerRole());
        }
        assertTrue(requirement.allReviewsPassed());
    }

    @Test void productManagerRoleIsRequiredForApprovalAndReopening() {
        var c = controller(true);
        when(user.getWorkflowRole()).thenReturn(WorkflowRole.ANALYST);
        assertThrows(AuthorizationException.class, () -> c.approve("alpha", 7, new RequirementController.Version(0L)));
        assertThrows(AuthorizationException.class, () -> c.reopen("alpha", 7, new RequirementController.Version(0L)));
        verifyNoInteractions(em);
    }

    @Test void productManagerCanApproveAfterAllReviewsPassAndReopenForFreshReview() {
        var c = controller(true);
        when(user.getWorkflowRole()).thenReturn(WorkflowRole.PRODUCT_MANAGER);
        var requirement = requirementInProject();
        var version = new RequirementController.Version(0L);
        assertThrows(IllegalStateException.class, () -> c.approve("alpha", 7, version));

        for (Department department : Department.values()) {
            requirement.review(department, Result.PASSED, null, "reviewer", "Reviewer");
        }
        c.approve("alpha", 7, version);
        assertEquals(Status.APPROVED, requirement.getStatus());
        c.reopen("alpha", 7, version);
        assertEquals(Status.DRAFT, requirement.getStatus());
        assertTrue(requirement.getReviews().isEmpty());
    }

    @Test void ideaSubmissionWithoutAIGenerationWorks() {
        var c = controller(true, null); // No AI generation service
        when(user.getId()).thenReturn(42L);
        when(user.getUsername()).thenReturn("pm");

        var result = c.submit("alpha", new RequirementController.Submit("The system shall allow users to login with SSO."));

        assertEquals(Idea.Status.SUBMITTED, result.get("status"));
        assertNull(result.get("generationId"));
        verify(em).persist(any(IdeaImpl.class));
        verify(em).persist(any(CommandAuditLog.class));
        verify(em).flush();
    }

    @Test void ideaSubmissionWithAIGenerationSavesGenerationId() {
        var c = controller(true);
        when(user.getId()).thenReturn(42L);
        when(user.getUsername()).thenReturn("pm");
        when(ideaGenerationService.submit(anyLong(), any(UserRef.class), any(IdeaToRequirementsService.Idea.class)))
            .thenReturn("gen-123");

        var result = c.submit("alpha", new RequirementController.Submit("The system shall allow users to login with SSO."));

        assertEquals(Idea.Status.SUBMITTED, result.get("status"));
        assertEquals("gen-123", result.get("generationId"));
        verify(ideaGenerationService).submit(eq(1L), any(UserRef.class), any(IdeaToRequirementsService.Idea.class));
        verify(em).persist(any(IdeaImpl.class));
        verify(em).persist(any(CommandAuditLog.class));
        verify(em).flush();
    }

    @Test void ideaSubmissionWithAIGenerationFailureDoesNotFailRequest() {
        var c = controller(true);
        when(user.getId()).thenReturn(42L);
        when(user.getUsername()).thenReturn("pm");
        when(ideaGenerationService.submit(anyLong(), any(UserRef.class), any(IdeaToRequirementsService.Idea.class)))
            .thenThrow(new RuntimeException("AI service unavailable"));

        var result = c.submit("alpha", new RequirementController.Submit("The system shall allow users to login with SSO."));

        assertEquals(Idea.Status.SUBMITTED, result.get("status"));
        assertNull(result.get("generationId"));
        verify(em).persist(any(IdeaImpl.class));
        verify(em).persist(any(CommandAuditLog.class));
        verify(em).flush();
    }

    @Test void ideaSubmissionWithFileAndAIGeneration() {
        var c = controller(true);
        when(user.getId()).thenReturn(42L);
        when(user.getUsername()).thenReturn("pm");
        when(ideaGenerationService.submit(anyLong(), any(UserRef.class), any(IdeaToRequirementsService.Idea.class)))
            .thenReturn("gen-456");

        var result = c.submitFile("alpha", "The system shall", null);

        assertEquals(Idea.Status.SUBMITTED, result.get("status"));
        assertEquals("gen-456", result.get("generationId"));
    }

    @Test void onlyProjectManagersWithEditAccessCanSubmitBreakdowns() {
        var controller = controller(true);
        when(user.getWorkflowRole()).thenReturn(WorkflowRole.ENGINEER);
        assertThrows(AuthorizationException.class, () -> controller.reviewBreakdown("alpha", 8, null));
        when(user.getWorkflowRole()).thenReturn(WorkflowRole.PRODUCT_MANAGER);
        when(stakeholder.getStakeholderPermissions()).thenReturn(Set.of());
        assertThrows(AuthorizationException.class, () -> controller.reviewBreakdown("alpha", 8, null));
        verifyNoInteractions(em);
    }

    @Test void invalidOrRepeatedReviewsCannotChangeAnyProposal() {
        var controller = controller(true);
        when(user.getWorkflowRole()).thenReturn(WorkflowRole.PRODUCT_MANAGER);
        var idea = new IdeaImpl(1L, "The original idea", null, user);
        when(em.find(IdeaImpl.class, 8L)).thenReturn(idea);
        var draft = new RequirementImpl(idea, "The original proposal", "pm");
        ReflectionTestUtils.setField(draft, "id", 7L);
        idea.getDerivedRequirementsInternal().add(draft);
        var reject = new RequirementController.Decision(7L, BreakdownAction.REJECT, null, "out of scope", 0L);
        var valid = new RequirementController.BreakdownReview(List.of(reject));
        assertThrows(IllegalStateException.class, () -> controller.reviewBreakdown("alpha", 8, valid));

        idea.setStatus(Idea.Status.BREAKDOWN_REVIEW);
        for (var invalid : List.of(List.<RequirementController.Decision>of(), List.of(reject, reject),
                List.of(new RequirementController.Decision(42L, BreakdownAction.KEEP, null, null, null)),
                List.of(new RequirementController.Decision(7L, BreakdownAction.REVISE, " ", null, null)))) {
            assertThrows(IllegalArgumentException.class, () -> controller.reviewBreakdown(
                "alpha", 8, new RequirementController.BreakdownReview(invalid)));
        }
        var stale = new RequirementController.Decision(7L, BreakdownAction.KEEP, null, null, 12L);
        assertThrows(ResponseStatusException.class, () -> controller.reviewBreakdown(
            "alpha", 8, new RequirementController.BreakdownReview(List.of(stale))));
        assertEquals(Status.DRAFT, draft.getStatus());
        assertEquals(Idea.Status.BREAKDOWN_REVIEW, idea.getStatus());
        verify(em, never()).persist(any());

        controller.reviewBreakdown("alpha", 8, valid);
        assertEquals(Status.REJECTED, draft.getStatus());
        assertEquals("out of scope", draft.getBreakdownFeedback());
        assertEquals(Idea.Status.COMPLETE, idea.getStatus());
        assertThrows(IllegalStateException.class, () -> controller.reviewBreakdown("alpha", 8, valid));
        verify(em).persist(any(CommandAuditLog.class));
    }

    private RequirementImpl requirementInProject() {
        var requirement = new RequirementImpl(1L, null, "The system shall export.", "author");
        ReflectionTestUtils.setField(requirement, "id", 7L);
        when(em.find(RequirementImpl.class, 7L)).thenReturn(requirement);
        return requirement;
    }
}
