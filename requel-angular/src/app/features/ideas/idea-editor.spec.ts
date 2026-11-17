import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { ConfirmationService, MessageService } from 'primeng/api';
import { AnnouncerService } from '../../core/announcer.service';
import { CommandService } from '../../core/command.service';
import { EventStreamService } from '../../core/event-stream.service';
import { PermissionService } from '../../core/permission.service';
import { ProjectService } from '../../core/project.service';
import { RequirementService } from '../../core/requirement.service';
import { IdeaDto } from '../../models/requirement';
import { IdeaEditorComponent } from './idea-editor';

describe('Idea editor breakdown review', () => {
  const reviewBreakdown = vi.fn();
  const confirm = vi.fn();
  let editor: IdeaEditorComponent;
  let idea: IdeaDto;
  beforeEach(() => {
    reviewBreakdown.mockReset();
    confirm.mockReset();
    TestBed.configureTestingModule({ providers: [
      ...[ActivatedRoute, Router, AnnouncerService, CommandService, EventStreamService,
        PermissionService, ProjectService].map(provide => ({ provide, useValue: {} })),
      { provide: MessageService, useValue: { add: vi.fn() } },
      { provide: ConfirmationService, useValue: { confirm } },
      { provide: RequirementService, useValue: { reviewBreakdown } }
    ] });
    editor = TestBed.runInInjectionContext(() => new IdeaEditorComponent());
    editor.projectName = 'alpha';
    editor.ideaId = 5;
    idea = { id: 5, version: 1, externalId: null, sourceText: 'Export reports', status: 'BREAKDOWN_REVIEW',
      createdBy: 'pm', createdAt: '', generationId: 'g1', canReviewBreakdown: true,
      derivedRequirements: [10, 11].map(id => ({ id, version: 0, requirementId: 'REQ-' + id,
        shallText: 'Export a report', status: 'DRAFT', breakdownAction: null, feedback: null })) };
    editor.idea.set(idea);
  });

  it('requires permission and one complete decision per proposal', () => {
    expect(editor.canSubmitBreakdown()).toBe(false);
    editor.rejectAll();
    expect(editor.canSubmitBreakdown()).toBe(true);
    editor.breakdownDecisions[11] = 'REVISE';
    expect(editor.canSubmitBreakdown()).toBe(false);
    editor.revisedTexts[11] = 'Export CSV';
    editor.idea.set({ ...idea, canReviewBreakdown: false });
    editor.confirmBreakdownReview();
    expect(confirm).not.toHaveBeenCalled();
  });

  it('recounts the decisions when the PM returns to the review before submitting', () => {
    editor.rejectAll();
    editor.confirmBreakdownReview();
    expect(confirm.mock.lastCall?.[0].message).toContain('Keep 0, reject 2, revise 0.');
    // Dismissing the confirmation leaves its accept callback uncalled.
    editor.breakdownDecisions[10] = 'KEEP';
    editor.breakdownDecisions[11] = 'REVISE';
    editor.revisedTexts[11] = 'Export signed CSV';
    editor.confirmBreakdownReview();
    expect(confirm.mock.lastCall?.[0].message).toContain('Keep 1, reject 0, revise 1.');
    expect(reviewBreakdown).not.toHaveBeenCalled();
  });

  it('posts only on confirmation and preserves every input for retry', async () => {
    editor.rejectAll();
    editor.breakdownDecisions[10] = 'REVISE';
    editor.revisedTexts[10] = 'Export CSV';
    editor.feedback[11] = 'Out of scope';
    editor.confirmBreakdownReview();
    expect(reviewBreakdown).not.toHaveBeenCalled();
    reviewBreakdown.mockRejectedValueOnce(new Error('offline'));
    confirm.mock.calls[0][0].accept();
    await vi.waitFor(() => expect(editor.errorMessage()).toContain('offline'));
    expect(editor.canSubmitBreakdown()).toBe(true);
    reviewBreakdown.mockResolvedValueOnce({ ...idea, status: 'COMPLETE', canReviewBreakdown: false });
    editor.confirmBreakdownReview();
    confirm.mock.calls[1][0].accept();
    await vi.waitFor(() => expect(editor.idea()?.status).toBe('COMPLETE'));
    expect(reviewBreakdown.mock.calls[1]).toEqual(reviewBreakdown.mock.calls[0]);
    expect(reviewBreakdown).toHaveBeenCalledWith('alpha', 5, [
      { requirementId: 10, version: 0, action: 'REVISE', revisedText: 'Export CSV', feedback: undefined },
      { requirementId: 11, version: 0, action: 'REJECT', revisedText: undefined, feedback: 'Out of scope' }
    ]);
  });
});
