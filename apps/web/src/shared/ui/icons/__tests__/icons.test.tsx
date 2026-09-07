import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import * as icons from '../icons.js';
import { IconBack, IconHeartLikeFilled, IconSearch } from '../icons.js';

const all = Object.entries(icons).filter(([n]) => n.startsWith('Icon'));

describe('icon set', () => {
  it('exports one component per Figma icon component', () => {
    expect(all).toHaveLength(20);
  });

  it('renders every icon inside the shared 24x24 viewBox', () => {
    for (const [name, Component] of all) {
      const { container, unmount } = render(<Component />);
      const svg = container.querySelector('svg');
      expect(svg, name).not.toBeNull();
      expect(svg!.getAttribute('viewBox'), name).toBe('0 0 24 24');
      expect(container.querySelectorAll('path').length, name).toBeGreaterThan(0);
      unmount();
    }
  });

  it('keeps every path inside the viewBox after translation', () => {
    for (const [name, Component] of all) {
      const { container, unmount } = render(<Component />);
      for (const path of container.querySelectorAll('path')) {
        const numbers = (path.getAttribute('d') ?? '').match(/-?\d+(\.\d+)?/g) ?? [];
        for (const n of numbers) {
          expect(Number(n), `${name} coordinate out of bounds`).toBeGreaterThanOrEqual(0);
          expect(Number(n), `${name} coordinate out of bounds`).toBeLessThanOrEqual(24);
        }
      }
      unmount();
    }
  });

  it('is decorative unless given a title', () => {
    const { container } = render(<IconSearch />);
    expect(container.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });

  it('becomes an labelled image when given a title', () => {
    const { getByRole } = render(<IconBack title="뒤로" />);
    expect(getByRole('img', { name: '뒤로' })).toBeInTheDocument();
  });

  it('fills filled variants and strokes the rest', () => {
    const filled = render(<IconHeartLikeFilled />);
    expect(filled.container.querySelector('path')).toHaveAttribute(
      'fill',
      'currentColor',
    );
    const stroked = render(<IconSearch />);
    expect(stroked.container.querySelector('path')).toHaveAttribute(
      'stroke',
      'currentColor',
    );
  });
});
