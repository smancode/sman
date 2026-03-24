import { useState } from 'react';
import { Bot, Eye, EyeOff, Save, AlertCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { useSettingsStore } from '@/stores/settings';
import { MODEL_OPTIONS } from '@/types/settings';

export function LLMSettings() {
  const { settings, loading, error, updateLlm, clearError } = useSettingsStore();
  const [showApiKey, setShowApiKey] = useState(false);
  const [saving, setSaving] = useState(false);

  const llm = settings?.llm;
  const localApiKey = llm?.apiKey ?? '';
  const localModel = llm?.model ?? 'claude-sonnet-4-6';
  const localBaseUrl = llm?.baseUrl ?? '';

  const handleSave = async () => {
    setSaving(true);
    try {
      await updateLlm({ apiKey: localApiKey, model: localModel, baseUrl: localBaseUrl || undefined });
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
          <Bot className="h-5 w-5" />
          模型配置
        </CardTitle>
        <CardDescription>配置 Claude Code 使用的模型和 API Key</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {error && (
          <div className="flex items-center gap-2 p-3 rounded-lg bg-destructive/10 text-destructive text-sm">
            <AlertCircle className="h-4 w-4 shrink-0" />
            <span>{error}</span>
            <button onClick={clearError} className="ml-auto text-xs underline">关闭</button>
          </div>
        )}

        <div className="space-y-2">
          <Label>模型</Label>
          <Select value={localModel} onValueChange={(v) => updateLlm({ model: v }).catch(() => {})}>
            <SelectTrigger>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {MODEL_OPTIONS.map((opt) => (
                <SelectItem key={opt.value} value={opt.value}>
                  {opt.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-2">
          <Label>API Key</Label>
          <div className="relative">
            <Input
              type={showApiKey ? 'text' : 'password'}
              value={localApiKey}
              onChange={(e) => updateLlm({ apiKey: e.target.value }).catch(() => {})}
              placeholder="sk-ant-..."
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

        <div className="space-y-2">
          <Label>Base URL (可选)</Label>
          <Input
            value={localBaseUrl}
            onChange={(e) => updateLlm({ baseUrl: e.target.value || undefined }).catch(() => {})}
            placeholder="留空使用 Anthropic 官方端点"
          />
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
