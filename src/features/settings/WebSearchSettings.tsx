import { useState } from 'react';
import { Search, Eye, EyeOff, Save, AlertCircle } from 'lucide-react';
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

  const ws = settings?.webSearch;
  const provider = ws?.provider ?? 'builtin';
  const activeApiKeyField = provider === 'brave' ? ws?.braveApiKey : provider === 'tavily' ? ws?.tavilyApiKey : ws?.bingApiKey;

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

  const handleProviderChange = (value: string) => {
    updateWebSearch({ provider: value as WebSearchProvider }).catch(() => {});
  };

  const handleApiKeyChange = (value: string) => {
    if (provider === 'brave') {
      updateWebSearch({ braveApiKey: value }).catch(() => {});
    } else if (provider === 'tavily') {
      updateWebSearch({ tavilyApiKey: value }).catch(() => {});
    } else if (provider === 'bing') {
      updateWebSearch({ bingApiKey: value }).catch(() => {});
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
                  <div className="text-sm text-muted-foreground">{option.description}</div>
                </div>
              </Label>
            ))}
          </RadioGroup>
        </div>

        {provider !== 'builtin' && (
          <div className="space-y-2">
            <Label>{provider === 'brave' ? 'Brave' : provider === 'tavily' ? 'Tavily' : 'Bing'} API Key</Label>
            <div className="relative">
              <Input
                type={showApiKey ? 'text' : 'password'}
                value={activeApiKeyField ?? ''}
                onChange={(e) => handleApiKeyChange(e.target.value)}
                placeholder={`输入 ${provider === 'brave' ? 'Brave' : provider === 'tavily' ? 'Tavily' : 'Bing'} API Key`}
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
