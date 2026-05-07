import { useNavigate } from 'react-router-dom';
import { X, Download, AlertCircle } from 'lucide-react';
import { useUpdateStore } from '@/stores/update';
import { t } from '@/locales';

export function UpdateBanner() {
  const navigate = useNavigate();
  const status = useUpdateStore((s) => s.status);
  const newVersion = useUpdateStore((s) => s.newVersion);
  const bannerDismissed = useUpdateStore((s) => s.bannerDismissed);
  const isElectron = useUpdateStore((s) => s.isElectron);
  const dismissBanner = useUpdateStore((s) => s.dismissBanner);

  if (!isElectron || bannerDismissed) return null;

  if (status === 'ready' && newVersion) {
    return (
      <div className="flex items-center justify-between px-4 py-2 bg-primary/10 border-b border-primary/20 text-sm animate-in slide-in-from-top duration-300">
        <div className="flex items-center gap-2">
          <Download className="h-4 w-4 text-primary" />
          <span>{t('update.banner.ready').replace('{version}', newVersion)}</span>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => navigate('/settings')}
            className="px-3 py-1 text-xs font-medium bg-primary text-primary-foreground rounded-md hover:bg-primary/90 transition-colors"
          >
            {t('update.banner.goSettings')}
          </button>
          <button onClick={dismissBanner} className="p-1 rounded hover:bg-muted transition-colors">
            <X className="h-3.5 w-3.5 text-muted-foreground" />
          </button>
        </div>
      </div>
    );
  }

  if (status === 'error') {
    return (
      <div className="flex items-center justify-between px-4 py-2 bg-destructive/10 border-b border-destructive/20 text-sm animate-in slide-in-from-top duration-300">
        <div className="flex items-center gap-2">
          <AlertCircle className="h-4 w-4 text-destructive" />
          <span className="text-destructive">{t('update.banner.error')}</span>
        </div>
        <button onClick={dismissBanner} className="p-1 rounded hover:bg-muted transition-colors">
          <X className="h-3.5 w-3.5 text-muted-foreground" />
        </button>
      </div>
    );
  }

  return null;
}
