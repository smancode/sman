import { useState } from 'react';
import { MessageCircle, Eye, EyeOff, Save, AlertCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Separator } from '@/components/ui/separator';
import { useSettingsStore } from '@/stores/settings';

export function ChatbotSettings() {
  const { settings, loading, error, updateChatbot, clearError } = useSettingsStore();
  const [showWecomSecret, setShowWecomSecret] = useState(false);
  const [showFeishuSecret, setShowFeishuSecret] = useState(false);
  const [saving, setSaving] = useState(false);

  const chatbot = settings?.chatbot;
  const enabled = chatbot?.enabled ?? false;
  const wecom = chatbot?.wecom ?? { enabled: false, botId: '', secret: '' };
  const feishu = chatbot?.feishu ?? { enabled: false, appId: '', appSecret: '' };

  const handleSave = async () => {
    setSaving(true);
    try {
      await updateChatbot({ enabled, wecom, feishu });
    } catch {
      // error handled by store
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <MessageCircle className="h-5 w-5" />
          Bot机器人配置
        </CardTitle>
        <CardDescription>配置企业微信和飞书机器人接入</CardDescription>
      </CardHeader>
      <CardContent className="space-y-6">
        {error && (
          <div className="flex items-center gap-2 p-3 rounded-lg bg-destructive/10 text-destructive text-sm">
            <AlertCircle className="h-4 w-4 shrink-0" />
            <span>{error}</span>
            <button onClick={clearError} className="ml-auto text-xs underline">关闭</button>
          </div>
        )}

        {/* Global toggle */}
        <div className="flex items-center justify-between">
          <Label>启用Bot机器人</Label>
          <Switch
            checked={enabled}
            onCheckedChange={(checked) => updateChatbot({ enabled: checked }).catch(() => {})}
          />
        </div>

        {/* WeCom */}
        <Separator />
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <Label className="text-base">企业微信</Label>
              <p className="text-sm text-muted-foreground">通过企业微信机器人接收和回复消息</p>
            </div>
            <Switch
              checked={wecom.enabled}
              onCheckedChange={(checked) =>
                updateChatbot({ wecom: { ...wecom, enabled: checked } }).catch(() => {})
              }
              disabled={!enabled}
            />
          </div>

          {wecom.enabled && enabled && (
            <div className="space-y-3 pl-1">
              <div className="space-y-2">
                <Label>Bot ID</Label>
                <Input
                  value={wecom.botId}
                  onChange={(e) =>
                    updateChatbot({ wecom: { ...wecom, botId: e.target.value } }).catch(() => {})
                  }
                  placeholder="输入企业微信 Bot ID"
                />
              </div>
              <div className="space-y-2">
                <Label>Secret</Label>
                <div className="relative">
                  <Input
                    type={showWecomSecret ? 'text' : 'password'}
                    value={wecom.secret}
                    onChange={(e) =>
                      updateChatbot({ wecom: { ...wecom, secret: e.target.value } }).catch(() => {})
                    }
                    placeholder="输入企业微信 Secret"
                    className="pr-10"
                  />
                  <button
                    type="button"
                    onClick={() => setShowWecomSecret(!showWecomSecret)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                  >
                    {showWecomSecret ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Feishu */}
        <Separator />
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <Label className="text-base">飞书</Label>
              <p className="text-sm text-muted-foreground">通过飞书机器人接收和回复消息</p>
            </div>
            <Switch
              checked={feishu.enabled}
              onCheckedChange={(checked) =>
                updateChatbot({ feishu: { ...feishu, enabled: checked } }).catch(() => {})
              }
              disabled={!enabled}
            />
          </div>

          {feishu.enabled && enabled && (
            <div className="space-y-3 pl-1">
              <div className="space-y-2">
                <Label>App ID</Label>
                <Input
                  value={feishu.appId}
                  onChange={(e) =>
                    updateChatbot({ feishu: { ...feishu, appId: e.target.value } }).catch(() => {})
                  }
                  placeholder="输入飞书 App ID"
                />
              </div>
              <div className="space-y-2">
                <Label>App Secret</Label>
                <div className="relative">
                  <Input
                    type={showFeishuSecret ? 'text' : 'password'}
                    value={feishu.appSecret}
                    onChange={(e) =>
                      updateChatbot({ feishu: { ...feishu, appSecret: e.target.value } }).catch(() => {})
                    }
                    placeholder="输入飞书 App Secret"
                    className="pr-10"
                  />
                  <button
                    type="button"
                    onClick={() => setShowFeishuSecret(!showFeishuSecret)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                  >
                    {showFeishuSecret ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                  </button>
                </div>
              </div>
            </div>
          )}
        </div>

        <div className="flex items-center gap-2 pt-4 border-t">
          <Button variant="outline" size="sm" onClick={handleSave} disabled={loading || saving}>
            {saving ? <Save className="h-4 w-4 mr-2 animate-pulse" /> : <Save className="h-4 w-4 mr-2" />}
            保存配置
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
