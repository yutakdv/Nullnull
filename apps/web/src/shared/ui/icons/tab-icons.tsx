// Generated from the Figma `icon/*` tab components. Coordinates are
// translated into the shared 24×24 viewBox. These four carry filled dots
// alongside stroked paths, so they render their own svg rather than going
// through the paths-only Icon primitive.
import type { IconProps } from './Icon.js';

/** Figma: `icon/home` */
export function IconHome({ size = 24, title, ...rest }: IconProps) {
  const labelled = title !== undefined;
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      role={labelled ? 'img' : undefined}
      aria-hidden={labelled ? undefined : true}
      focusable="false"
      {...rest}
    >
      {labelled ? <title>{title}</title> : null}
      <path
        d="M 3.8 11.5 L 12 4.6 L 20.2 11.5 M 6 10 L 6 18.5 C 6 19.33 6.67 20 7.5 20 L 16.5 20 C 17.33 20 18 19.33 18 18.5 L 18 10 M 10.2 20 L 10.2 15.4 C 10.2 14.75 10.72 14.2 11.4 14.2 L 12.6 14.2 C 13.28 14.2 13.8 14.75 13.8 15.4 L 13.8 20"
        stroke="currentColor"
        strokeWidth={1.7}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/** Figma: `icon/trip` */
export function IconTrip({ size = 24, title, ...rest }: IconProps) {
  const labelled = title !== undefined;
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      role={labelled ? 'img' : undefined}
      aria-hidden={labelled ? undefined : true}
      focusable="false"
      {...rest}
    >
      {labelled ? <title>{title}</title> : null}
      <path
        d="M 4.6 10.4 L 19.4 10.4 M 8.6 4 L 8.6 7.2 M 15.4 4 L 15.4 7.2 M 4.6 8 C 4.6 6.9 5.5 6 6.6 6 L 17.4 6 C 18.5 6 19.4 6.9 19.4 8 L 19.4 18 C 19.4 19.1 18.5 20 17.4 20 L 6.6 20 C 5.5 20 4.6 19.1 4.6 18 L 4.6 8 Z"
        stroke="currentColor"
        strokeWidth={1.7}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx={9} cy={14.8} r={1.25} fill="currentColor" />
      <circle cx={13.2} cy={14.8} r={1.25} fill="currentColor" />
    </svg>
  );
}

/** Figma: `icon/live` */
export function IconLive({ size = 24, title, ...rest }: IconProps) {
  const labelled = title !== undefined;
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      role={labelled ? 'img' : undefined}
      aria-hidden={labelled ? undefined : true}
      focusable="false"
      {...rest}
    >
      {labelled ? <title>{title}</title> : null}
      <path
        d="M 8.61 8.9 C 6.91 10.6 6.91 13.4 8.61 15.1 M 15.41 8.9 C 17.11 10.6 17.11 13.4 15.41 15.1 M 6.1 6.4 C 3.2 9.5 3.2 14.5 6.1 17.6 M 17.91 6.4 C 20.81 9.5 20.81 14.5 17.91 17.6"
        stroke="currentColor"
        strokeWidth={1.7}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx={12} cy={12} r={2.2} fill="currentColor" />
    </svg>
  );
}

/** Figma: `icon/profile` */
export function IconProfile({ size = 24, title, ...rest }: IconProps) {
  const labelled = title !== undefined;
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      role={labelled ? 'img' : undefined}
      aria-hidden={labelled ? undefined : true}
      focusable="false"
      {...rest}
    >
      {labelled ? <title>{title}</title> : null}
      <path
        d="M 4.9 19.6 C 4.9 16.1 8.1 14.3 12 14.3 C 15.9 14.3 19.1 16.1 19.1 19.6 M 12 4.5 C 14.04 4.5 15.7 6.16 15.7 8.2 C 15.7 10.24 14.04 11.9 12 11.9 C 9.96 11.9 8.3 10.24 8.3 8.2 C 8.3 6.16 9.96 4.5 12 4.5 Z"
        stroke="currentColor"
        strokeWidth={1.7}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
