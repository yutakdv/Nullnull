import type { InputHTMLAttributes } from 'react';
import { IconSearch } from '../icons/index.js';
import styles from './SearchField.module.css';

// Figma: `Form / Search` (C10).
//
// Search text is sent in a POST body so it never lands in a URL or an access
// log (docs/api/README.md on searchPlaces). This field therefore never puts
// the query in the address bar itself.
export interface SearchFieldProps
  extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label: string;
}

export function SearchField({ label, ...rest }: SearchFieldProps) {
  return (
    <label className={styles.field}>
      <span className={styles.visuallyHidden}>{label}</span>
      <IconSearch size={20} />
      <input type="search" className={styles.input} {...rest} />
    </label>
  );
}
