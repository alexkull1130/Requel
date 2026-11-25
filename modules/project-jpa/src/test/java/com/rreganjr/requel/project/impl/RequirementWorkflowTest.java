package com.rreganjr.requel.project.impl;

import com.rreganjr.requel.project.Requirement.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequirementWorkflowTest {
    private RequirementImpl draft() {
        return new RequirementImpl(new IdeaImpl(1L, "An exportable report", null, null),
            "The system shall export reports.", "author");
    }
    private void passAll(RequirementImpl r) {
        for (Department d : Department.values()) r.review(d, Result.PASSED, "Reviewed", "reviewer", d.name());
    }
    @Test void approvalRequiresEveryDepartmentToPass() {
        var r = draft();
        r.review(Department.QUALITY, Result.PASSED, "", "analyst", "Analyst");
        assertThrows(IllegalStateException.class, r::approve);
        r.review(Department.FEASIBILITY, Result.PASSED, "", "engineer", "Engineer");
        r.review(Department.TESTABILITY, Result.FAILED, "Not measurable", "tester", "TestEngineer");
        assertFalse(r.allReviewsPassed());
        assertThrows(IllegalStateException.class, r::approve);
        r.review(Department.TESTABILITY, Result.PASSED, "Measurable now", "tester", "TestEngineer");
        r.approve();
        assertEquals(Status.APPROVED, r.getStatus());
    }
    @Test void approvedContentAndIdentifierAreLockedUntilExplicitReopen() {
        var r = draft(); r.setRequirementCode("REQ-ORIGINAL"); passAll(r); r.approve();
        var updatedAt = r.getUpdatedAt();
        var error = assertThrows(IllegalStateException.class,
            () -> r.revise("The system shall export reports as PDF."));
        assertTrue(error.getMessage().contains("Reopen"));
        assertThrows(IllegalStateException.class, () -> r.setRequirementCode("REQ-CHANGED"));
        assertThrows(IllegalStateException.class, () -> r.revise(r.getShallText()));
        assertThrows(IllegalStateException.class, () -> r.setRequirementCode(null));
        assertEquals("The system shall export reports.", r.getShallText());
        assertEquals("REQ-ORIGINAL", r.getRequirementCode());
        assertEquals(Status.APPROVED, r.getStatus());
        assertEquals(updatedAt, r.getUpdatedAt());
        assertTrue(r.allReviewsPassed());

        r.reopen();
        r.revise("The system shall export reports as PDF.");
        r.setRequirementCode("REQ-REOPENED");
        assertEquals(Status.DRAFT, r.getStatus());
        assertEquals("REQ-REOPENED", r.getRequirementCode());
        assertTrue(r.getReviews().isEmpty());
        assertThrows(IllegalStateException.class, r::approve);
    }
    @Test void unchangedUnapprovedContentPreservesReviews() {
        var r = draft(); passAll(r);
        r.revise(r.getShallText());
        assertTrue(r.allReviewsPassed());
        assertEquals(Status.IN_REVIEW, r.getStatus());
    }
    @Test void changingUnapprovedContentStillInvalidatesReviews() {
        var r = draft(); passAll(r);
        r.revise("The system shall export reports as PDF.");
        assertEquals(Status.DRAFT, r.getStatus());
        assertTrue(r.getReviews().isEmpty());
    }
    @Test void publishedContentAndIdentifierStayLocked() throws Exception {
        var r = draft(); r.setRequirementCode("REQ-PUBLISHED"); passAll(r); r.approve();
        // Publishing is not connected yet; represent a persisted published row without adding a transition API.
        var status = RequirementImpl.class.getDeclaredField("status");
        status.setAccessible(true); status.set(r, Status.PUBLISHED);
        var updatedAt = r.getUpdatedAt();
        assertThrows(IllegalStateException.class, () -> r.revise("A changed statement"));
        assertThrows(IllegalStateException.class, () -> r.setRequirementCode("REQ-CHANGED"));
        assertThrows(IllegalStateException.class, r::reopen);
        assertEquals(Status.PUBLISHED, r.getStatus());
        assertEquals("The system shall export reports.", r.getShallText());
        assertEquals("REQ-PUBLISHED", r.getRequirementCode());
        assertEquals(updatedAt, r.getUpdatedAt());
        assertTrue(r.allReviewsPassed());
    }
    @Test void reopeningApprovalRequiresFreshReviews() {
        var r = draft(); passAll(r); r.approve(); r.reopen();
        assertEquals(Status.DRAFT, r.getStatus());
        assertFalse(r.allReviewsPassed());
        assertThrows(IllegalArgumentException.class, () -> r.review(Department.QUALITY, Result.NOT_REVIEWED, "", "user", "Analyst"));
    }
}
