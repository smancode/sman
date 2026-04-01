import { Outlet } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { Titlebar } from './Titlebar';

export function MainLayout() {
  return (
    <div className="flex flex-col h-screen overflow-hidden bg-background">
      <Titlebar />
      <div className="flex flex-1 overflow-hidden">
        <Sidebar />
        <main className="flex-1 overflow-y-auto">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
