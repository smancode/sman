// src/features/stardom/StardomSettings.tsx
import { useState } from 'react';
import { Server, User, Shield, Save } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { useSettingsStore } from '@/stores/settings';
import { useWsConnection } from '@/stores/ws-connection';
import { t } from '@/locales';

export function StardomSettings({ id }: { id?: string }) {
  const settings = useSettingsStore((s) => s.settings);
  const client = useWsConnection((s) => s.client);
  const stardom = settings?.stardom;

  const [server, setServer] = useState(stardom?.server ?? '');
  const [agentName, setAgentName] = useState(stardom?.agentName ?? '');
  const [mode, setMode] = useState<string>(stardom?.mode ?? 'notify');
  const [maxSlots, setMaxSlots] = useState(stardom?.maxConcurrentTasks ?? 3);
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    setSaving(true);
    try {
      // save config to backend via settings.update WS message
      client?.send({
        type: 'settings.update',
        stardom: { server, agentName: agentName || undefined, mode, maxConcurrentTasks: maxSlots },
      });
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card id={id}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Server className="h-5 w-5" />
          {t("stardom.settings.title")}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-2">
          <Label>{t("stardom.settings.serverAddress")}</Label>
          <Input
            placeholder="stardom.company.com:5890"
            value={server}
            onChange={(e) => setServer(e.target.value)}
          />
          <p className="text-xs text-muted-foreground">{t("stardom.settings.serverHint")}</p>
        </div>

        <div className="space-y-2">
          <Label className="flex items-center gap-1"><User className="h-3.5 w-3.5" /> {t("stardom.settings.agentName")}</Label>
          <Input
            placeholder={t("stardom.settings.agentNamePlaceholder")}
            value={agentName}
            onChange={(e) => setAgentName(e.target.value)}
          />
        </div>

        <div className="space-y-2">
          <Label className="flex items-center gap-1"><Shield className="h-3.5 w-3.5" /> {t("stardom.settings.collabMode")}</Label>
          <Select value={mode} onValueChange={setMode}>
            <SelectTrigger>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="notify">{t("stardom.settings.modeNotify")}</SelectItem>
              <SelectItem value="auto">{t("stardom.settings.modeAuto")}</SelectItem>
              <SelectItem value="manual">{t("stardom.settings.modeManual")}</SelectItem>
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">
            {t("stardom.settings.modeHint")}
          </p>
        </div>

        <div className="space-y-2">
          <Label>{t("stardom.settings.maxSlots")}</Label>
          <Select value={String(maxSlots)} onValueChange={(v) => setMaxSlots(Number(v))}>
            <SelectTrigger className="w-24">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map(n => (
                <SelectItem key={n} value={String(n)}>{n}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="flex items-center gap-2 pt-4 border-t">
          <Button variant="outline" size="sm" onClick={handleSave} disabled={saving || !server}>
            {saving ? <Save className="h-4 w-4 mr-2 animate-pulse" /> : <Save className="h-4 w-4 mr-2" />}
            {t("stardom.settings.save")}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
