import styles from './Tag.module.css';

// Figma: `Data / Tag` (C47). tone=기본|외곽선.
// Presentational only: it carries no interaction and no data state.
export interface TagProps {
  label: string;
  tone?: 'solid' | 'outline';
}

export function Tag({ label, tone = 'solid' }: TagProps) {
  return <span className={`${styles.tag} ${styles[tone]}`}>{label}</span>;
}
