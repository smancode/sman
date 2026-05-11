import { createBrowserRouter, Navigate } from 'react-router-dom';
import { MainLayout } from '@/components/layout/MainLayout';
import { Chat } from '@/features/chat';
import { Settings } from '@/features/settings';
import { CronTasksPage } from '@/features/cron-tasks';
import { BatchTasksPage } from '@/features/batch-tasks';
import { StardomEntry } from '@/features/stardom/StardomEntry';
import { SmartPathPage } from '@/features/smart-paths';
import { HubDashboard } from '@/features/hub/HubDashboard';

export const router = createBrowserRouter([
  {
    path: '/',
    element: <MainLayout />,
    children: [
      { index: true, element: <Navigate to="/chat" replace /> },
      { path: 'chat', element: <Chat /> },
      { path: 'cron-tasks', element: <CronTasksPage /> },
      { path: 'batch-tasks', element: <BatchTasksPage /> },
      { path: 'smart-paths', element: <SmartPathPage /> },
      { path: 'stardom', element: <StardomEntry /> },
      { path: 'hub', element: <HubDashboard /> },
      { path: 'settings', element: <Settings /> },
      { path: '*', element: <Navigate to="/chat" replace /> },
    ],
  },
]);
