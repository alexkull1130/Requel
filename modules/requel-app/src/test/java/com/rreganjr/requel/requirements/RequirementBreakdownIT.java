package com.rreganjr.requel.requirements;

import com.fasterxml.jackson.databind.node.NullNode;
import com.rreganjr.AbstractIntegrationTestCase;
import com.rreganjr.requel.assistant.ai.*;
import com.rreganjr.requel.project.Idea;
import com.rreganjr.requel.project.Requirement;
import com.rreganjr.requel.project.impl.RequirementImpl.BreakdownAction;
import com.rreganjr.requel.user.WorkflowRole;
import com.rreganjr.requel.user.impl.UserImpl;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise submission, generation, review and reload across real database transactions. */
@TestPropertySource(properties = "requel.ai.enabled=true")
class RequirementBreakdownIT extends AbstractIntegrationTestCase {
    @org.springframework.test.context.bean.override.mockito.MockitoBean(name = "stubAiClient")
    private AiAnalysisClient provider;

    private static AiFindingDraft proposal(String name) {
        return new AiFindingDraft("GOAL", null, 0.8, List.of(), "The system shall " + name + ".",
            null, List.of(), Map.of(ReviewResultMapper.SUGGESTED_ENTITY_NAME, name));
    }

    @Autowired private RequirementController controller;
    @Autowired private IdeaGenerationService generation;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EntityManager em;

    @AfterEach void signOut() { SecurityContextHolder.clearContext(); }

    @Test void managerCanReviewGeneratedProposalsAndReloadTheirDecisions() throws Exception {
        org.mockito.Mockito.when(provider.analyze(org.mockito.ArgumentMatchers.any())).thenReturn(
            new AiAnalysisResponse("Report exports", NullNode.getInstance(),
                List.of(proposal("Export PDF"), proposal("Export CSV"), proposal("Export XML")),
                List.of(), AiUsage.noop("fixture", Duration.ZERO), Map.of()));
        String name = "Report exports " + System.nanoTime();
        var user = getUserRepository().findUserByUsername("project");
        var project = getProjectCommandFactory().newEditProjectCommand();
        project.setEditedBy(user);
        project.setName(name);
        project.setText("Test the requirement review.");
        project.setOrganizationName(name);
        getCommandHandler().execute(project);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("project", null, List.of()));
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
            em.find(UserImpl.class, user.getId()).setWorkflowRole(WorkflowRole.PRODUCT_MANAGER));

        var submitted = controller.submit(name, new RequirementController.Submit("Support PDF, CSV and XML exports."));
        long id = (long) submitted.get("id");
        String generationId = (String) submitted.get("generationId");
        assertNotNull(generationId);
        // The test profile queues work without starting background threads.
        generation.run(generationId);
        assertEquals("DRAFTED", generation.get(generationId).orElseThrow().status());
        var review = controller.getIdea(name, id);
        assertEquals(Idea.Status.BREAKDOWN_REVIEW, review.get("status"));
        assertEquals(true, review.get("canReviewBreakdown"));
        var drafts = rows(review);
        assertEquals(3, drafts.size());
        assertEquals(3, drafts.stream().map(r -> r.get("requirementId")).distinct().count());
        var actions = List.of(BreakdownAction.KEEP, BreakdownAction.REJECT, BreakdownAction.REVISE);
        var decisions = java.util.stream.IntStream.range(0, drafts.size()).mapToObj(index ->
            new RequirementController.Decision((Long) drafts.get(index).get("id"), actions.get(index),
                index == 2 ? "The system shall export signed XML." : null,
                "Comment " + index, (Long) drafts.get(index).get("version"))).toList();
        controller.reviewBreakdown(name, id, new RequirementController.BreakdownReview(decisions));

        var completed = controller.getIdea(name, id);
        assertEquals(Idea.Status.COMPLETE, completed.get("status"));
        assertEquals(false, completed.get("canReviewBreakdown"));
        var saved = rows(completed);
        assertEquals(drafts.stream().map(r -> r.get("id")).toList(), saved.stream().map(r -> r.get("id")).toList());
        assertEquals(List.of(Requirement.Status.DRAFT, Requirement.Status.REJECTED, Requirement.Status.DRAFT),
            saved.stream().map(r -> r.get("status")).toList());
        assertEquals(drafts.get(0).get("shallText"), saved.get(0).get("shallText"));
        assertEquals("The system shall export signed XML.", saved.get(2).get("shallText"));
        assertEquals(actions, saved.stream().map(r -> r.get("breakdownAction")).toList());
        assertEquals(List.of("Comment 0", "Comment 1", "Comment 2"), saved.stream().map(r -> r.get("feedback")).toList());
        assertEquals(1L, em.createQuery("select count(a) from CommandAuditLog a where a.commandType = 'ReviewBreakdown'"
            + " and a.projectId = :project and a.userId = :user", Long.class)
            .setParameter("project", project.getProject().getId()).setParameter("user", user.getId()).getSingleResult());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> idea) {
        return (List<Map<String, Object>>) idea.get("derivedRequirements");
    }
}
