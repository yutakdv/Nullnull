import type { Meta, StoryObj } from '@storybook/react-vite';
import { DecisionBar } from './DecisionBar.js';

// Figma: `Action / DecisionBar` (C03).
const meta = {
  title: 'Action/DecisionBar',
  component: DecisionBar,
} satisfies Meta<typeof DecisionBar>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Declining sits next to applying, never behind a small link. */
export const Preview: Story = { args: { state: 'preview' } };

export const Applying: Story = { args: { state: 'applying' } };

export const Applied: Story = { args: { state: 'applied' } };

/** A stale run offers a recompute, not an apply. */
export const Stale: Story = { args: { state: 'stale' } };

/** The failure copy states the schedule did not change. */
export const Failed: Story = { args: { state: 'failed' } };
