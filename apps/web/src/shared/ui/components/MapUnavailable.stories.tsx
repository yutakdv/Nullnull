import type { Meta, StoryObj } from '@storybook/react-vite';
import { MapUnavailable } from './MapUnavailable.js';

// Shown wherever a map would be. P0 keeps map capability OFF until a provider,
// licence and attribution are approved, so this panel is the default rather
// than a fallback — and the list view beside it carries the same information
// and filters (.claude/rules/frontend.md).
const meta = {
  title: 'Map/MapUnavailable',
  component: MapUnavailable,
  args: { title: '지도는 준비 중이에요' },
} satisfies Meta<typeof MapUnavailable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const WithDetail: Story = {
  args: {
    title: '지도는 준비 중이에요',
    detail: '같은 장소를 아래 목록에서 모두 확인할 수 있어요.',
  },
};
