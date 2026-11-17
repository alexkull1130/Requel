package com.rreganjr.requel.requirements;

import com.rreganjr.requel.assistant.ai.IdeaGenerationService;
import com.rreganjr.requel.assistant.ai.IdeaRequirementsDrafts;
import com.rreganjr.requel.assistant.api.AssistantMessage;
import com.rreganjr.requel.project.Idea;
import com.rreganjr.requel.project.impl.IdeaImpl;
import com.rreganjr.requel.project.impl.RequirementImpl;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Callback implementation for IdeaGenerationService that creates requirement drafts
 * and updates the project idea status when AI generation completes.
 */
@Component
public class IdeaGenerationCallback implements IdeaGenerationService.GenerationCallback {

    private static final Logger log = LoggerFactory.getLogger(IdeaGenerationCallback.class);

    private final EntityManager em;

    public IdeaGenerationCallback(EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional
    public void onSuccess(String generationId, List<IdeaRequirementsDrafts.Draft> drafts,
            String summary, List<AssistantMessage> messages, List<IdeaRequirementsDrafts.Problem> problems) {
        Optional<IdeaImpl> linked = em.createQuery(
                "select i from IdeaImpl i where i.generationId = :id", IdeaImpl.class)
            .setParameter("id", generationId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList().stream().findFirst();
        if (linked.isEmpty() || linked.get().getStatus() != Idea.Status.SUBMITTED) return;
        if (drafts.isEmpty()) throw new IllegalStateException("Generation returned no proposals to review.");

        IdeaImpl idea = linked.get();
        String author = idea.getCreatedBy() == null ? "ai" : idea.getCreatedBy().getUsername();
        for (var draft : drafts) {
            // RequirementImpl derives the visible REQ code from the database-assigned ID.
            em.persist(new RequirementImpl(idea, draft.text(), author));
        }
        idea.setStatus(Idea.Status.BREAKDOWN_REVIEW);
    }

    @Override
    public void onFailure(String generationId, String errorSummary) {
        log.warn("Generation {} did not produce a breakdown: {}", generationId, errorSummary);
    }
}
