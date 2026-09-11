import type { Meta, StoryObj } from '@storybook/react-vite';
import { IconSettings } from '../icons/index.js';
import { NavBar } from './NavBar.js';

// Figma: `Nav / NavBar` (C49). The page top app bar.
const meta = {
  title: 'Nav/NavBar',
  component: NavBar,
  args: { title: '담아둔 장소' },
} satisfies Meta<typeof NavBar>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * A sub-page reached by a back control. `onBack` is a callback rather than an
 * internal `history.go(-1)`: a screen opened by a deep link or a reload has no
 * previous entry belonging to this app (COMPONENT_CATALOG C49).
 */
export const WithBack: Story = {
  args: { onBack: () => undefined, backLabel: '일정으로 돌아가기' },
};

/** A tab destination: a title and a trailing action, no back. */
export const WithAction: Story = {
  args: {
    title: '내 정보',
    actions: (
      <button aria-label="설정" type="button">
        <IconSettings size={24} />
      </button>
    ),
  },
};

/** Title only. */
export const TitleOnly: Story = {};

/**
 * A long title truncates rather than pushing the trailing action off-screen —
 * trip names come from the user and are not length-limited on screen.
 */
export const LongTitle: Story = {
  args: {
    title: '아주 긴 여행 이름이 들어가면 어떻게 되는지 확인하는 제목',
    onBack: () => undefined,
    actions: (
      <button aria-label="설정" type="button">
        <IconSettings size={24} />
      </button>
    ),
  },
};
