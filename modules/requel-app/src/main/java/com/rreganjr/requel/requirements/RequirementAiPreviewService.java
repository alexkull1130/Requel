package com.rreganjr.requel.requirements;

import com.rreganjr.platform.command.AuthorizationException;
import com.rreganjr.requel.project.Idea;
import com.rreganjr.requel.project.Project;
import com.rreganjr.requel.project.ProjectRepository;
import com.rreganjr.requel.project.Requirement;
import com.rreganjr.requel.project.StakeholderAuthorizationChecker;
import com.rreganjr.requel.project.exception.NoSuchProjectException;
import com.rreganjr.requel.project.impl.IdeaImpl;
import com.rreganjr.requel.project.impl.RequirementImpl;
import com.rreganjr.requel.service.auth.CurrentUserResolver;
import com.rreganjr.requel.user.WorkflowRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Validates a breakdown revision in a short transaction, returning no managed entities. */
@Service
public class RequirementAiPreviewService {
    private final ProjectRepository projects;
    private final CurrentUserResolver users;
    @PersistenceContext private EntityManager em;

    /** Build the snapshot boundary with project lookup and authenticated-user resolution. */
    public RequirementAiPreviewService(ProjectRepository projects, CurrentUserResolver users) {
        this.projects = projects;
        this.users = users;
    }

    /**
     * Check PM/edit authorization, ownership, review state and optimistic versions before AI.
     * Zero is a valid JPA version. Only copied values escape this read-only transaction.
     */
    @Transactional(readOnly = true)
    public RequirementAiPreviewSnapshot snapshot(String projectName, long ideaId, long requirementId,
            String feedback, Long expectedIdeaVersion, Long expectedRequirementVersion) {
        if (feedback == null || feedback.isBlank())
            throw new IllegalArgumentException("Enter feedback before requesting an AI revision.");
        if (feedback.length() > 10000)
            throw new IllegalArgumentException("Revision feedback must be at most 10,000 characters.");
        if (expectedIdeaVersion == null || expectedIdeaVersion < 0
                || expectedRequirementVersion == null || expectedRequirementVersion < 0)
            throw new IllegalArgumentException("Nonnegative idea and requirement versions are required.");

        var user = users.resolve();
        if (user == null) throw new AuthorizationException("Sign in before requesting a revision.");
        Project project;
        try {
            project = projects.findProjectByName(projectName);
        } catch (NoSuchProjectException e) {
            throw missing();
        }
        if (project == null) throw missing();
        StakeholderAuthorizationChecker.require(project, user, Project.class, "Edit");
        if (user.getWorkflowRole() != WorkflowRole.PRODUCT_MANAGER)
            throw new AuthorizationException("Only product managers can request breakdown revisions.");

        IdeaImpl idea = em.find(IdeaImpl.class, ideaId);
        RequirementImpl requirement = em.find(RequirementImpl.class, requirementId);
        if (idea == null || requirement == null
                || !Objects.equals(idea.getProjectId(), project.getId())
                || !Objects.equals(requirement.getProjectId(), project.getId())
                || requirement.getIdea() == null
                || !Objects.equals(requirement.getIdea().getId(), idea.getId())) throw missing();
        if (idea.getStatus() != Idea.Status.BREAKDOWN_REVIEW
                || requirement.getStatus() != Requirement.Status.DRAFT)
            throw new IllegalStateException("This proposal is no longer awaiting breakdown review. Reload the idea.");
        if (idea.getVersion() != expectedIdeaVersion
                || requirement.getVersion() != expectedRequirementVersion)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This idea or proposal changed. Reload before requesting a revision.");

        return new RequirementAiPreviewSnapshot(project.getId(), idea.getId(), requirement.getId(),
            idea.getVersion(), requirement.getVersion(), idea.getText(), requirement.getShallText(),
            feedback.strip(), user.getId(), user.getUsername());
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal not found in this idea and project.");
    }

    /** Immutable, detached input for a preview; no entity or lazy association crosses this boundary. */
    public record RequirementAiPreviewSnapshot(Long projectId, Long ideaId, Long requirementId,
            long ideaVersion, long requirementVersion, String ideaText, String shallText,
            String feedback, Long userId, String username) {
        /** Reject incomplete internal snapshots rather than sending ambiguous identity/context to AI. */
        public RequirementAiPreviewSnapshot {
            Objects.requireNonNull(projectId); Objects.requireNonNull(ideaId);
            Objects.requireNonNull(requirementId); Objects.requireNonNull(ideaText);
            Objects.requireNonNull(shallText); Objects.requireNonNull(feedback);
            Objects.requireNonNull(userId); Objects.requireNonNull(username);
        }
    }
}
