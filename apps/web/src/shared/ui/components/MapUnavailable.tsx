import styles from './MapUnavailable.module.css';

// Figma: `Map / Optimization` view=no-map-item and view=route-unavailable
// (C37), and the map-off default the Live list uses (C45 territory).
//
// P0 has no approved route provider, so no component here draws a route,
// a detour time or a distance it cannot source. Where a map would go, this
// states what is being compared instead (FCR-005, FCR-012).
export interface MapUnavailableProps {
  title: string;
  detail?: string;
}

export function MapUnavailable({ title, detail }: MapUnavailableProps) {
  return (
    <div className={styles.panel}>
      <p className={styles.title}>{title}</p>
      {detail ? <p className={styles.detail}>{detail}</p> : null}
    </div>
  );
}
