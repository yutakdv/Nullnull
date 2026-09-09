import type { Meta, StoryObj } from '@storybook/react-vite';
import { SheetGrab } from './SheetGrab.js';

// Figma: `Sheet / Grab` (C44). The drag affordance at the top of a sheet.
//
// Decorative on its own: dragging is never the only way to dismiss a sheet,
// because a drag handle is unreachable by keyboard. The sheet that owns this
// also provides Escape and a labelled close control.
const meta = {
  title: 'Sheet/SheetGrab',
  component: SheetGrab,
} satisfies Meta<typeof SheetGrab>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const OnASheet: Story = {
  render: () => (
    <div
      style={{
        borderRadius: '16px 16px 0 0',
        background: 'var(--color-bg-default)',
        boxShadow: '0 -2px 12px rgba(0,0,0,0.1)',
        paddingBottom: 24,
        width: 320,
      }}
    >
      <SheetGrab />
      <p style={{ margin: 0, padding: '0 16px' }}>어느 여행에 담을까요?</p>
    </div>
  ),
};
