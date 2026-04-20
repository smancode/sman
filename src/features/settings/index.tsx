import { useEffect, useRef, useCallback, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronLeft, Wifi, Bot, Cpu, Search, Store, ExternalLink, Users, Star } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { cn } from '@/lib/utils';
import { LLMSettings } from './LLMSettings';
import { WebSearchSettings } from './WebSearchSettings';
import { ChatbotSettings } from './ChatbotSettings';
import { BackendSettings } from './BackendSettings';
import { BazaarSettings } from '@/features/bazaar/BazaarSettings';
import { useSettingsStore } from '@/stores/settings';

const APP_VERSION = '0.2.6';

async function openExternalUrl(url: string) {
  // 1. Electron: use shell.openExternal via IPC
  const w = window as any;
  if (w.sman?.openExternal) {
    w.sman.openExternal(url);
    return;
  }
  // 2. Fallback: ask backend server to open
  try {
    const res = await fetch('/api/open-external', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url }),
    });
    if (res.ok) return;
  } catch {}
  // 3. Last resort
  window.open(url, '_blank', 'noopener,noreferrer');
}

const SECTIONS = [
  { id: 'llm', label: '模型配置', icon: Cpu },
  { id: 'backend', label: '后端连接', icon: Wifi },
  { id: 'chatbot', label: 'Bot 机器人设置', icon: Bot },
  { id: 'websearch', label: '网络搜索配置', icon: Search },
  { id: 'bazaar', label: 'Agent星图配置', icon: Store },
  { id: 'community', label: '加入社群', icon: Users },
] as const;

export function Settings() {
  const fetchSettings = useSettingsStore((s) => s.fetchSettings);
  const navigate = useNavigate();
  const scrollRef = useRef<HTMLDivElement>(null);
  const [activeSection, setActiveSection] = useState('llm');
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
      <nav className="w-64 shrink-0 p-4 space-y-1 flex flex-col h-full">
        <button
          onClick={() => navigate(-1)}
          className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground mb-4 px-2"
        >
          <ChevronLeft className="h-4 w-4" />
          返回
        </button>
        <div className="flex-1 space-y-1">
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
        </div>

        {/* 版本号 */}
        <div className="text-xs text-muted-foreground/60 px-3 pt-4 border-t border-border/40">
          v{APP_VERSION}
        </div>
      </nav>

      {/* Right scrollable content */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto">
        <div className="max-w-2xl mx-auto p-6 space-y-6">
          <LLMSettings id="settings-llm" />
          <BackendSettings id="settings-backend" />
          <ChatbotSettings id="settings-chatbot" />
          <WebSearchSettings id="settings-websearch" />
          <BazaarSettings id="settings-bazaar" />

          {/* 加入社群 */}
          <Card id="settings-community">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Users className="h-5 w-5" />
                加入社群
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="flex items-center justify-around gap-6">
                <div className="flex flex-col items-center gap-2">
                  <p className="text-sm font-medium">Sman 微信群</p>
                  <img
                    src="/resources/pictures/sman-wechat-group.png"
                    alt="Sman 微信群二维码"
                    className="w-[150px] h-[150px] rounded-xl object-cover"
                  />
                </div>
                <div className="flex flex-col items-center gap-2">
                  <p className="flex items-center gap-1 text-sm font-medium">
                    <Star className="h-4 w-4 text-yellow-500 fill-yellow-500" />
                    Star on GitHub
                  </p>
                  <button
                    type="button"
                    onClick={() => openExternalUrl('https://github.com/smancode/sman')}
                    className="flex items-center gap-1.5 text-sm text-primary hover:underline"
                  >
                    <ExternalLink className="h-4 w-4" />
                    链接
                  </button>
                </div>
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}

export default Settings;
