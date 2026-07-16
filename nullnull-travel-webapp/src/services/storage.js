// 익명 MVP 로컬 보관함 — localStorage 기반(내 코스·저장 관광지·저장 코스·여행 중 코스)
export const STORAGE_KEYS = {
  myCourses: 'nullnull.my-courses',
  savedSpots: 'nullnull.saved-spots',
  savedCourses: 'nullnull.saved-courses',
  activeCourse: 'nullnull.active-course',
  wikiImages: 'nullnull.wiki-images',
  adminToken: 'nullnull.admin-token',
};

export function readJson(key, fallback) {
  try {
    return JSON.parse(localStorage.getItem(key)) ?? fallback;
  } catch {
    return fallback;
  }
}

export function writeJson(key, value) {
  localStorage.setItem(key, JSON.stringify(value));
}

export const loadMyCourses = () => readJson(STORAGE_KEYS.myCourses, []);
export const loadSavedSpots = () => readJson(STORAGE_KEYS.savedSpots, []);
export const loadSavedCourses = () => readJson(STORAGE_KEYS.savedCourses, []);
export const loadActiveCourse = () => readJson(STORAGE_KEYS.activeCourse, null);
