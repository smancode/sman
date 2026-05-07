// src/features/stardom/OnboardingGuide.tsx
import { useState, useEffect } from 'react';
import { useStardomStore } from '@/stores/stardom';
import { Button } from '@/components/ui/button';
import { Sparkles, X, HelpCircle } from 'lucide-react';
import { t } from '@/locales';

const ONBOARDED_KEY = 'stardom-onboarded';

export function OnboardingGuide() {
  const [open, setOpen] = useState(false);
  const { connection } = useStardomStore();

  useEffect(() => {
    if (connection.connected && !localStorage.getItem(ONBOARDED_KEY)) {
      setOpen(true);
    }
  }, [connection.connected]);

  const dismiss = () => {
    localStorage.setItem(ONBOARDED_KEY, 'true');
    setOpen(false);
  };

  return (
    <>
      {/* Help button — always visible in top-right */}
      {!open && connection.connected && (
        <button
          onClick={() => setOpen(true)}
          className="absolute top-14 right-4 z-20 p-1.5 rounded-full bg-background/80 backdrop-blur-sm border border-border hover:bg-muted transition-colors"
          title={t("stardom.guide.help")}
        >
          <HelpCircle className="h-4 w-4 text-muted-foreground" />
        </button>
      )}

      {/* Guide card — non-modal, top-left */}
      {open && (
        <div className="absolute top-14 left-4 z-20 w-80 bg-background/95 backdrop-blur-sm border border-border rounded-lg shadow-lg">
          <div className="flex items-center justify-between p-3 border-b">
            <div className="flex items-center gap-2">
              <Sparkles className="h-4 w-4 text-primary" />
              <h3 className="text-sm font-semibold">{t("stardom.guide.welcome")}</h3>
            </div>
            <Button variant="ghost" size="sm" className="h-5 w-5 p-0" onClick={dismiss}>
              <X className="h-3.5 w-3.5" />
            </Button>
          </div>

          <div className="p-3 space-y-2 text-xs text-muted-foreground">
            <p>{t("stardom.guide.intro")}</p>
            <ul className="list-disc list-inside space-y-0.5 ml-1">
              <li>{t("stardom.guide.feature1")}</li>
              <li>{t("stardom.guide.feature2")}</li>
              <li>{t("stardom.guide.feature3")}</li>
            </ul>

            <div className="border-t pt-2 mt-2">
              <p className="font-medium text-foreground text-xs">{t("stardom.guide.modeIntro")}</p>
              <ul className="list-disc list-inside space-y-0.5 ml-1 mt-1">
                <li><span className="font-medium">{t("stardom.settings.modeAuto")}</span>：{t("stardom.guide.autoDesc")}</li>
                <li><span className="font-medium">{t("stardom.settings.modeNotify")}</span>：{t("stardom.guide.notifyDesc")}</li>
                <li><span className="font-medium">{t("stardom.settings.modeManual")}</span>：{t("stardom.guide.manualDesc")}</li>
              </ul>
            </div>
          </div>

          <div className="p-3 border-t">
            <Button size="sm" className="w-full text-xs h-7" onClick={dismiss}>
              {t("stardom.guide.gotIt")}
            </Button>
          </div>
        </div>
      )}
    </>
  );
}
