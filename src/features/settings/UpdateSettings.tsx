import { useState } from 'react';
import { Download, RefreshCw, Server } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
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
  const [serverUrl, setServerUrl] = useState('');
  const [urlSaved, setUrlSaved] = useState(false);

  if (!isElectron) return null;

  const handleSaveServerUrl = () => {
    if (!serverUrl.trim()) return;
    window.sman?.updater?.setFeedURL?.(serverUrl.trim());
    setUrlSaved(true);
    setTimeout(() => setUrlSaved(false), 2000);
  };

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

        <div className="space-y-2 pt-2 border-t">
          <label className="text-sm font-medium flex items-center gap-1.5">
            <Server className="h-4 w-4 text-muted-foreground" />
            {t('update.settings.serverUrl')}
          </label>
          <div className="flex gap-2">
            <Input
              value={serverUrl}
              onChange={(e) => { setServerUrl(e.target.value); setUrlSaved(false); }}
              placeholder={t('update.settings.serverUrlPlaceholder')}
              className="flex-1"
            />
            <Button onClick={handleSaveServerUrl} variant="outline" size="sm" disabled={!serverUrl.trim()}>
              {urlSaved ? '✓' : t('common.confirm')}
            </Button>
          </div>
          {urlSaved && (
            <p className="text-xs text-green-600">{t('update.settings.serverUrlSaved')}</p>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
