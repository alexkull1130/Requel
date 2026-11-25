import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { EMPTY } from 'rxjs';
import { MessageService } from 'primeng/api';
import { RequirementEditorComponent } from './requirement-editor';
import { RequirementService } from '../../core/requirement.service';
import { CommandService } from '../../core/command.service';
import { ProjectService } from '../../core/project.service';
import { PermissionService } from '../../core/permission.service';
import { EventStreamService } from '../../core/event-stream.service';
import { AnnouncerService } from '../../core/announcer.service';
import { RequirementDto, RequirementStatus, ReviewDepartment } from '../../models/requirement';

describe('Requirement review entry points', () => {
  function render(status: RequirementStatus, departments: ReviewDepartment[] = ['QUALITY'],
                  canApproveOrReopen = false, allReviewsPassed = false, canEdit = true) {
    const requirementService = {
      createRequirement: vi.fn(),
      updateRequirement: vi.fn(),
    };
    TestBed.configureTestingModule({
      imports: [RequirementEditorComponent],
      providers: [
        provideRouter([]), provideNoopAnimations(),
        { provide: ActivatedRoute, useValue: { paramMap: EMPTY } },
        { provide: RequirementService, useValue: requirementService },
        { provide: CommandService, useValue: {} },
        { provide: ProjectService, useValue: { notifyTreeChanged: vi.fn() } },
        { provide: PermissionService, useValue: {
          canEdit: () => canEdit, canDelete: () => true, getRoles: () => [],
        } },
        { provide: EventStreamService, useValue: {
          events$: EMPTY, addSubscription: vi.fn(), removeSubscription: vi.fn(),
        } },
        { provide: AnnouncerService, useValue: {} },
        { provide: MessageService, useValue: { add: vi.fn() } },
      ],
    });
    const fixture = TestBed.createComponent(RequirementEditorComponent);
    const component = fixture.componentInstance;
    // Exercise the actual detail template without fetching a record or ancillary panels.
    component.isNew.set(false);
    component.loading.set(false);
    component.requirement.set({
      id: 1, version: 0, requirementId: 'REQ-1', shallText: 'The system shall export reports.',
      status, ideaId: null, ideaName: null, jamaItemId: null, createdBy: 'author',
      createdAt: '2026-11-16T12:00:00Z', updatedAt: '2026-11-16T12:00:00Z',
      reviews: [], allReviewsPassed, isApproved: status === 'APPROVED',
      allowedReviewDepartments: departments, canApproveOrReopen,
      isPublished: status === 'PUBLISHED',
    } satisfies RequirementDto);
    fixture.detectChanges();
    return { fixture, requirementService };
  }

  it.each<RequirementStatus>(['DRAFT', 'IN_REVIEW', 'REJECTED'])('allows department review from %s', status => {
    const { fixture } = render(status);
    expect(fixture.nativeElement.querySelectorAll('p-button[data-testid^="req-review-"]').length).toBe(1);
    const button = fixture.nativeElement.querySelector('[data-testid="req-review-quality"] button') as HTMLButtonElement;
    button.click();
    expect(fixture.componentInstance.reviewDialogVisible()).toBe(true);
    expect(fixture.componentInstance.reviewDialogDept()).toBe('QUALITY');
  });

  it.each<RequirementStatus>(['APPROVED', 'PUBLISHED'])('does not offer new reviews while %s', status => {
    const { fixture } = render(status);
    expect(fixture.nativeElement.querySelectorAll('p-button[data-testid^="req-review-"]').length).toBe(0);
  });

  it('does not expose review controls without a reviewer role', () => {
    const { fixture } = render('DRAFT', []);
    expect(fixture.nativeElement.querySelectorAll('p-button[data-testid^="req-review-"]').length).toBe(0);
  });

  it.each<ReviewDepartment>(['FEASIBILITY', 'TESTABILITY'])('offers only the %s review', department => {
    const { fixture } = render('DRAFT', [department]);
    const buttons = fixture.nativeElement.querySelectorAll('p-button[data-testid^="req-review-"]');
    expect(buttons.length).toBe(1);
    expect(buttons[0].getAttribute('data-testid')).toBe(`req-review-${department.toLowerCase()}`);
  });

  it('offers final approval and zero department reviews to a Product Manager', () => {
    const { fixture } = render('IN_REVIEW', [], true, true);
    expect(fixture.nativeElement.querySelector('[data-testid="req-approve"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('p-button[data-testid^="req-review-"]').length).toBe(0);
  });

  it('offers all three department reviews to a system administrator', () => {
    const { fixture } = render('DRAFT', ['QUALITY', 'FEASIBILITY', 'TESTABILITY']);
    expect(fixture.nativeElement.querySelectorAll('p-button[data-testid^="req-review-"]').length).toBe(3);
  });

  it('lets a Product Manager reopen a rejected requirement', () => {
    const { fixture } = render('REJECTED', [], true);
    expect(fixture.nativeElement.querySelector('[data-testid="req-reopen"]')).not.toBeNull();
  });

  it.each<RequirementStatus>(['APPROVED', 'PUBLISHED'])('locks both detail controls and explains why when %s', async status => {
    const { fixture } = render(status);
    await fixture.whenStable();
    fixture.detectChanges();

    expect((fixture.nativeElement.querySelector('[data-testid="req-id"]') as HTMLInputElement).disabled).toBe(true);
    expect((fixture.nativeElement.querySelector('[data-testid="req-shall-text"]') as HTMLTextAreaElement).disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('[data-testid="req-save"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="req-details-locked"]')?.textContent)
      .toContain('approved or published');
  });

  it.each<RequirementStatus>(['APPROVED', 'PUBLISHED'])('does not submit locked details through public save or wizard handlers when %s', async status => {
    const { fixture, requirementService } = render(status);
    const component = fixture.componentInstance;
    component.detailsForm.setValue({ requirementId: 'REQ-locked', shallText: 'Changed while stale.' });
    component.detailsForm.markAsDirty();
    await fixture.whenStable();

    await component.onSave();
    const request = { step: { key: 'details' }, complete: vi.fn(), fail: vi.fn() } as any;
    await component.onStepCommit(request);

    expect(requirementService.createRequirement).not.toHaveBeenCalled();
    expect(requirementService.updateRequirement).not.toHaveBeenCalled();
    expect(request.fail).toHaveBeenCalled();
  });

  it('locks immediately on approved or published transitions without clearing dirty input', async () => {
    const { fixture } = render('DRAFT');
    const component = fixture.componentInstance;
    component.detailsForm.setValue({ requirementId: 'REQ-dirty', shallText: 'Unsaved draft text.' });
    component.detailsForm.markAsDirty();
    const draft = component.requirement()!;

    for (const status of ['APPROVED', 'PUBLISHED'] as RequirementStatus[]) {
      component.requirement.set({ ...draft, status });
      await fixture.whenStable();
      fixture.detectChanges();
      expect(component.detailsForm.getRawValue()).toEqual({ requirementId: 'REQ-dirty', shallText: 'Unsaved draft text.' });
      expect(component.detailsForm.dirty).toBe(true);
      expect(component.detailsForm.disabled).toBe(true);
    }
  });

  it('re-enables an approved requirement reopened as a draft and permits saving', async () => {
    const { fixture, requirementService } = render('APPROVED');
    const component = fixture.componentInstance;
    const approved = component.requirement()!;
    component.requirement.set({ ...approved, status: 'DRAFT' });
    await fixture.whenStable();
    fixture.detectChanges();
    component.requirementId = 1;
    component.detailsForm.setValue({ requirementId: 'REQ-1', shallText: 'The system shall save again.' });
    component.detailsForm.markAsDirty();
    requirementService.updateRequirement.mockResolvedValue({ ...approved, status: 'DRAFT', version: 1, shallText: 'The system shall save again.' });

    expect(component.detailsForm.enabled).toBe(true);
    expect(component.canSave()).toBe(true);
    await component.onSave();
    expect(requirementService.updateRequirement).toHaveBeenCalledOnce();
  });

  it('does not submit when edit permission is denied', async () => {
    const { fixture, requirementService } = render('DRAFT', ['QUALITY'], false, false, false);
    const component = fixture.componentInstance;
    component.detailsForm.setValue({ requirementId: 'REQ-1', shallText: 'The system shall not submit.' });
    component.detailsForm.markAsDirty();

    expect(component.canSave()).toBe(false);
    await component.onSave();
    expect(requirementService.updateRequirement).not.toHaveBeenCalled();
    expect(requirementService.createRequirement).not.toHaveBeenCalled();
  });

  it('keeps regular draft create and save behavior', async () => {
    const { fixture, requirementService } = render('DRAFT');
    const component = fixture.componentInstance;
    component.requirementId = 1;
    component.detailsForm.setValue({ requirementId: 'REQ-1', shallText: 'The system shall save.' });
    component.detailsForm.markAsDirty();
    requirementService.updateRequirement.mockResolvedValue({ ...component.requirement()!, version: 1 });

    expect(component.canSave()).toBe(true);
    await component.onSave();
    expect(requirementService.updateRequirement).toHaveBeenCalledOnce();

    component.isNew.set(true);
    component.requirement.set(null);
    component.requirementId = null;
    component.detailsForm.setValue({ requirementId: 'REQ-new', shallText: 'The system shall create.' });
    component.detailsForm.markAsDirty();
    requirementService.createRequirement.mockResolvedValue({ id: 2, version: 0, requirementId: 'REQ-new', shallText: 'The system shall create.' });
    const request = { step: { key: 'details' }, complete: vi.fn(), fail: vi.fn() } as any;

    await component.onStepCommit(request);
    expect(requirementService.createRequirement).toHaveBeenCalledOnce();
    expect(request.complete).toHaveBeenCalledOnce();
  });
});
