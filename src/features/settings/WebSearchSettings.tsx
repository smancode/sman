import { useState } from 'react';
import { Search, Eye, EyeOff, Save, AlertCircle, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { useSettingsStore } from '@/stores/settings';
import { WEB_SEARCH_PROVIDER_OPTIONS, type WebSearchProvider } from '@/types/settings';
import { cn } from '@/lib/utils';

export function WebSearchSettings({ id }: { id?: string }) {
  const { settings, loading, error, updateWebSearch, clearError } = useSettingsStore();
  const [showApiKey, setShowApiKey] = useState(false);
  const [saving, setSaving] = useState(false);
  const [searxngTesting, setSearxngTesting] = useState(false);
  const [searxngError, setSearxngError] = useState<string | null>(null);

  const ws = settings?.webSearch;
  const provider = ws?.provider ?? 'builtin';
  const activeApiKeyField = provider === 'brave' ? ws?.braveApiKey : provider === 'tavily' ? ws?.tavilyApiKey : provider === 'baidu' ? ws?.baiduApiKey : ws?.bingApiKey;

  const handleSave = async () => {
    setSaving(true);
    try {
      await updateWebSearch({ ...ws });
    } catch {
      // error handled by store
    } finally {
      setSaving(false);
    }
  };

  const handleProviderChange = async (value: string) => {
    if (value === 'searxng') {
      setSearxngTesting(true);
      setSearxngError(null);
      try {
        const res = await fetch('/api/searxng/test', { method: 'POST' });
        const data = await res.json();
        if (!data.ok) {
          setSearxngError('连接 SearXNG 服务失败，请检查网络');
          setSearxngTesting(false);
          return;
        }
      } catch {
        setSearxngError('连接 SearXNG 服务失败，请检查网络');
        setSearxngTesting(false);
        return;
      }
      setSearxngTesting(false);
    } else {
      setSearxngError(null);
    }
    updateWebSearch({ provider: value as WebSearchProvider }).catch(() => {});
  };

  const handleApiKeyChange = (value: string) => {
    if (provider === 'brave') {
      updateWebSearch({ braveApiKey: value }).catch(() => {});
    } else if (provider === 'tavily') {
      updateWebSearch({ tavilyApiKey: value }).catch(() => {});
    } else if (provider === 'baidu') {
      updateWebSearch({ baiduApiKey: value }).catch(() => {});
    }
  };

  return (
    <Card id={id}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Search className="h-5 w-5" />
          网络搜索配置
        </CardTitle>
        <CardDescription>配置 Claude Code 的网络搜索能力</CardDescription>
      </CardHeader>
      <CardContent className="space-y-6">
        {error && (
          <div className="flex items-center gap-2 p-3 rounded-lg bg-destructive/10 text-destructive text-sm">
            <AlertCircle className="h-4 w-4 shrink-0" />
            <span>{error}</span>
            <button onClick={clearError} className="ml-auto text-xs underline">关闭</button>
          </div>
        )}

        <div className="space-y-3">
          <Label>搜索提供商</Label>
          <RadioGroup value={provider} onValueChange={handleProviderChange} className="space-y-2">
            {WEB_SEARCH_PROVIDER_OPTIONS.map((option) => (
              <Label
                key={option.value}
                htmlFor={option.value}
                className={cn(
                  'flex items-start gap-3 p-3 rounded-lg border cursor-pointer',
                  'hover:bg-muted/50 transition-colors',
                  provider === option.value && 'border-primary bg-primary/5',
                )}
              >
                <RadioGroupItem value={option.value} id={option.value} className="mt-0.5" />
                <div className="flex-1">
                  <div className="font-medium">{option.label}</div>
                  <div className="text-sm text-muted-foreground">
                    {option.description}
                    {option.link && (
                      <button
                        type="button"
                        className="ml-1 text-primary hover:underline"
                        onClick={(e) => {
                          e.stopPropagation();
                          const w = window as any;
                          if (w.sman?.openExternal) {
                            w.sman.openExternal(option.link!);
                          } else {
                            window.open(option.link, '_blank', 'noopener,noreferrer');
                          }
                        }}
                      >
                        了解详情
                      </button>
                    )}
                    {option.value === 'searxng' && searxngTesting && (
                      <span className="ml-2 text-muted-foreground inline-flex items-center gap-1">
                        <Loader2 className="h-3 w-3 animate-spin" /> 测试连通性…
                      </span>
                    )}
                    {option.value === 'searxng' && searxngError && !searxngTesting && (
                      <span className="ml-2 text-destructive">{searxngError}</span>
                    )}
                  </div>
                </div>
              </Label>
            ))}
          </RadioGroup>
        </div>

        {(provider === 'brave' || provider === 'tavily' || provider === 'baidu') && (
          <div className="space-y-2">
            <Label>{provider === 'brave' ? 'Brave' : provider === 'tavily' ? 'Tavily' : '百度'} API Key</Label>
            <div className="relative">
              <Input
                type={showApiKey ? 'text' : 'password'}
                value={activeApiKeyField ?? ''}
                onChange={(e) => handleApiKeyChange(e.target.value)}
                placeholder={`输入 ${provider === 'brave' ? 'Brave' : provider === 'tavily' ? 'Tavily' : '百度'} API Key`}
                className="pr-10"
              />
              <button
                type="button"
                onClick={() => setShowApiKey(!showApiKey)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              >
                {showApiKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
          </div>
        )}

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
