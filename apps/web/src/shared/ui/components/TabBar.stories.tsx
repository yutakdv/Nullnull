import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { TabBar, type TabKey } from './TabBar.js';

// Figma: `Nav / TabBar` (C12).
//
// The active tab is named as well as coloured: an icon plus a tint is not a
// state a screen reader can report (COMPONENT_CATALOG §1).
const LABELS = { home: '홈', trip: '내 여행', live: '라이브', profile: '내 정보' };

const meta = {
  title: 'Nav/TabBar',
  component: TabBar,
  args: { active: 'home' as const, labels: LABELS, navLabel: '주요 메뉴' },
} satisfies Meta<typeof TabBar>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Home: Story = {};
export const MyTrip: Story = { args: { active: 'trip' } };
export const Live: Story = { args: { active: 'live' } };
export const Profile: Story = { args: { active: 'profile' } };

/** Switchable, so the pointer and keyboard paths can be tried. */
export const Interactive: Story = {
  render: () => {
    const [active, setActive] = useState<TabKey>('home');
    return (
      <TabBar active={active} labels={LABELS} navLabel="주요 메뉴" onSelect={setActive} />
    );
  },
};
