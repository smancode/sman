import { useEffect, useState, useRef } from 'react';
import { StarfieldCanvas } from './StarfieldCanvas';
import { StardomDashboard } from './StardomDashboard';
import { useStardomDevMode } from '@/queries/use-hub';
import { t } from '@/locales';

type Phase = 'checking' | 'locked' | 'unlocking' | 'dashboard';

const CHECK_DELAY_MS = 3000;
const FADE_DURATION_MS = 2000;

export function StardomEntry() {
  const { data: devMode, isError, isFetched } = useStardomDevMode();
  const [phase, setPhase] = useState<Phase>('checking');
  const [showMessage, setShowMessage] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout>>(undefined);

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
    if (!isFetched && !isError) return;

    const enabled = devMode === true;

    timerRef.current = setTimeout(() => {
      if (enabled) {
        setPhase('unlocking');
        timerRef.current = setTimeout(() => {
          setPhase('dashboard');
        }, FADE_DURATION_MS);
      } else {
        setPhase('locked');
        setShowMessage(true);
      }
    }, CHECK_DELAY_MS);

    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [isFetched, isError, devMode]);

  if (phase === 'dashboard') {
    return <StardomDashboard />;
  }

  const isFading = phase === 'unlocking';

  return (
    <div
      style={{
        position: 'relative',
        width: '100vw',
        height: '100vh',
        overflow: 'hidden',
      }}
    >
      <div
        style={{
          position: 'absolute',
          inset: 0,
          transition: isFading ? `opacity ${FADE_DURATION_MS}ms ease-out` : undefined,
          opacity: isFading ? 0 : 1,
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
                color: '#00f0ff',
                fontSize: '1.25rem',
                fontWeight: 300,
                letterSpacing: '0.15em',
                textShadow: '0 0 20px rgba(0, 240, 255, 0.5), 0 0 40px rgba(0, 240, 255, 0.2)',
                margin: 0,
              }}
            >
              {t('stardom.entry.comingSoon')}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
