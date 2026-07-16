// 마이페이지 — 일반적인 디지털 서비스의 프로필/메뉴 + 저장한 관광지·코스
import {
  Bell,
  ChevronRight,
  Clock3,
  Compass,
  Info,
  LogOut,
  Navigation,
  Route,
  Settings,
  Trash2,
  UserRound,
  Bookmark,
} from 'lucide-react';
import SmartImage from '../components/SmartImage';
import { Card } from '../components/common';
import { RegionSpotCard } from '../components/cards';

export default function MyPageScreen({
  savedSpots,
  savedCourses,
  myCourses,
  activeCourse,
  onEndTravel,
  onOpenSpot,
  onOpenCourse,
  onRemoveSaved,
  onRemoveSavedCourse,
  onNotice,
}) {
  const menuItems = [
    { key: 'courses', icon: Compass, label: '내 코스', desc: `${myCourses.length}개 보관 중` },
    { key: 'alerts', icon: Bell, label: '알림 설정', desc: '혼잡 알림 받기' },
    { key: 'about', icon: Info, label: '서비스 소개', desc: 'Null crowd, Full trip' },
    { key: 'logout', icon: LogOut, label: '로그아웃', desc: '' },
  ];

  return (
    <section className="screen mypage-screen">
      <Card className="profile-card">
        <span className="profile-avatar">
          <UserRound size={30} />
        </span>
        <div className="profile-meta">
          <h1>널널한 여행자</h1>
          <p>붐비는 곳 말고, 널널하게 즐기는 중</p>
        </div>
        <button
          className="profile-settings"
          aria-label="프로필 설정"
          onClick={() => onNotice('준비 중인 기능이에요')}
        >
          <Settings size={19} />
        </button>
      </Card>

      <div className="mypage-stats">
        <div>
          <strong>{savedSpots.length}</strong>
          <span>저장한 관광지</span>
        </div>
        <div>
          <strong>{savedCourses.length}</strong>
          <span>저장한 코스</span>
        </div>
        <div>
          <strong>{myCourses.length}</strong>
          <span>내 코스</span>
        </div>
      </div>

      {/* 여행하기로 선택한 코스 — 지금 사용 중인 코스를 맨 위에서 바로 연다 */}
      {activeCourse && (
        <>
          <div className="section-header compact">
            <h2>여행 중인 코스</h2>
            <button onClick={onEndTravel}>여행 마치기</button>
          </div>
          <div className="region-spot-card active-course-card">
            <button
              className="region-spot-main"
              onClick={() => onOpenCourse(activeCourse.course_id)}
            >
              <SmartImage
                src={activeCourse.image_url}
                name={activeCourse.title}
                alt={activeCourse.title}
              />
              <span className="region-spot-body">
                <span className="region-spot-top">
                  <strong>{activeCourse.title}</strong>
                </span>
                <span className="region-spot-addr">{activeCourse.location}</span>
                <span className="region-spot-meta">
                  <em>
                    <Navigation size={13} />
                    여행 중
                  </em>
                  {activeCourse.duration_text && (
                    <em>
                      <Clock3 size={13} />
                      {activeCourse.duration_text}
                    </em>
                  )}
                </span>
              </span>
              <ChevronRight size={20} className="region-spot-arrow" />
            </button>
          </div>
        </>
      )}

      <div className="section-header compact">
        <h2>저장한 관광지</h2>
      </div>
      {savedSpots.length ? (
        <div className="region-results">
          {savedSpots.map((spot) => (
            <RegionSpotCard
              key={spot.spot_id}
              spot={spot}
              onClick={() => onOpenSpot(spot.spot_id)}
              onRemove={() => onRemoveSaved(spot)}
            />
          ))}
        </div>
      ) : (
        <div className="mypage-empty">
          <Bookmark size={26} />
          <p>
            아직 저장한 관광지가 없어요.
            <br />
            관광지 상세에서 하트를 눌러 담아보세요.
          </p>
        </div>
      )}

      <div className="section-header compact">
        <h2>저장한 코스</h2>
      </div>
      {savedCourses.length ? (
        <div className="region-results">
          {savedCourses.map((course) => (
            <div className="region-spot-card" key={course.course_id}>
              <button className="region-spot-main" onClick={() => onOpenCourse(course.course_id)}>
                <SmartImage src={course.image_url} name={course.title} alt={course.title} />
                <span className="region-spot-body">
                  <span className="region-spot-top">
                    <strong>{course.title}</strong>
                  </span>
                  <span className="region-spot-addr">{course.location}</span>
                  {course.duration_text && (
                    <span className="region-spot-meta">
                      <em>
                        <Clock3 size={13} />
                        {course.duration_text}
                      </em>
                    </span>
                  )}
                </span>
              </button>
              <button
                className="region-spot-remove"
                onClick={() => onRemoveSavedCourse(course)}
                aria-label="코스 저장 해제"
              >
                <Trash2 size={17} />
              </button>
            </div>
          ))}
        </div>
      ) : (
        <div className="mypage-empty">
          <Route size={26} />
          <p>
            아직 저장한 코스가 없어요.
            <br />홈 인기 코스에서 북마크를 눌러 담아보세요.
          </p>
        </div>
      )}

      <Card className="mypage-menu">
        {menuItems.map(({ key, icon: Icon, label, desc }) => (
          <button
            key={key}
            className="mypage-menu-item"
            onClick={() =>
              key === 'courses' && myCourses[0]
                ? onOpenCourse(myCourses[0].course_id)
                : onNotice('준비 중인 기능이에요')
            }
          >
            <span className="mypage-menu-icon">
              <Icon size={18} />
            </span>
            <span className="mypage-menu-text">
              <strong>{label}</strong>
              {desc && <small>{desc}</small>}
            </span>
            <ChevronRight size={18} />
          </button>
        ))}
      </Card>
    </section>
  );
}
