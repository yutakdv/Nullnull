import styles from './Toast.module.css';

// Figma: `Feedback / Toast` (C35).
//
// Secondary feedback only. Anything the user must still be able to act on
// after the toast disappears has to live in persistent UI as well; FCR-015
// moved undo out of the toast for exactly this reason.
export interface ToastProps {
  message: string;
  actionLabel?: string;
  onAction?: () => void;
  /** Errors interrupt; confirmations do not. */
  tone?: 'info' | 'error';
}

export function Toast({ message, actionLabel, onAction, tone = 'info' }: ToastProps) {
  return (
    <div
      className={styles.toast}
      role={tone === 'error' ? 'alert' : 'status'}
      aria-live={tone === 'error' ? 'assertive' : 'polite'}
    >
      <span>{message}</span>
      {actionLabel ? (
        <button type="button" className={styles.action} onClick={onAction}>
          {actionLabel}
        </button>
      ) : null}
    </div>
  );
}
