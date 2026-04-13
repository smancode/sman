import { useEffect, useRef, useCallback, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronLeft, Wifi, Bot, Cpu, Search, Store } from 'lucide-react';
import { cn } from '@/lib/utils';
import { LLMSettings } from './LLMSettings';
import { WebSearchSettings } from './WebSearchSettings';
import { ChatbotSettings } from './ChatbotSettings';
import { BackendSettings } from './BackendSettings';
import { BazaarSettings } from '@/features/bazaar/BazaarSettings';
import { useSettingsStore } from '@/stores/settings';

const SECTIONS = [
  { id: 'backend', label: '后端连接', icon: Wifi },
  { id: 'chatbot', label: 'Bot 机器人设置', icon: Bot },
  { id: 'llm', label: '模型配置', icon: Cpu },
  { id: 'websearch', label: '网络搜索配置', icon: Search },
  { id: 'bazaar', label: '世界配置', icon: Store },
] as const;

export function Settings() {
  const fetchSettings = useSettingsStore((s) => s.fetchSettings);
  const navigate = useNavigate();
  const scrollRef = useRef<HTMLDivElement>(null);
  const [activeSection, setActiveSection] = useState('backend');
  const isScrollingRef = useRef(false);

  useEffect(() => {
    fetchSettings();
  }, [fetchSettings]);

  // Intersection observer to track which section is visible
  useEffect(() => {
    const container = scrollRef.current;
    if (!container) return;

    // Skip the initial batch of observer callbacks to avoid overriding default
    let ready = false;
    const timer = setTimeout(() => { ready = true; }, 200);

    const observers: IntersectionObserver[] = [];

    SECTIONS.forEach(({ id }) => {
      const el = document.getElementById(`settings-${id}`);
      if (!el) return;

      const observer = new IntersectionObserver(
        (entries) => {
          entries.forEach((entry) => {
            if (ready && entry.isIntersecting && !isScrollingRef.current) {
              setActiveSection(id);
            }
          });
        },
        { root: container, threshold: 0.3 },
      );
      observer.observe(el);
      observers.push(observer);
    });

    return () => {
      clearTimeout(timer);
      observers.forEach((o) => o.disconnect());
    };
  }, []);

  const scrollToSection = useCallback((id: string) => {
    const el = document.getElementById(`settings-${id}`);
    if (el) {
      isScrollingRef.current = true;
      setActiveSection(id);
      el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      // Re-enable observer tracking after scroll animation finishes
      setTimeout(() => { isScrollingRef.current = false; }, 800);
    }
  }, []);

  return (
    <div className="flex h-full">
      {/* Left nav — same width as main sidebar (w-64) */}
      <nav className="w-64 shrink-0 p-4 space-y-1">
        <button
          onClick={() => navigate(-1)}
          className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground mb-4 px-2"
        >
          <ChevronLeft className="h-4 w-4" />
          返回
        </button>
        {SECTIONS.map(({ id, label, icon: Icon }) => (
          <button
            key={id}
            onClick={() => scrollToSection(id)}
            className={cn(
              'flex items-center gap-2.5 w-full rounded-lg px-3 py-2 text-sm font-medium transition-colors',
              activeSection === id
                ? 'bg-primary/10 text-primary'
                : 'text-muted-foreground hover:bg-muted hover:text-foreground',
            )}
          >
            <Icon className="h-4 w-4" />
            {label}
          </button>
        ))}
      </nav>

      {/* Right scrollable content */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto">
        <div className="max-w-2xl mx-auto p-6 space-y-6">
          <BackendSettings id="settings-backend" />
          <ChatbotSettings id="settings-chatbot" />
          <LLMSettings id="settings-llm" />
          <WebSearchSettings id="settings-websearch" />
          <BazaarSettings id="settings-bazaar" />
        </div>
      </div>
    </div>
  );
}

export default Settings;
