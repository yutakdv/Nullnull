import type { Meta, StoryObj } from '@storybook/react-vite';
import { SearchField } from './SearchField.js';

// Figma: `Form / Search` (C10).
//
// The label is visually hidden but always present: the icon alone is not a
// name, and a search box without one is unreachable by voice control.
const meta = {
  title: 'Form/SearchField',
  component: SearchField,
  args: { label: '장소 이름으로 검색', placeholder: '장소 검색' },
} satisfies Meta<typeof SearchField>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Empty: Story = {};

export const WithQuery: Story = {
  args: { defaultValue: '경복궁' },
};

export const Disabled: Story = {
  args: { disabled: true },
};

/**
 * The query never reaches a URL — searchPlaces is a read-only POST precisely so
 * free-form text stays out of access logs (docs/api/README.md).
 */
export const LongQuery: Story = {
  args: { defaultValue: '서울特別市 종로구 사직로 경복궁 근처 조용한 카페' },
  decorators: [
    (Story) => (
      <div style={{ width: 320 }}>
        <Story />
      </div>
    ),
  ],
};
