import type React from 'react';

interface PageLayoutProps {
  sidebar: React.ReactNode;
  children: React.ReactNode;
  sidebarFooter?: React.ReactNode;
  scrollRef?: React.RefObject<HTMLDivElement | null>;
  contentClassName?: string;
}

export function PageLayout({ sidebar, children, sidebarFooter, scrollRef, contentClassName }: PageLayoutProps) {
  return (
    <div className="flex h-full">
      <nav className="w-64 shrink-0 p-4 space-y-1 flex flex-col h-full">
        <div className="flex-1 space-y-1">
          {sidebar}
        </div>
        {sidebarFooter}
      </nav>
      <div ref={scrollRef} className="flex-1 overflow-y-auto">
        <div className={contentClassName ?? 'max-w-2xl mx-auto p-6 space-y-6'}>
          {children}
        </div>
      </div>
    </div>
  );
}
