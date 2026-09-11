import type { Meta, StoryObj } from '@storybook/react-vite';
import { MustVisitBadge } from './MustVisitBadge.js';

// Figma: `Data / Must Visit` (C40).
//
// Marks a MUST_VISIT constraint. The lock is independent of DATE, TIME and
// RESERVATION and is never released automatically (CLAUDE.md invariant 7);
// this badge only displays it.
const meta = {
  title: 'Data/MustVisitBadge',
  component: MustVisitBadge,
} satisfies Meta<typeof MustVisitBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** Beside a place name, as the must-visit and trip item cards use it. */
export const NextToAName: Story = {
  render: () => (
    <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
      <strong style={{ fontSize: 13 }}>경복궁</strong>
      <MustVisitBadge />
    </span>
  ),
};
