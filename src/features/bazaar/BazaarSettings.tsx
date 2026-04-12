// src/features/bazaar/BazaarSettings.tsx
import { useState } from 'react';
import { Server, User, Shield, Save } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { useSettingsStore } from '@/stores/settings';
import { useWsConnection } from '@/stores/ws-connection';

export function BazaarSettings({ id }: { id?: string }) {
  const settings = useSettingsStore((s) => s.settings);
  const client = useWsConnection((s) => s.client);
  const bazaar = settings?.bazaar;

  const [server, setServer] = useState(bazaar?.server ?? '');
  const [agentName, setAgentName] = useState(bazaar?.agentName ?? '');
  const [mode, setMode] = useState<string>(bazaar?.mode ?? 'notify');
  const [maxSlots, setMaxSlots] = useState(bazaar?.maxConcurrentTasks ?? 3);
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    setSaving(true);
    try {
      // 通过 settings.update WS 消息保存配置到后端
      client?.send({
        type: 'settings.update',
        bazaar: { server, agentName: agentName || undefined, mode, maxConcurrentTasks: maxSlots },
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
          集市配置
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-2">
          <Label>集市服务器地址</Label>
          <Input
            placeholder="bazaar.company.com:5890"
            value={server}
            onChange={(e) => setServer(e.target.value)}
          />
          <p className="text-xs text-muted-foreground">企业内网集市服务器地址</p>
        </div>

        <div className="space-y-2">
          <Label className="flex items-center gap-1"><User className="h-3.5 w-3.5" /> Agent 显示名</Label>
          <Input
            placeholder="你的名字（可选）"
            value={agentName}
            onChange={(e) => setAgentName(e.target.value)}
          />
        </div>

        <div className="space-y-2">
          <Label className="flex items-center gap-1"><Shield className="h-3.5 w-3.5" /> 协作模式</Label>
          <Select value={mode} onValueChange={setMode}>
            <SelectTrigger>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="notify">半自动（推荐）</SelectItem>
              <SelectItem value="auto">全自动</SelectItem>
              <SelectItem value="manual">手动</SelectItem>
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">
            半自动：接任务前通知你，30秒无响应自动接
          </p>
        </div>

        <div className="space-y-2">
          <Label>最大并发槽位</Label>
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
            保存配置
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
