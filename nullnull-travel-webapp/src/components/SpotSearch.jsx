import { useEffect, useState } from 'react';
import { Search } from 'lucide-react';
import { searchSpots } from '../api/endpoints';

// TourAPI로 수집된 모든 장소를 키워드로 검색해 기준 장소로 선택한다(동적 추천의 입구)
export default function SpotSearch({ onPick, disabled }) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);

  useEffect(() => {
    const keyword = query.trim();
    if (keyword.length < 2) {
      setResults([]);
      return undefined;
    }
    const timer = window.setTimeout(async () => {
      try {
        const response = await searchSpots(keyword);
        setResults(response.items);
      } catch {
        setResults([]);
      }
    }, 250);
    return () => window.clearTimeout(timer);
  }, [query]);

  const pick = (item) => {
    setQuery('');
    setResults([]);
    onPick(item.spot_id);
  };

  return (
    <div className="search-wrap">
      <label className="search-bar">
        <Search size={20} />
        <input
          value={query}
          placeholder="서울의 모든 장소 검색 (예: 창덕궁, 서울숲)"
          onChange={(event) => setQuery(event.target.value)}
          onBlur={() => window.setTimeout(() => setResults([]), 150)}
          disabled={disabled}
        />
      </label>
      {results.length > 0 && (
        <ul className="search-results" role="listbox">
          {results.map((item) => (
            <li key={item.spot_id}>
              {/* onMouseDown: input blur보다 먼저 실행돼 선택이 씹히지 않는다 */}
              <button onMouseDown={() => pick(item)}>
                <strong>{item.name}</strong>
                <small>
                  {item.category_name} · {item.addr ?? '서울'}
                </small>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
