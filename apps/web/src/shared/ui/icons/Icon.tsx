import type { SVGProps } from 'react';

// Every icon mirrors a Figma `icon/*` component: 24×24 viewBox, 1.7 stroke,
// round caps and joins. Colour comes from currentColor so callers style the
// parent, never the glyph (COMPONENT_CATALOG C13-C31, C36).
//
// Decorative by default (aria-hidden). Pass a `title` only when the icon is
// the sole label for a control; otherwise put the name on the control itself.

export interface IconProps extends Omit<SVGProps<SVGSVGElement>, 'children'> {
  /** Rendered size in px. Defaults to 24, matching the Figma frame. */
  size?: number;
  /** Accessible name. Omit for decorative icons. */
  title?: string;
}

export interface IconRenderProps extends IconProps {
  paths: readonly string[];
  /** Filled glyphs use fill; the rest are stroked outlines. */
  variant?: 'stroke' | 'fill';
}

export function Icon({
  paths,
  variant = 'stroke',
  size = 24,
  title,
  ...rest
}: IconRenderProps) {
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
      {paths.map((d) =>
        variant === 'fill' ? (
          <path key={d} d={d} fill="currentColor" fillRule="evenodd" />
        ) : (
          <path
            key={d}
            d={d}
            stroke="currentColor"
            strokeWidth={1.7}
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        ),
      )}
    </svg>
  );
}
