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
import { of } from 'rxjs';
import { IdeaEditorComponent } from './idea-editor';

describe('Idea editor breakdown review', () => {
  const reviewBreakdown = vi.fn();
  const previewBreakdownRevision = vi.fn();
  const confirm = vi.fn();
  let editor: IdeaEditorComponent;
  let idea: IdeaDto;
  beforeEach(() => {
    reviewBreakdown.mockReset();
    previewBreakdownRevision.mockReset();
    confirm.mockReset();
    TestBed.configureTestingModule({ providers: [
      ...[ActivatedRoute, Router, AnnouncerService, CommandService, EventStreamService,
        PermissionService, ProjectService].map(provide => ({ provide, useValue: {} })),
      { provide: MessageService, useValue: { add: vi.fn() } },
      { provide: ConfirmationService, useValue: { confirm } },
      { provide: RequirementService, useValue: { reviewBreakdown, previewBreakdownRevision } }
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


describe('Idea editor AI revision preview', () => {
  const previewBreakdownRevision = vi.fn();
  let editor: IdeaEditorComponent;
  let idea: IdeaDto;
  const deferred = <T>() => { let resolve!: (value: T) => void; let reject!: (reason?: unknown) => void; const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; }); return { promise, resolve, reject }; };

  beforeEach(() => {
    previewBreakdownRevision.mockReset();
    TestBed.configureTestingModule({ providers: [
      ...[ActivatedRoute, Router, AnnouncerService, CommandService, EventStreamService, PermissionService, ProjectService].map(provide => ({ provide, useValue: {} })),
      { provide: MessageService, useValue: { add: vi.fn() } },
      { provide: ConfirmationService, useValue: { confirm: vi.fn() } },
      { provide: RequirementService, useValue: { previewBreakdownRevision } }
    ] });
    editor = TestBed.runInInjectionContext(() => new IdeaEditorComponent());
    editor.projectName = 'alpha'; editor.ideaId = 5;
    idea = { id: 5, version: 3, externalId: null, sourceText: 'Export reports', status: 'BREAKDOWN_REVIEW', createdBy: 'pm', createdAt: '', generationId: 'g1', canReviewBreakdown: true,
      derivedRequirements: [10, 11].map(id => ({ id, version: id === 10 ? 7 : 8, requirementId: 'REQ-' + id, shallText: 'Export a report', status: 'DRAFT', breakdownAction: null, feedback: null })) };
    editor.idea.set(idea);
  });

  function revise(reqIndex = 0, manual = 'Manual draft', feedback = 'Make it measurable') {
    const req = idea.derivedRequirements![reqIndex];
    editor.onBreakdownActionChange(req, 'REVISE');
    editor.onManualTextChange(req, manual);
    editor.onFeedbackChange(req, feedback);
    return req;
  }

  it('requests the API with the exact preview contract', async () => {
    const req = revise();
    previewBreakdownRevision.mockResolvedValueOnce({ revisedShall: 'AI draft', ideaVersion: 3, requirementVersion: 7 });
    await editor.requestAiRevision(req);
    expect(previewBreakdownRevision).toHaveBeenCalledWith('alpha', 5, 10, 'Make it measurable', 3, 7);
  });

  it('shows a suggestion separately and changes manual text only on explicit accept or discard', async () => {
    const req = revise();
    previewBreakdownRevision.mockResolvedValueOnce({ revisedShall: 'AI draft', ideaVersion: 3, requirementVersion: 7 });
    await editor.requestAiRevision(req);
    expect(editor.revisedTexts[10]).toBe('Manual draft');
    expect(editor.aiPreviewState(req)?.suggestion).toBe('AI draft');
    editor.discardAiSuggestion(req);
    expect(editor.revisedTexts[10]).toBe('Manual draft');
    previewBreakdownRevision.mockResolvedValueOnce({ revisedShall: 'Accepted AI draft', ideaVersion: 3, requirementVersion: 7 });
    await editor.requestAiRevision(req);
    editor.acceptAiSuggestion(req);
    expect(editor.revisedTexts[10]).toBe('Accepted AI draft');
    expect(editor.aiPreviewState(req)).toBeNull();
  });

  it('preserves manual input after an error and allows retry', async () => {
    const req = revise();
    previewBreakdownRevision.mockRejectedValueOnce(new Error('offline'));
    await editor.requestAiRevision(req);
    expect(editor.revisedTexts[10]).toBe('Manual draft');
    expect(editor.aiPreviewState(req)?.error).toContain('offline');
    previewBreakdownRevision.mockResolvedValueOnce({ revisedShall: 'AI draft', ideaVersion: 3, requirementVersion: 7 });
    await editor.requestAiRevision(req);
    expect(editor.aiPreviewState(req)?.suggestion).toBe('AI draft');
  });

  it('prevents duplicate requests and blocks final submission while a preview is pending', async () => {
    const req = revise(); editor.onBreakdownActionChange(idea.derivedRequirements![1], 'REJECT'); const wait = deferred<{ revisedShall: string; ideaVersion: number; requirementVersion: number }>();
    previewBreakdownRevision.mockReturnValueOnce(wait.promise);
    const first = editor.requestAiRevision(req); await editor.requestAiRevision(req);
    expect(previewBreakdownRevision).toHaveBeenCalledTimes(1);
    expect(editor.canSubmitBreakdown()).toBe(false);
    wait.resolve({ revisedShall: 'AI draft', ideaVersion: 3, requirementVersion: 7 }); await first;
  });

  it('prevents another request after edits until the original settles and invalidates mismatched response versions', async () => {
    const req = revise();
    const oldRequest = deferred<{ revisedShall: string; ideaVersion: number; requirementVersion: number }>();
    previewBreakdownRevision.mockReturnValueOnce(oldRequest.promise);
    const first = editor.requestAiRevision(req);
    editor.onFeedbackChange(req, 'Temporary feedback');
    editor.onFeedbackChange(req, 'Make it measurable');
    await editor.requestAiRevision(req);
    expect(previewBreakdownRevision).toHaveBeenCalledTimes(1);
    expect(editor.hasPendingAiPreview()).toBe(true);
    oldRequest.resolve({ revisedShall: 'Old response', ideaVersion: 3, requirementVersion: 7 }); await first;
    expect(editor.aiPreviewState(req)).toBeNull();

    previewBreakdownRevision.mockResolvedValueOnce({ revisedShall: 'Wrong version', ideaVersion: 4, requirementVersion: 7 });
    await editor.requestAiRevision(req);
    expect(editor.aiPreviewState(req)).toBeNull();
  });

  it('ignores stale results after manual, version, and idea changes without affecting unrelated proposals', async () => {
    const req10 = revise(0); const req11 = revise(1, 'Other manual', 'Other feedback');
    const wait = deferred<{ revisedShall: string; ideaVersion: number; requirementVersion: number }>();
    previewBreakdownRevision.mockReturnValueOnce(wait.promise);
    const pending = editor.requestAiRevision(req10);
    editor.onManualTextChange(req10, 'Edited while waiting');
    wait.resolve({ revisedShall: 'Stale AI draft', ideaVersion: 3, requirementVersion: 7 }); await pending;
    expect(editor.aiPreviewState(req10)).toBeNull();
    expect(editor.revisedTexts[10]).toBe('Edited while waiting');
    expect(editor.revisedTexts[11]).toBe('Other manual');

    const next = deferred<{ revisedShall: string; ideaVersion: number; requirementVersion: number }>();
    previewBreakdownRevision.mockReturnValueOnce(next.promise);
    const pendingVersion = editor.requestAiRevision(req11);
    editor.idea.set({ ...idea, version: 4 });
    next.resolve({ revisedShall: 'Stale version draft', ideaVersion: 3, requirementVersion: 8 }); await pendingVersion;
    expect(editor.aiPreviewState(req11)).toBeNull();
    editor.idea.set({ ...idea, id: 6 });
    expect(editor.aiPreviewState(req10)).toBeNull();
  });
});

describe('RequirementService AI revision preview', () => {
  it('posts the API path, project query, and versioned body', async () => {
    const post = vi.fn((_url?: unknown, _body?: unknown, _options?: unknown) => of({ revisedShall: 'Revised', ideaVersion: 3, requirementVersion: 7 }));
    const service = new RequirementService({ post } as any);
    await service.previewBreakdownRevision('alpha', 5, 10, 'Clarify scope', 3, 7);
    expect(post).toHaveBeenCalledWith('/api/requirements/ideas/5/requirements/10/ai-preview',
      { feedback: 'Clarify scope', expectedIdeaVersion: 3, expectedRequirementVersion: 7 },
      expect.objectContaining({ params: expect.anything() }));
    expect((post.mock.calls[0]![2] as any).params.get('projectName')).toBe('alpha');
  });
});

// Exercise the real template as well as the component methods, so missing buttons/bindings
// cannot pass the behavioral tests above.
describe('Rendered AI revision controls', () => {
  afterEach(() => vi.restoreAllMocks());
  it('lets the PM request, inspect, and accept wording through the actual template', async () => {
    const preview = vi.fn().mockResolvedValue({ revisedShall: 'AI-generated wording', ideaVersion: 3, requirementVersion: 7 });
    TestBed.configureTestingModule({ imports: [IdeaEditorComponent], providers: [
      ...[ActivatedRoute, Router, CommandService, ProjectService].map(provide => ({ provide, useValue: {} })),
      { provide: AnnouncerService, useValue: { announce: vi.fn(), announceThrottled: vi.fn() } },
      { provide: EventStreamService, useValue: { removeSubscription: vi.fn() } },
      { provide: PermissionService, useValue: { canEdit: () => false, canDelete: () => false } },
      { provide: MessageService, useValue: { add: vi.fn() } },
      { provide: RequirementService, useValue: { previewBreakdownRevision: preview } }
    ] });
    vi.spyOn(IdeaEditorComponent.prototype, 'ngOnInit').mockImplementation(() => {});
    const fixture = TestBed.createComponent(IdeaEditorComponent);
    const component = fixture.componentInstance;
    const proposal = { id: 10, version: 7, requirementId: 'REQ-10', shallText: 'Original wording', status: 'DRAFT' as const, breakdownAction: null, feedback: null };
    component.projectName = 'alpha';
    component.isNew.set(false);
    component.loading.set(false);
    component.idea.set({ id: 5, version: 3, externalId: null, sourceText: 'Export reports', status: 'BREAKDOWN_REVIEW', createdBy: 'pm', createdAt: '', generationId: 'g1', canReviewBreakdown: true, derivedRequirements: [proposal] });
    component.onBreakdownActionChange(proposal, 'REVISE');
    component.onManualTextChange(proposal, 'My manual wording');
    component.onFeedbackChange(proposal, 'Make it measurable');
    fixture.detectChanges();
    await fixture.whenStable();
    const root: HTMLElement = fixture.nativeElement;
    const request = root.querySelector<HTMLButtonElement>('[data-testid="ai-revision-request-10"] button')!;
    expect(request).not.toBeNull();
    expect(request.disabled).toBe(false);
    request.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(preview).toHaveBeenCalledWith('alpha', 5, 10, 'Make it measurable', 3, 7);
    expect(root.querySelector('[data-testid="ai-revision-suggestion-10"]')?.textContent).toContain('AI-generated wording');
    const textarea = root.querySelector<HTMLTextAreaElement>('[aria-label="Revised statement for REQ-10"]')!;
    expect(textarea.value).toBe('My manual wording');
    const accept = [...root.querySelectorAll('button')].find(button => button.textContent?.includes('Accept wording'))!;
    accept.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(component.revisedTexts[10]).toBe('AI-generated wording');
    expect(root.querySelector('[data-testid="ai-revision-suggestion-10"]')).toBeNull();
  });
});
