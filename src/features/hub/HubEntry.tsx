import { useEffect, useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import { StarfieldCanvas } from '../stardom/StarfieldCanvas';
import { HubDashboard } from './HubDashboard';
import { useHubDevMode } from '@/queries/use-hub';
import { t } from '@/locales';

type Phase = 'checking' | 'locked' | 'unlocking' | 'unlocked';

const CHECK_DELAY_MS = 3000;
const FADE_DURATION_MS = 2000;

export function HubEntry() {
  const navigate = useNavigate();
  const { data: remoteEnabled, isError, isFetched } = useHubDevMode();
  const [phase, setPhase] = useState<Phase>('checking');
  const [showMessage, setShowMessage] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout>>(undefined);

  useEffect(() => {
    const style = document.createElement('style');
    style.textContent = `
      @keyframes hub-breathe {
        0%, 100% { opacity: 0.4; }
        50% { opacity: 0.9; }
      }
      .hub-coming-soon { animation: hub-breathe 3s ease-in-out infinite; }
    `;
    document.head.appendChild(style);
    return () => { document.head.removeChild(style); };
  }, []);

  useEffect(() => {
    if (!isFetched && !isError) return;

    const enabled = remoteEnabled === true;

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
  }, [isFetched, isError, remoteEnabled]);

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
      {showDashboard && (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            opacity: dashboardOpacity,
            transition: isFading ? `opacity ${FADE_DURATION_MS}ms ease-in` : undefined,
          }}
        >
          <HubDashboard />
        </div>
      )}

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
              background: 'rgba(77, 150, 255, 0.08)',
              border: '1px solid rgba(255, 107, 157, 0.3)',
              borderRadius: 8,
              color: '#4D96FF',
              padding: '8px 14px',
              fontSize: '0.85rem',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              gap: 6,
              zIndex: 10,
              transition: 'background 0.2s',
            }}
            onMouseEnter={(e) => { e.currentTarget.style.background = 'rgba(77, 150, 255, 0.18)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.background = 'rgba(77, 150, 255, 0.08)'; }}
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
                className="hub-coming-soon"
                style={{
                  color: '#4D96FF',
                  fontSize: '1.25rem',
                  fontWeight: 300,
                  letterSpacing: '0.15em',
                  textShadow: '0 0 20px rgba(155, 89, 182, 0.4), 0 0 40px rgba(255, 107, 157, 0.3)',
                  margin: 0,
                }}
              >
                {t('hub.entry.comingSoon')}
              </p>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
