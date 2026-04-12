import { useState, useEffect, useCallback, useRef } from 'react';
import { MessageCircle, Eye, EyeOff, Save, AlertCircle, Smartphone, QrCode, Loader2, CheckCircle2, XCircle, Unplug } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Separator } from '@/components/ui/separator';
import { useSettingsStore } from '@/stores/settings';
import { useWsConnection } from '@/stores/ws-connection';

type WeixinConnectionStatus = 'idle' | 'connecting' | 'connected' | 'disconnected';

export function ChatbotSettings({ id }: { id?: string }) {
  const { settings, loading, error, updateChatbot, clearError } = useSettingsStore();
  const [showWecomSecret, setShowWecomSecret] = useState(false);
  const [showFeishuSecret, setShowFeishuSecret] = useState(false);
  const [saving, setSaving] = useState(false);

  // WeChat/Weixin state
  const [weixinStatus, setWeixinStatus] = useState<WeixinConnectionStatus>('idle');
  const [qrcodeUrl, setQrcodeUrl] = useState<string | null>(null);
  const [qrSessionKey, setQrSessionKey] = useState<string | null>(null);
  const [qrPolling, setQrPolling] = useState(false);
  const [qrMessage, setQrMessage] = useState<string | null>(null);
  const pollTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const chatbot = settings?.chatbot;
  const enabled = chatbot?.enabled ?? false;
  const wecom = chatbot?.wecom ?? { enabled: false, botId: '', secret: '' };
  const feishu = chatbot?.feishu ?? { enabled: false, appId: '', appSecret: '' };
  const weixin = chatbot?.weixin ?? { enabled: false };

  // Get WebSocket client for WeChat QR flow
  const getWs = useCallback(() => useWsConnection.getState().client, []);

  // Listen for WeChat status updates
  useEffect(() => {
    const client = getWs();
    if (!client) return;

    const handleStatus = (...args: unknown[]) => {
      const msg = args[0] as Record<string, unknown>;
      if (msg.status) setWeixinStatus(msg.status as WeixinConnectionStatus);
    };
    const handleQrResponse = (...args: unknown[]) => {
      const msg = args[0] as Record<string, unknown>;
      setQrcodeUrl(msg.qrcodeUrl as string);
      setQrSessionKey(msg.sessionKey as string);
      setQrPolling(true);
      setQrMessage('请用微信扫描二维码');
    };
    const handleQrStatus = (...args: unknown[]) => {
      const msg = args[0] as Record<string, unknown>;
      const status = msg.status as string;
      if (status === 'confirmed') {
        setWeixinStatus('connected');
        setQrPolling(false);
        setQrMessage('连接成功！');
        setQrcodeUrl(null);
        setQrSessionKey(null);
      } else if (status === 'expired') {
        setQrPolling(false);
        setQrMessage('二维码已过期，请重新获取');
      } else if (status === 'scaned') {
        setQrMessage('已扫码，请在微信上确认...');
      } else if (status === 'error') {
        setQrPolling(false);
        setQrMessage((msg.message as string) || '发生错误');
      } else {
        // 'wait', 'scaned_but_redirect', etc. — keep polling, update message
        setQrMessage((msg.message as string) || '等待中...');
      }
    };
    const handleQrError = (...args: unknown[]) => {
      const msg = args[0] as Record<string, unknown>;
      setWeixinStatus('idle');
      setQrMessage((msg.error as string) || '获取二维码失败');
    };

    client.on('chatbot.weixin.status', handleStatus);
    client.on('chatbot.weixin.qr.response', handleQrResponse);
    client.on('chatbot.weixin.qr.status', handleQrStatus);
    client.on('chatbot.weixin.qr.error', handleQrError);

    // Get initial status
    if (weixin.enabled && enabled) {
      client.send({ type: 'chatbot.weixin.getStatus' });
    }

    return () => {
      client.off('chatbot.weixin.status', handleStatus);
      client.off('chatbot.weixin.qr.response', handleQrResponse);
      client.off('chatbot.weixin.qr.status', handleQrStatus);
      client.off('chatbot.weixin.qr.error', handleQrError);
    };
  }, [getWs, weixin.enabled, enabled]);

  // Poll for QR login status
  useEffect(() => {
    if (!qrPolling || !qrSessionKey) return;

    const client = getWs();
    if (!client) return;

    const poll = () => {
      client.send({ type: 'chatbot.weixin.qr.poll', sessionKey: qrSessionKey });
    };

    // Poll every 3 seconds
    poll();
    pollTimerRef.current = setInterval(poll, 3000);

    return () => {
      if (pollTimerRef.current) {
        clearInterval(pollTimerRef.current);
        pollTimerRef.current = null;
      }
    };
  }, [qrPolling, qrSessionKey, getWs()]);

  const handleWeixinConnect = () => {
    const client = getWs();
    if (!client) return;
    setWeixinStatus('connecting');
    setQrcodeUrl(null);
    setQrSessionKey(null);
    setQrMessage(null);
    client.send({ type: 'chatbot.weixin.qr.request' });

    // Timeout: if no response in 15s, reset to idle
    setTimeout(() => {
      setWeixinStatus((prev) => prev === 'connecting' ? 'idle' : prev);
      setQrMessage((prev) => prev === null ? '连接超时，请重试' : prev);
    }, 15_000);
  };

  const handleWeixinDisconnect = () => {
    const client = getWs();
    if (!client) return;
    client.send({ type: 'chatbot.weixin.disconnect' });
    setWeixinStatus('idle');
    setQrcodeUrl(null);
    setQrSessionKey(null);
    setQrPolling(false);
    setQrMessage(null);
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      await updateChatbot({ enabled, wecom, feishu, weixin });
    } catch {
      // error handled by store
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card id={id}>
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

        {/* WeChat / Weixin */}
        <Separator />
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <div>
              <Label className="text-base">微信</Label>
              <p className="text-sm text-muted-foreground">通过微信Bot接收和回复消息</p>
            </div>
            <Switch
              checked={weixin.enabled}
              onCheckedChange={(checked) =>
                updateChatbot({ weixin: { ...weixin, enabled: checked } }).catch(() => {})
              }
              disabled={!enabled}
            />
          </div>

          {weixin.enabled && enabled && (
            <div className="space-y-3 pl-1">
              {/* Connection status */}
              <div className="flex items-center gap-2 text-sm">
                {weixinStatus === 'connected' ? (
                  <>
                    <CheckCircle2 className="h-4 w-4 text-green-500" />
                    <span className="text-green-600">已连接</span>
                  </>
                ) : weixinStatus === 'connecting' ? (
                  <>
                    <Loader2 className="h-4 w-4 animate-spin text-yellow-500" />
                    <span className="text-yellow-600">连接中...</span>
                  </>
                ) : weixinStatus === 'disconnected' ? (
                  <>
                    <XCircle className="h-4 w-4 text-red-500" />
                    <span className="text-red-600">会话已过期，请重新连接</span>
                  </>
                ) : (
                  <>
                    <Unplug className="h-4 w-4 text-muted-foreground" />
                    <span className="text-muted-foreground">未连接</span>
                  </>
                )}
              </div>

              {/* QR Code flow */}
              {weixinStatus !== 'connected' && !qrcodeUrl && (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleWeixinConnect}
                  disabled={weixinStatus === 'connecting'}
                >
                  {weixinStatus === 'connecting' ? (
                    <Loader2 className="h-4 w-4 mr-2 animate-spin" />
                  ) : (
                    <QrCode className="h-4 w-4 mr-2" />
                  )}
                  连接微信
                </Button>
              )}

              {/* QR Code display */}
              {qrcodeUrl && (
                <div className="flex flex-col items-center gap-3 p-4 rounded-lg border bg-muted/30">
                  <p className="text-sm text-muted-foreground">请用微信扫描二维码</p>
                  <img
                    src={qrcodeUrl}
                    alt="微信登录二维码"
                    className="w-48 h-48 rounded"
                  />
                  {qrMessage && (
                    <p className="text-sm text-center text-muted-foreground">{qrMessage}</p>
                  )}
                </div>
              )}

              {/* Disconnect button */}
              {weixinStatus === 'connected' && (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleWeixinDisconnect}
                >
                  <Unplug className="h-4 w-4 mr-2" />
                  断开连接
                </Button>
              )}
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
