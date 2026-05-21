import { useEffect, useState, useRef } from 'react';
import { StarfieldCanvas } from './StarfieldCanvas';
import { StardomDashboard } from './StardomDashboard';
import { useStardomDevMode } from '@/queries/use-hub';
import { useSettingsStore } from '@/stores/settings';
import { t } from '@/locales';

type Phase = 'checking' | 'locked' | 'unlocking' | 'unlocked';

const CHECK_DELAY_MS = 3000;
const FADE_DURATION_MS = 2000;

export function StardomEntry() {
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
      className="stardom-theme absolute inset-0 overflow-hidden"
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
                  color: '#4D96FF',
                  fontSize: '1.25rem',
                  fontWeight: 300,
                  letterSpacing: '0.15em',
                  textShadow: '0 0 20px rgba(155, 89, 182, 0.4), 0 0 40px rgba(255, 107, 157, 0.3)',
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
