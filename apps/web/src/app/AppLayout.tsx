import { Outlet } from 'react-router';

export function AppLayout() {
  return (
    <div className="app-shell">
      <main id="main" className="app-main">
        <Outlet />
      </main>
    </div>
  );
}
