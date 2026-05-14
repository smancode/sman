import { useState, useEffect } from 'react';
import { Download, RefreshCw, Save, CheckCircle2, AlertCircle, Loader2 } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useUpdateStore } from '@/stores/update';
import { useSettingsStore } from '@/stores/settings';
import { t } from '@/locales';

declare const __APP_VERSION__: string;
const APP_VERSION = __APP_VERSION__;

type SaveStatus = 'idle' | 'probing' | 'success' | 'error';

interface UpdateSettingsProps {
  id: string;
}

export function UpdateSettings({ id }: UpdateSettingsProps) {
  const status = useUpdateStore((s) => s.status);
  const newVersion = useUpdateStore((s) => s.newVersion);
  const releaseNotes = useUpdateStore((s) => s.releaseNotes);
  const errorMessage = useUpdateStore((s) => s.errorMessage);
  const isElectron = useUpdateStore((s) => s.isElectron);
  const checkUpdate = useUpdateStore((s) => s.checkUpdate);
  const installUpdate = useUpdateStore((s) => s.installUpdate);

  const settings = useSettingsStore((s) => s.settings);
  const [serverUrl, setServerUrl] = useState('');
  const [saveStatus, setSaveStatus] = useState<SaveStatus>('idle');
  const [errorMsg, setErrorMsg] = useState('');

  useEffect(() => {
    const baseUrl = settings?.hub?.serverBaseUrl
      || settings?.hub?.serverUrl
      || '';
    const updateUrl = settings?.hub?.updateUrl || '';
    setServerUrl(baseUrl || updateUrl);
  }, [settings?.hub?.serverBaseUrl, settings?.hub?.serverUrl, settings?.hub?.updateUrl]);

  if (!isElectron) return null;

  const handleSave = async () => {
    const trimmed = serverUrl.trim();
    if (!trimmed) return;

    setSaveStatus('probing');
    setErrorMsg('');

    const result = await window.sman?.updater?.probeServer(trimmed);
    if (!result?.ok) {
      setSaveStatus('error');
      setErrorMsg(result?.error || '');
      return;
    }

    const updateFeedUrl = trimmed.replace(/\/+$/, '') + '/updates/sman';

    await window.sman?.updater?.setFeedURL(updateFeedUrl);

    // Save to config.json via settings store
    const client = useSettingsStore.getState().settings;
    if (client) {
      useSettingsStore.getState().settings = {
        ...client,
        hub: {
          serverUrl: trimmed,
          updateUrl: client.hub?.updateUrl || '',
          serverBaseUrl: trimmed,
          fallbackUrl: client.hub?.fallbackUrl || '',
          enabled: true,
          adminToken: client.hub?.adminToken || '',
        },
      };
    }

    // Persist through backend
    try {
      const wsClient = (await import('@/stores/ws-connection')).useWsConnection.getState().client;
      if (wsClient) {
        wsClient.send({
          type: 'settings.update',
          hub: {
            serverBaseUrl: trimmed,
            serverUrl: trimmed,
            updateUrl: client?.hub?.updateUrl || '',
            fallbackUrl: client?.hub?.fallbackUrl || '',
            enabled: true,
            adminToken: client?.hub?.adminToken || '',
          },
        });
      }
    } catch { /* best effort */ }

    setSaveStatus('success');
    setTimeout(() => setSaveStatus('idle'), 3000);
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

        {/* Server URL config */}
        <div className="space-y-2">
          <label className="text-sm text-muted-foreground">{t('update.settings.serverUrl')}</label>
          <div className="flex items-center gap-2">
            <Input
              value={serverUrl}
              onChange={(e) => {
                setServerUrl(e.target.value);
                if (saveStatus !== 'idle') setSaveStatus('idle');
              }}
              placeholder={t('update.settings.serverUrlPlaceholder')}
              className="flex-1"
            />
            <Button
              onClick={handleSave}
              disabled={!serverUrl.trim() || saveStatus === 'probing'}
              size="sm"
              variant="outline"
            >
              {saveStatus === 'probing' ? (
                <Loader2 className="h-4 w-4 mr-1.5 animate-spin" />
              ) : (
                <Save className="h-4 w-4 mr-1.5" />
              )}
              {t('update.settings.serverUrlSaveButton')}
            </Button>
          </div>
          {saveStatus === 'probing' && (
            <div className="flex items-center gap-1.5 text-sm text-muted-foreground">
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
              {t('update.settings.serverUrlProbing')}
            </div>
          )}
          {saveStatus === 'success' && (
            <div className="flex items-center gap-1.5 text-sm text-green-600 font-medium">
              <CheckCircle2 className="h-3.5 w-3.5" />
              {t('update.settings.serverUrlSaved')}
            </div>
          )}
          {saveStatus === 'error' && (
            <div className="flex items-center gap-1.5 text-sm text-destructive">
              <AlertCircle className="h-3.5 w-3.5" />
              {t('update.settings.serverUrlProbeFailed')}
            </div>
          )}
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
            <div className="space-y-2">
              <div className="flex items-center gap-3">
                <span className="text-sm text-primary font-medium">
                  {t('update.settings.ready').replace('{version}', newVersion)}
                </span>
                <Button onClick={installUpdate} size="sm">
                  <Download className="h-4 w-4 mr-1.5" />
                  {t('update.settings.installButton')}
                </Button>
              </div>
              {releaseNotes && (
                <div className="text-sm text-muted-foreground bg-muted/50 rounded-md p-3 max-h-40 overflow-y-auto whitespace-pre-wrap">
                  {releaseNotes}
                </div>
              )}
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
