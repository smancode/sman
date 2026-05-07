import { useEffect, useRef, useCallback, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronLeft, Wifi, Bot, Cpu, Search, Store, Users, Star, Languages, Download } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { cn } from '@/lib/utils';
import { LLMSettings } from './LLMSettings';
import { WebSearchSettings } from './WebSearchSettings';
import { ChatbotSettings } from './ChatbotSettings';
import { BackendSettings } from './BackendSettings';
import { LanguageSettings } from './LanguageSettings';
import { UpdateSettings } from './UpdateSettings';
import { StardomSettings } from '@/features/stardom/StardomSettings';
import { useSettingsStore } from '@/stores/settings';
import { t, useLocale } from '@/locales';

declare const __APP_VERSION__: string;
const APP_VERSION = __APP_VERSION__;

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

/** 从 GitHub API 获取 star 数，展示社交证明 */
function GitHubStarCount() {
  const [stars, setStars] = useState<number | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetch('https://api.github.com/repos/smancode/sman')
      .then((r) => (r.ok ? r.json() : Promise.reject()))
      .then((data) => {
        if (!cancelled && typeof data.stargazers_count === 'number') {
          setStars(data.stargazers_count);
        }
      })
      .catch(() => { if (!cancelled) setError(true); });
    return () => { cancelled = true; };
  }, []);

  if (error) {
    return (
      <span className="text-xs text-muted-foreground">{t('settings.about.community.starHint')}</span>
    );
  }

  if (stars === null) {
    return (
      <span className="text-xs text-muted-foreground animate-pulse">{t('common.loading')}</span>
    );
  }

  const parts = t('settings.about.community.starCount').split('{count}');

  return (
    <span className="text-xs text-muted-foreground">
      {parts[0]}<span className="font-semibold text-yellow-600 dark:text-yellow-400">{stars.toLocaleString()}</span>{parts[1]}
    </span>
  );
}

const WECHAT_QR_REMOTE_URL = 'https://h5.smancode.com/qrsman/wechat-cropped.png';
const WECHAT_QR_LOCAL_URL = '/resources/pictures/sman-wechat-group.png';

function WechatGroupQR() {
  const [src, setSrc] = useState(WECHAT_QR_LOCAL_URL);

  useEffect(() => {
    const img = new Image();
    img.onload = () => setSrc(WECHAT_QR_REMOTE_URL);
    img.onerror = () => setSrc(WECHAT_QR_LOCAL_URL);
    img.src = WECHAT_QR_REMOTE_URL;
  }, []);

  return (
    <img
      src={src}
      alt="Sman 微信群二维码"
      className="w-[150px] h-[150px] rounded-xl object-cover border-2 border-yellow-400 dark:border-border/60"
    />
  );
}

const SECTIONS = [
  { id: 'llm', label: '模型配置', icon: Cpu },
  { id: 'backend', label: '后端连接', icon: Wifi },
  { id: 'chatbot', label: 'Bot 机器人设置', icon: Bot },
  { id: 'websearch', label: '网络搜索配置', icon: Search },
  { id: 'stardom', label: '星域配置', icon: Store },
  { id: 'language', label: '语言设置', icon: Languages },
  { id: 'update', label: '检查更新', icon: Download },
  { id: 'community', label: '加入社群', icon: Users },
] as const;

export function Settings() {
  useLocale();
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
          {t('settings.back')}
        </button>
        <div className="flex-1 space-y-1">
          {SECTIONS.map(({ id, label, icon: Icon }) => {
            const labels: Record<string, string> = {
              llm: t('settings.llm.title'),
              backend: t('settings.backend.title'),
              chatbot: t('settings.chatbot.title'),
              websearch: t('settings.webSearch.title'),
              stardom: t('settings.stardom.title'),
              language: t('settings.language.title'),
              update: t('settings.sections.update'),
              community: t('settings.about.community'),
            };
            return (
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
                {labels[id] || label}
              </button>
            );
          })}
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
          <StardomSettings id="settings-stardom" />

          {/* 语言设置 */}
          <LanguageSettings id="settings-language" />

          {/* 检查更新 */}
          <UpdateSettings id="settings-update" />

          {/* 加入社群 */}
          <Card id="settings-community">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Users className="h-5 w-5" />
                {t('settings.about.community')}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="flex items-center justify-center gap-10">
                {/* Star on GitHub — 左侧 */}
                <div className="flex flex-col items-center gap-3">
                  <button
                    type="button"
                    onClick={() => openExternalUrl('https://github.com/smancode/sman')}
                    className="group relative flex flex-col items-center gap-2 rounded-xl border border-border/60 bg-gradient-to-br from-yellow-50/80 via-orange-50/50 to-pink-50/80 dark:from-yellow-950/30 dark:via-orange-950/20 dark:to-pink-950/30 px-8 py-6 shadow-sm transition-all hover:shadow-md hover:scale-[1.02] active:scale-[0.98]"
                  >
                    <div className="flex items-center gap-1.5">
                      <Star className="h-7 w-7 text-yellow-500 fill-yellow-500 group-hover:animate-pulse" />
                      <svg viewBox="0 0 24 24" className="h-5 w-5 text-foreground/70" fill="currentColor"><path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z"/></svg>
                    </div>
                    <span className="text-base font-semibold text-foreground">
                      Star on GitHub
                    </span>
                    <GitHubStarCount />
                  </button>
                  <p className="text-xs text-muted-foreground text-center leading-relaxed max-w-[180px]">
                    {t('settings.about.community.githubStar')}
                  </p>
                </div>

                {/* 微信群 — 右侧 */}
                <div className="flex flex-col items-center gap-2">
                  <WechatGroupQR />
                  <p className="text-xs text-muted-foreground text-center leading-relaxed">
                    {t('settings.about.community.wechatScan')}
                    <br />
                    {t('settings.about.community.wechatTip')}
                  </p>
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
