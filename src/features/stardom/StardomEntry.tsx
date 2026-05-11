import { useEffect, useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import { StarfieldCanvas } from './StarfieldCanvas';
import { StardomDashboard } from './StardomDashboard';
import { useStardomDevMode } from '@/queries/use-hub';
import { useSettingsStore } from '@/stores/settings';
import { t } from '@/locales';

type Phase = 'checking' | 'locked' | 'unlocking' | 'unlocked';

const CHECK_DELAY_MS = 3000;
const FADE_DURATION_MS = 2000;

export function StardomEntry() {
  const navigate = useNavigate();
  const fetchSettings = useSettingsStore((s) => s.fetchSettings);
  const settings = useSettingsStore((s) => s.settings);
  const { data: remoteEnabled, isError, isFetched } = useStardomDevMode();
  const [phase, setPhase] = useState<Phase>('checking');
  const [showMessage, setShowMessage] = useState(false);
  const [settingsLoaded, setSettingsLoaded] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout>>(undefined);

  // Refresh settings on mount to pick up manual config.json edits
  useEffect(() => {
    fetchSettings().then(() => setSettingsLoaded(true));
  }, [fetchSettings]);

  // Inject @keyframes for breathe animation on mount
  useEffect(() => {
    const style = document.createElement('style');
    style.textContent = `
      @keyframes stardom-breathe {
        0%, 100% { opacity: 0.4; }
        50% { opacity: 0.9; }
      }
      .stardom-coming-soon { animation: stardom-breathe 3s ease-in-out infinite; }
    `;
    document.head.appendChild(style);
    return () => { document.head.removeChild(style); };
  }, []);

  useEffect(() => {
    if (!settingsLoaded) return;
    if (!isFetched && !isError) return;

    const localEnabled = settings?.stardomEnabled === true;
    const enabled = localEnabled || remoteEnabled === true;

    timerRef.current = setTimeout(() => {
      if (enabled) {
        setPhase('unlocking');
        timerRef.current = setTimeout(() => {
          setPhase('unlocked');
        }, FADE_DURATION_MS);
      } else {
        setPhase('locked');
        setShowMessage(true);
      }
    }, CHECK_DELAY_MS);

    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [settingsLoaded, isFetched, isError, settings, remoteEnabled]);

  const isFading = phase === 'unlocking';
  const showDashboard = phase === 'unlocking' || phase === 'unlocked';
  const dashboardOpacity = phase === 'unlocked' ? 1 : phase === 'unlocking' ? 0 : 0;

  return (
    <div
      style={{
        position: 'relative',
        width: '100vw',
        height: '100vh',
        overflow: 'hidden',
      }}
    >
      {/* Dashboard layer (behind animation, fades in) */}
      {showDashboard && (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            opacity: dashboardOpacity,
            transition: isFading ? `opacity ${FADE_DURATION_MS}ms ease-in` : undefined,
          }}
        >
          <StardomDashboard />
        </div>
      )}

      {/* Animation layer (in front, fades out) */}
      {phase !== 'unlocked' && (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            opacity: isFading ? 0 : 1,
            transition: isFading ? `opacity ${FADE_DURATION_MS}ms ease-out` : undefined,
            zIndex: 1,
          }}
        >
          <StarfieldCanvas />
          <button
            onClick={() => navigate('/chat')}
            style={{
              position: 'absolute',
              top: 20,
              left: 20,
              background: 'rgba(0, 255, 65, 0.08)',
              border: '1px solid rgba(0, 255, 65, 0.2)',
              borderRadius: 8,
              color: '#00ff41',
              padding: '8px 14px',
              fontSize: '0.85rem',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              gap: 6,
              zIndex: 10,
              transition: 'background 0.2s',
            }}
            onMouseEnter={(e) => { e.currentTarget.style.background = 'rgba(0, 255, 65, 0.15)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.background = 'rgba(0, 255, 65, 0.08)'; }}
          >
            <ArrowLeft className="h-4 w-4" />
            {t('common.back')}
          </button>
          {showMessage && (
            <div
              style={{
                position: 'absolute',
                top: '50%',
                left: '50%',
                transform: 'translate(-50%, -50%)',
                textAlign: 'center',
              }}
            >
              <p
                className="stardom-coming-soon"
                style={{
                  color: '#00ff41',
                  fontSize: '1.25rem',
                  fontWeight: 300,
                  letterSpacing: '0.15em',
                  textShadow: '0 0 20px rgba(0, 255, 65, 0.5), 0 0 40px rgba(0, 255, 65, 0.2)',
                  margin: 0,
                }}
              >
                {t('stardom.entry.comingSoon')}
              </p>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
