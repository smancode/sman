import { Download, RefreshCw } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { useUpdateStore } from '@/stores/update';
import { t } from '@/locales';
import { useUpdateStore } from '@/stores/update';
import { t } from '@/locales';

declare const __APP_VERSION__: string;
const APP_VERSION = __APP_VERSION__;

interface UpdateSettingsProps {
  id: string;
}

export function UpdateSettings({ id }: UpdateSettingsProps) {
  const status = useUpdateStore((s) => s.status);
  const newVersion = useUpdateStore((s) => s.newVersion);
  const errorMessage = useUpdateStore((s) => s.errorMessage);
  const isElectron = useUpdateStore((s) => s.isElectron);
  const checkUpdate = useUpdateStore((s) => s.checkUpdate);
  const installUpdate = useUpdateStore((s) => s.installUpdate);

  if (!isElectron) return null;

  return (
    <Card id={id}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Download className="h-5 w-5" />
          {t('update.settings.title')}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex items-center gap-2 text-sm">
          <span className="text-muted-foreground">{t('update.settings.currentVersion')}:</span>
          <span className="font-mono font-medium">v{APP_VERSION}</span>
        </div>

        <div className="flex items-center gap-3">
          {status === 'idle' && (
            <Button onClick={checkUpdate} variant="outline" size="sm">
              <RefreshCw className="h-4 w-4 mr-1.5" />
              {t('update.settings.checkButton')}
            </Button>
          )}
          {status === 'checking' && (
            <span className="text-sm text-muted-foreground flex items-center gap-2">
              <RefreshCw className="h-4 w-4 animate-spin" />
              {t('update.settings.checking')}
            </span>
          )}
          {status === 'downloading' && (
            <span className="text-sm text-muted-foreground flex items-center gap-2">
              <Download className="h-4 w-4 animate-bounce" />
              {t('update.settings.downloading')}
            </span>
          )}
          {status === 'ready' && newVersion && (
            <div className="flex items-center gap-3">
              <span className="text-sm text-primary font-medium">
                {t('update.settings.ready').replace('{version}', newVersion)}
              </span>
              <Button onClick={installUpdate} size="sm">
                <Download className="h-4 w-4 mr-1.5" />
                {t('update.settings.installButton')}
              </Button>
            </div>
          )}
          {status === 'not-available' && (
            <div className="flex items-center gap-3">
              <span className="text-sm text-muted-foreground">{t('update.settings.notAvailable')}</span>
              <Button onClick={checkUpdate} variant="outline" size="sm">
                <RefreshCw className="h-4 w-4 mr-1.5" />
                {t('update.settings.checkButton')}
              </Button>
            </div>
          )}
          {status === 'error' && (
            <div className="flex items-center gap-3">
              <span className="text-sm text-destructive">
                {t('update.settings.error').replace('{message}', errorMessage || '')}
              </span>
              <Button onClick={checkUpdate} variant="outline" size="sm">
                <RefreshCw className="h-4 w-4 mr-1.5" />
                {t('update.settings.retryButton')}
              </Button>
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
