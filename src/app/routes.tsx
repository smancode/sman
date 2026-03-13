/**
 * Routes Configuration
 * Defines application routes using React Router
 */
import { createBrowserRouter, Navigate } from 'react-router-dom';
import { MainLayout } from '@/components/layout/MainLayout';
import { Chat } from '@/features/chat';
import { Settings } from '@/features/settings';

export const router = createBrowserRouter([
  {
    path: '/',
    element: <MainLayout />,
    children: [
      {
        index: true,
        element: <Navigate to="/chat" replace />,
      },
      {
        path: 'chat',
        element: <Chat />,
      },
      {
        path: 'models',
        element: <div className="p-6 text-muted-foreground">Models page coming soon...</div>,
      },
      {
        path: 'agents',
        element: <div className="p-6 text-muted-foreground">Agents page coming soon...</div>,
      },
      {
        path: 'channels',
        element: <div className="p-6 text-muted-foreground">Channels page coming soon...</div>,
      },
      {
        path: 'skills',
        element: <div className="p-6 text-muted-foreground">Skills page coming soon...</div>,
      },
      {
        path: 'cron',
        element: <div className="p-6 text-muted-foreground">Cron page coming soon...</div>,
      },
      {
        path: 'settings',
        element: <Settings />,
      },
      {
        path: '*',
        element: <Navigate to="/chat" replace />,
      },
    ],
  },
]);
