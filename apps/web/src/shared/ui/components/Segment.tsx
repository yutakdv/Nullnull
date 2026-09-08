import styles from './Segment.module.css';

// Figma: `Nav / Segment` (C09). A single-choice switch between views.
// Options that P0 does not support are rendered disabled with a reason
// rather than hidden, so the choice set stays honest.
export interface SegmentOption<T extends string> {
  value: T;
  label: string;
  /** Present means the option cannot be chosen and why. */
  disabledReason?: string;
}

export interface SegmentProps<T extends string> {
  options: readonly SegmentOption<T>[];
  value: T;
  onChange: (next: T) => void;
  label: string;
}

export function Segment<T extends string>({
  options,
  value,
  onChange,
  label,
}: SegmentProps<T>) {
  return (
    <div className={styles.segment} role="tablist" aria-label={label}>
      {options.map((option) => {
        const disabled = option.disabledReason !== undefined;
        return (
          <button
            key={option.value}
            type="button"
            role="tab"
            className={styles.option}
            aria-selected={option.value === value}
            data-selected={option.value === value || undefined}
            disabled={disabled}
            title={option.disabledReason}
            onClick={() => onChange(option.value)}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}
