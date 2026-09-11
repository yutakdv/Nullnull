import type { Meta, StoryObj } from '@storybook/react-vite';
import { Chip } from './Chip.js';

// Figma: `Form / Chip` (C48). state=기본|선택, size=md|sm.
//
// Selection is announced with aria-pressed, never by colour alone
// (COMPONENT_CATALOG §1), so the selected story is also the accessibility
// story: the state has to survive a greyscale screenshot.
const meta = {
  title: 'Form/Chip',
  component: Chip,
  args: { label: '친구와' },
} satisfies Meta<typeof Chip>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Selected: Story = {
  args: { selected: true },
};

export const Small: Story = {
  args: { size: 'sm' },
};

/**
 * Disabled always carries a reason. A chip that is simply grey leaves the user
 * guessing whether it is broken or merely unavailable.
 */
export const DisabledWithReason: Story = {
  args: {
    label: '日本語',
    disabled: true,
    disabledReason: '준비 중',
  },
};

/** The wizard's two groups, so selected and unselected read side by side. */
export const InAGroup: Story = {
  render: () => (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, maxWidth: 340 }}>
      <Chip label="혼자" />
      <Chip label="친구와" selected />
      <Chip label="연인과" />
      <Chip label="배우자와" />
      <Chip label="아이와" />
      <Chip label="부모님과" />
    </div>
  ),
};
