import {
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
  useRef,
  useState,
} from 'react';
import { SheetGrab } from '../../shared/ui/index.js';
import styles from './LiveBottomSheet.module.css';

type SheetSnap = 'expanded' | 'collapsed';

interface LiveBottomSheetProps {
  children: ReactNode;
  collapseLabel: string;
  expandLabel: string;
  initialSnap?: SheetSnap;
  title: string;
}

const SNAP_DISTANCE = 64;
const SNAP_VELOCITY = 0.65;

export function LiveBottomSheet({
  children,
  collapseLabel,
  expandLabel,
  initialSnap = 'expanded',
  title,
}: LiveBottomSheetProps) {
  const [snap, setSnap] = useState<SheetSnap>(initialSnap);
  const [dragOffset, setDragOffset] = useState(0);
  const suppressClick = useRef(false);
  const drag = useRef<{
    pointerId: number;
    startY: number;
    lastY: number;
    lastAt: number;
    velocity: number;
  } | null>(null);
  const expanded = snap === 'expanded';

  const finishDrag = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const gesture = drag.current;
    if (!gesture || gesture.pointerId !== event.pointerId) return;

    const distance = event.clientY - gesture.startY;
    if (expanded && (distance > SNAP_DISTANCE || gesture.velocity > SNAP_VELOCITY)) {
      setSnap('collapsed');
    } else if (
      !expanded &&
      (distance < -SNAP_DISTANCE || gesture.velocity < -SNAP_VELOCITY)
    ) {
      setSnap('expanded');
    }
    setDragOffset(0);
    drag.current = null;
    if (event.currentTarget.hasPointerCapture?.(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  };

  return (
    <section
      aria-label={title}
      className={styles.sheet}
      data-dragging={drag.current ? '' : undefined}
      data-snap={snap}
      style={{ '--live-sheet-drag': `${dragOffset}px` } as CSSProperties}
    >
      <button
        aria-controls="live-sheet-content"
        aria-expanded={expanded}
        aria-label={expanded ? collapseLabel : expandLabel}
        className={styles.handle}
        data-testid="live-sheet-drag-handle"
        onClick={() => {
          if (suppressClick.current) {
            suppressClick.current = false;
            return;
          }
          setSnap(expanded ? 'collapsed' : 'expanded');
        }}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' || event.key === 'Escape') {
            event.preventDefault();
            setSnap('collapsed');
          }
          if (event.key === 'ArrowUp') {
            event.preventDefault();
            setSnap('expanded');
          }
        }}
        onPointerCancel={finishDrag}
        onPointerDown={(event) => {
          suppressClick.current = false;
          drag.current = {
            pointerId: event.pointerId,
            startY: event.clientY,
            lastY: event.clientY,
            lastAt: event.timeStamp,
            velocity: 0,
          };
          event.currentTarget.setPointerCapture?.(event.pointerId);
        }}
        onPointerMove={(event) => {
          const gesture = drag.current;
          if (!gesture || gesture.pointerId !== event.pointerId) return;
          const elapsed = Math.max(event.timeStamp - gesture.lastAt, 1);
          gesture.velocity = (event.clientY - gesture.lastY) / elapsed;
          gesture.lastY = event.clientY;
          gesture.lastAt = event.timeStamp;
          const delta = event.clientY - gesture.startY;
          if (Math.abs(delta) > 4) suppressClick.current = true;
          setDragOffset(expanded ? Math.max(0, delta) : Math.min(0, delta));
        }}
        onPointerUp={finishDrag}
        type="button"
      >
        <SheetGrab />
      </button>
      <div
        aria-hidden={!expanded || undefined}
        className={styles.content}
        data-testid="live-sheet-content"
        id="live-sheet-content"
        inert={!expanded ? true : undefined}
      >
        {children}
      </div>
    </section>
  );
}
