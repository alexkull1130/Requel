package com.rreganjr.requel.requirements;

import com.rreganjr.AbstractIntegrationTestCase;
import com.rreganjr.requel.project.Requirement.Department;
import com.rreganjr.requel.project.Requirement.Result;
import com.rreganjr.requel.project.Requirement.Status;
import com.rreganjr.requel.project.impl.RequirementImpl;
import com.rreganjr.requel.user.WorkflowRole;
import com.rreganjr.requel.user.impl.UserImpl;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Locked edit attempts must not change persisted content, versions, reviews or audit history. */
class RequirementEditLockIT extends AbstractIntegrationTestCase {
    @Autowired private RequirementController controller;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EntityManager em;

    @AfterEach void signOut() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"APPROVED", "PUBLISHED"})
    void lockedRowsStayUnchangedAndOnlyApprovedRowsCanBeExplicitlyReopened(Status status) throws Exception {
        String name = "Edit lock " + status + " " + System.nanoTime();
        var user = getUserRepository().findUserByUsername("project");
        var project = getProjectCommandFactory().newEditProjectCommand();
        project.setEditedBy(user);
        project.setName(name);
        project.setText("Verify approved and published requirement edit locks.");
        project.setOrganizationName(name);
        getCommandHandler().execute(project);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("project", null, List.of()));
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(transaction ->
            em.find(UserImpl.class, user.getId()).setWorkflowRole(WorkflowRole.PRODUCT_MANAGER));
        var created = controller.create(name,
            new RequirementController.Create(null, "The system shall export reports.", "REQ-ORIGINAL"));
        long id = (Long) created.get("id");
        long draftVersion = (Long) created.get("version");
        tx.executeWithoutResult(transaction -> {
            var requirement = em.find(RequirementImpl.class, id);
            for (Department department : Department.values())
                requirement.review(department, Result.PASSED, "Reviewed", "reviewer", "Reviewer");
            requirement.approve();
            // Publishing is not connected to Jama yet; simulate an already-published stored row.
            if (status == Status.PUBLISHED) ReflectionTestUtils.setField(requirement, "status", status);
        });
        var locked = controller.get(name, id);
        long lockedVersion = (Long) locked.get("version");
        assertEquals(status, locked.get("status"));
        assertTrue(lockedVersion > draftVersion);

        // A form opened before approval cannot write after the approval transaction commits.
        var stale = assertThrows(ResponseStatusException.class, () -> controller.edit(name, id,
            new RequirementController.Edit("A stale edit", draftVersion, "REQ-STALE")));
        assertEquals(409, stale.getStatusCode().value());
        assertThrows(IllegalStateException.class, () -> controller.edit(name, id,
            new RequirementController.Edit("A changed statement", lockedVersion, "REQ-CHANGED")));
        assertThrows(IllegalStateException.class, () -> controller.edit(name, id,
            new RequirementController.Edit((String) locked.get("shallText"), lockedVersion, "REQ-ID-ONLY")));
        assertEquals(locked, controller.get(name, id));
        assertEquals(0L, em.createQuery("select count(a) from CommandAuditLog a where a.commandType = 'EditRequirement'"
            + " and a.projectId = :project", Long.class)
            .setParameter("project", project.getProject().getId()).getSingleResult());

        if (status == Status.APPROVED) {
            var reopened = controller.reopen(name, id, new RequirementController.Version(lockedVersion));
            assertEquals(Status.DRAFT, reopened.get("status"));
            var saved = controller.edit(name, id, new RequirementController.Edit(
                "The system shall export signed reports.", (Long) reopened.get("version"), "REQ-REOPENED"));
            var reloaded = controller.get(name, id);
            assertEquals(saved, reloaded);
            assertEquals(Status.DRAFT, reloaded.get("status"));
            assertEquals("The system shall export signed reports.", reloaded.get("shallText"));
            assertEquals("REQ-REOPENED", reloaded.get("requirementId"));
            assertTrue(((List<?>) reloaded.get("reviews")).isEmpty());
        } else {
            assertThrows(IllegalStateException.class,
                () -> controller.reopen(name, id, new RequirementController.Version(lockedVersion)));
            assertEquals(locked, controller.get(name, id));
        }
    }
}
