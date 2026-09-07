import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { BottomCta, Chip, LockControl, TripAddButton } from '../index.js';

describe('Chip', () => {
  it('announces selection without relying on colour', () => {
    render(<Chip label="전체" selected />);
    expect(screen.getByRole('button', { name: '전체' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });

  it('gives a reason when disabled', () => {
    render(<Chip label="혼잡도 낮은 순" disabled disabledReason="준비 중이에요" />);
    expect(screen.getByRole('button')).toHaveAttribute('title', '준비 중이에요');
  });
});

describe('LockControl', () => {
  it('treats each lock kind independently', async () => {
    const onDate = vi.fn();
    render(
      <>
        <LockControl kind="date" state="user-locked" label="날짜 고정" onClick={onDate} />
        <LockControl kind="time" state="unlocked" label="시간 고정" />
      </>,
    );
    const date = screen.getByRole('button', { name: '날짜 고정' });
    const time = screen.getByRole('button', { name: '시간 고정' });
    expect(date).toHaveAttribute('aria-pressed', 'true');
    expect(time).toHaveAttribute('aria-pressed', 'false');

    await userEvent.click(date);
    expect(onDate).toHaveBeenCalledOnce();
    // Releasing one lock must not touch the other.
    expect(time).toHaveAttribute('aria-pressed', 'false');
  });

  it('does not offer a reservation lock as a toggle', () => {
    render(
      <LockControl
        kind="reservation"
        state="reservation-locked"
        label="예약 고정"
        disabledReason="예약에서 관리해요"
      />,
    );
    const button = screen.getByRole('button', { name: '예약 고정' });
    expect(button).toBeDisabled();
    expect(button).not.toHaveAttribute('aria-pressed');
  });
});

describe('TripAddButton', () => {
  it('never labels saving as scheduling', () => {
    render(<TripAddButton state="idle" />);
    const name = screen.getByRole('button').getAttribute('aria-label') ?? '';
    expect(name).toContain('담기');
    expect(name).not.toContain('일정');
  });

  it('blocks input only while the request is in flight', () => {
    const { rerender } = render(<TripAddButton state="loading" />);
    expect(screen.getByRole('button')).toBeDisabled();
    expect(screen.getByRole('button')).toHaveAttribute('aria-busy', 'true');

    // A failure has to stay retryable and a duplicate has to stay reachable.
    rerender(<TripAddButton state="error" />);
    expect(screen.getByRole('button')).toBeEnabled();
    rerender(<TripAddButton state="duplicate" />);
    expect(screen.getByRole('button')).toBeEnabled();
  });

  it('distinguishes every state by accessible name', () => {
    const states = ['idle', 'saved', 'no-trip', 'duplicate', 'loading', 'error'] as const;
    const names = states.map((state) => {
      const { getByRole, unmount } = render(<TripAddButton state={state} />);
      const name = getByRole('button').getAttribute('aria-label');
      unmount();
      return name;
    });
    expect(new Set(names).size).toBe(states.length);
  });
});

describe('BottomCta', () => {
  it('fires the primary action and honours disabled', async () => {
    const onClick = vi.fn();
    const { rerender } = render(<BottomCta label="다음" onClick={onClick} />);
    await userEvent.click(screen.getByRole('button', { name: '다음' }));
    expect(onClick).toHaveBeenCalledOnce();

    rerender(<BottomCta label="다음" onClick={onClick} disabled />);
    await userEvent.click(screen.getByRole('button', { name: '다음' }));
    expect(onClick).toHaveBeenCalledOnce();
  });
});
