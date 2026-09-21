// @vitest-environment happy-dom
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { LiveBottomSheet } from '../LiveBottomSheet.js';

describe('LiveBottomSheet', () => {
  it('keeps a visible control that collapses and re-expands the sheet', async () => {
    const user = userEvent.setup();
    render(
      <LiveBottomSheet
        collapseLabel="목록 내리기"
        expandLabel="목록 올리기"
        title="여행지 목록"
      >
        <button type="button">장소 보기</button>
      </LiveBottomSheet>,
    );

    const handle = screen.getByRole('button', { name: '목록 내리기' });
    expect(handle).toHaveAttribute('aria-expanded', 'true');

    await user.click(handle);
    expect(screen.getByRole('button', { name: '목록 올리기' })).toHaveAttribute(
      'aria-expanded',
      'false',
    );
    expect(screen.getByTestId('live-sheet-content')).toHaveAttribute('inert');

    await user.click(screen.getByRole('button', { name: '목록 올리기' }));
    expect(screen.getByRole('button', { name: '목록 내리기' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
  });

  it('snaps down after a downward drag and back up after an upward drag', () => {
    render(
      <LiveBottomSheet
        collapseLabel="목록 내리기"
        expandLabel="목록 올리기"
        title="여행지 목록"
      >
        <p>장소</p>
      </LiveBottomSheet>,
    );

    let handle = screen.getByRole('button', { name: '목록 내리기' });
    fireEvent.pointerDown(handle, { pointerId: 1, clientY: 200, timeStamp: 0 });
    fireEvent.pointerMove(handle, { pointerId: 1, clientY: 310, timeStamp: 80 });
    fireEvent.pointerUp(handle, { pointerId: 1, clientY: 310, timeStamp: 90 });
    expect(screen.getByRole('button', { name: '목록 올리기' })).toHaveAttribute(
      'aria-expanded',
      'false',
    );

    handle = screen.getByRole('button', { name: '목록 올리기' });
    fireEvent.pointerDown(handle, { pointerId: 2, clientY: 310, timeStamp: 100 });
    fireEvent.pointerMove(handle, { pointerId: 2, clientY: 190, timeStamp: 180 });
    fireEvent.pointerUp(handle, { pointerId: 2, clientY: 190, timeStamp: 190 });
    expect(screen.getByRole('button', { name: '목록 내리기' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
  });

  it('offers keyboard equivalents for both snap points', () => {
    render(
      <LiveBottomSheet
        collapseLabel="목록 내리기"
        expandLabel="목록 올리기"
        title="여행지 목록"
      >
        <p>장소</p>
      </LiveBottomSheet>,
    );

    fireEvent.keyDown(screen.getByRole('button', { name: '목록 내리기' }), {
      key: 'ArrowDown',
    });
    expect(screen.getByRole('button', { name: '목록 올리기' })).toBeVisible();

    fireEvent.keyDown(screen.getByRole('button', { name: '목록 올리기' }), {
      key: 'ArrowUp',
    });
    expect(screen.getByRole('button', { name: '목록 내리기' })).toBeVisible();
  });
});
