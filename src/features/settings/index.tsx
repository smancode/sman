import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronLeft, Wifi, Bot, Cpu, Search, Sparkles, Users, Star, Languages, Download, Info, MessageSquare } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { cn } from '@/lib/utils';
import { LLMSettings } from './LLMSettings';
import { WebSearchSettings } from './WebSearchSettings';
import { ChatbotSettings } from './ChatbotSettings';
import { BackendSettings } from './BackendSettings';
import { LanguageSettings } from './LanguageSettings';
import { UpdateSettings } from './UpdateSettings';
import { AboutSettings } from './AboutSettings';
import { StardomSettings } from '@/features/stardom/StardomSettings';
import { useSettingsStore } from '@/stores/settings';
import { useScrollSpy } from '@/hooks/useScrollSpy';
import { PageLayout } from '@/components/common/PageLayout';
import { t, useLocale } from '@/locales';
import { useWsConnection } from '@/stores/ws-connection';

declare const __APP_VERSION__: string;
const APP_VERSION = __APP_VERSION__;

async function openExternalUrl(url: string) {
  const w = window as any;
  if (w.sman?.openExternal) {
    w.sman.openExternal(url);
    return;
  }
  try {
    const res = await fetch('/api/open-external', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url }),
    });
    if (res.ok) return;
  } catch {}
  window.open(url, '_blank', 'noopener,noreferrer');
}

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
  { id: 'llm', icon: Cpu },
  { id: 'backend', icon: Wifi },
  { id: 'chatbot', icon: Bot },
  { id: 'websearch', icon: Search },
  { id: 'stardom', icon: Sparkles },
  { id: 'language', icon: Languages },
  { id: 'update', icon: Download },
  { id: 'about', icon: Info },
  { id: 'feedback', icon: MessageSquare },
  { id: 'community', icon: Users },
] as const;

const SECTION_LABELS: Record<string, string> = {
  llm: 'settings.llm.title',
  backend: 'settings.backend.title',
  chatbot: 'settings.chatbot.title',
  websearch: 'settings.webSearch.title',
  stardom: 'settings.stardom.title',
  language: 'settings.language.title',
  update: 'settings.sections.update',
  about: 'settings.about.title',
  feedback: 'settings.feedback.title',
  community: 'settings.about.community',
};

function FeedbackForm() {
  const [text, setText] = useState('');
  const [status, setStatus] = useState<'idle' | 'submitting' | 'success' | 'error'>('idle');
  const lastSubmitRef = useRef(0);

  const handleSubmit = () => {
    const trimmed = text.trim();
    if (!trimmed) return;

    const now = Date.now();
    if (now - lastSubmitRef.current < 60_000) return;

    const client = useWsConnection.getState().client;
    if (!client) {
      setStatus('error');
      return;
    }

    lastSubmitRef.current = now;
    setStatus('submitting');

    const timeout = setTimeout(() => {
      setStatus('error');
    }, 3000);

    const handler = (msg: unknown) => {
      clearTimeout(timeout);
      const data = msg as Record<string, unknown>;
      if (data.success) {
        setStatus('success');
        setText('');
        setTimeout(() => setStatus('idle'), 3000);
      } else {
        setStatus('error');
      }
      client.off('feedback.submit.ack', handler);
    };

    client.on('feedback.submit.ack', handler);
    client.send({ type: 'feedback.submit', message: trimmed });
  };

  const canSubmit = status !== 'submitting' && text.trim().length > 0 && Date.now() - lastSubmitRef.current >= 60_000;

  return (
    <div className="space-y-3">
      <textarea
        className="w-full min-h-[100px] rounded-lg border border-border/60 bg-background px-3 py-2 text-sm resize-none focus:outline-none focus:ring-2 focus:ring-primary/30 placeholder:text-muted-foreground/50"
        placeholder={t('settings.feedback.placeholder')}
        value={text}
        onChange={(e) => setText(e.target.value)}
        disabled={status === 'submitting'}
      />
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={handleSubmit}
          disabled={!canSubmit}
          className="rounded-lg bg-primary px-4 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          {status === 'submitting' ? t('common.loading') : t('settings.feedback.submit')}
        </button>
        {status === 'success' && (
          <span className="text-sm text-green-600 dark:text-green-400">{t('settings.feedback.success')}</span>
        )}
        {status === 'error' && (
          <span className="text-sm text-red-500">{t('settings.feedback.error')}</span>
        )}
      </div>
    </div>
  );
}

export function Settings() {
  useLocale();
  const fetchSettings = useSettingsStore((s) => s.fetchSettings);
  const navigate = useNavigate();
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    fetchSettings();
  }, [fetchSettings]);

  const { activeId, scrollTo } = useScrollSpy({
    containerRef: scrollRef,
    items: SECTIONS.map(({ id }) => ({ id })),
    idPrefix: 'settings-',
    threshold: 0.3,
  });

  const activeSection = activeId ?? 'llm';

  return (
    <PageLayout
      scrollRef={scrollRef}
      sidebar={
        <>
          <button
            onClick={() => navigate(-1)}
            className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground mb-4 px-2"
          >
            <ChevronLeft className="h-4 w-4" />
            {t('settings.back')}
          </button>
          {SECTIONS.map(({ id, icon: Icon }) => (
            <button
              key={id}
              onClick={() => scrollTo(id)}
              className={cn(
                'flex items-center gap-2.5 w-full rounded-lg px-3 py-2 text-sm font-medium transition-colors',
                activeSection === id
                  ? 'bg-primary/10 text-primary'
                  : 'text-muted-foreground hover:bg-muted hover:text-foreground',
              )}
            >
              <Icon className="h-4 w-4" />
              {t(SECTION_LABELS[id])}
            </button>
          ))}
        </>
      }
      sidebarFooter={
        <div className="text-xs text-muted-foreground/60 px-3 pt-4 border-t border-border/40">
          v{APP_VERSION}
        </div>
      }
    >
      <LLMSettings id="settings-llm" />
      <BackendSettings id="settings-backend" />
      <ChatbotSettings id="settings-chatbot" />
      <WebSearchSettings id="settings-websearch" />
      <StardomSettings id="settings-stardom" />
      <LanguageSettings id="settings-language" />
      <UpdateSettings id="settings-update" />
      <AboutSettings id="settings-about" />
      <Card id="settings-feedback">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <MessageSquare className="h-5 w-5" />
            {t('settings.feedback.title')}
          </CardTitle>
        </CardHeader>
        <CardContent>
          <FeedbackForm />
        </CardContent>
      </Card>
      <Card id="settings-community">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Users className="h-5 w-5" />
            {t('settings.about.community')}
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex items-center justify-center gap-10">
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
    </PageLayout>
  );
}

export default Settings;
