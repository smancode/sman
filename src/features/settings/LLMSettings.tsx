import { useState, useEffect } from 'react';
import { Bot, Eye, EyeOff, Loader2, CheckCircle2, XCircle, AlertCircle, Trash2, Plus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { useSettingsStore } from '@/stores/settings';
import type { DetectedCapabilities, LlmProfile } from '@/types/settings';

function CapabilitiesDisplay({ capabilities }: { capabilities?: DetectedCapabilities }) {
  if (!capabilities) return null;

  const items = [
    { label: '文本', supported: capabilities.text },
    { label: '图片', supported: capabilities.image },
    { label: 'PDF', supported: capabilities.pdf },
    { label: '音频', supported: capabilities.audio },
    { label: '视频', supported: capabilities.video },
  ];

  return (
    <div className="flex items-center gap-3 flex-wrap text-sm">
      <span className="text-muted-foreground">模态能力:</span>
      {items.map(({ label, supported }) => (
        <span key={label} className={`flex items-center gap-1 ${supported ? 'text-emerald-600' : 'text-muted-foreground/50'}`}>
          {supported ? <CheckCircle2 className="h-3.5 w-3.5" /> : <XCircle className="h-3.5 w-3.5" />}
          {label}
        </span>
      ))}
      <span className="text-xs text-muted-foreground/60">({capabilities.source})</span>
    </div>
  );
}

export function LLMSettings() {
  const { settings, testAndSaveLlm, selectLlmProfile, deleteLlmProfile } = useSettingsStore();

  // Local draft state
  const [draftApiKey, setDraftApiKey] = useState('');
  const [draftModel, setDraftModel] = useState('');
  const [draftBaseUrl, setDraftBaseUrl] = useState('');
  const [draftProfileName, setDraftProfileName] = useState('');
  const [showApiKey, setShowApiKey] = useState(false);

  // Selected profile name (empty = not from saved list)
  const [selectedProfile, setSelectedProfile] = useState('');
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ success: boolean; error?: string; capabilities?: DetectedCapabilities } | null>(null);

  const savedLlms: LlmProfile[] = settings?.savedLlms ?? [];

  // Sync from store settings → local draft when profile changes
  useEffect(() => {
    if (selectedProfile) {
      const profile = savedLlms.find(p => p.name === selectedProfile);
      if (profile) {
        setDraftApiKey(profile.apiKey ?? '');
        setDraftModel(profile.model ?? '');
        setDraftBaseUrl(profile.baseUrl ?? '');
        setDraftProfileName(profile.name);
      }
    } else {
      // Show current active LLM in form
      if (settings?.llm) {
        setDraftApiKey(settings.llm.apiKey ?? '');
        setDraftModel(settings.llm.model ?? '');
        setDraftBaseUrl(settings.llm.baseUrl ?? '');
        setDraftProfileName(settings.currentLlmProfile ?? '');
      }
    }
  }, [selectedProfile, settings?.llm, settings?.currentLlmProfile, savedLlms]);

  // Initialize selectedProfile from settings on mount/refresh
  useEffect(() => {
    if (settings?.currentLlmProfile && !selectedProfile) {
      setSelectedProfile(settings.currentLlmProfile);
    }
  }, [settings]);

  const handleProfileChange = (name: string) => {
    if (name === '__new__') {
      setSelectedProfile('');
      setDraftApiKey('');
      setDraftModel('');
      setDraftBaseUrl('');
      setDraftProfileName('');
    } else {
      setSelectedProfile(name);
    }
    setTestResult(null);
  };

  const handleTestAndSave = async () => {
    setTesting(true);
    setTestResult(null);
    try {
      // If no profile name entered, use existing profile name or default
      const profileName = draftProfileName || selectedProfile || '默认';
      const result = await testAndSaveLlm(draftApiKey, draftModel, draftBaseUrl || undefined, profileName);
      setTestResult(result);
      if (result.success) {
        setDraftProfileName(profileName);
        setSelectedProfile(profileName);
      }
    } catch {
      setTestResult({ success: false, error: '连接失败' });
    } finally {
      setTesting(false);
    }
  };

  const handleDeleteProfile = async () => {
    if (!selectedProfile) return;
    if (!confirm(`确定删除配置「${selectedProfile}」？`)) return;
    try {
      await deleteLlmProfile(selectedProfile);
      setSelectedProfile('');
      setDraftApiKey('');
      setDraftModel('');
      setDraftBaseUrl('');
      setDraftProfileName('');
      setTestResult(null);
    } catch {
      // ignore
    }
  };

  const hasChanges =
    draftApiKey !== (settings?.llm?.apiKey ?? '') ||
    draftModel !== (settings?.llm?.model ?? '') ||
    draftBaseUrl !== (settings?.llm?.baseUrl ?? '');

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Bot className="h-5 w-5" />
          模型配置
        </CardTitle>
        <CardDescription>配置使用的模型和 API Key，保存时自动检测兼容性和模态能力</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* Saved LLM profiles dropdown */}
        <div className="space-y-2">
          <Label>已保存的配置</Label>
          <div className="flex items-center gap-2">
            <Select value={selectedProfile} onValueChange={handleProfileChange}>
              <SelectTrigger className="flex-1">
                <SelectValue placeholder="选择已保存的配置，或新建..." />
              </SelectTrigger>
              <SelectContent>
                {savedLlms.map(p => (
                  <SelectItem key={p.name} value={p.name}>
                    {p.name} ({p.model})
                  </SelectItem>
                ))}
                <SelectItem value="__new__">
                  <span className="flex items-center gap-1 text-muted-foreground">
                    <Plus className="h-3.5 w-3.5" /> 新建配置...
                  </span>
                </SelectItem>
              </SelectContent>
            </Select>
            {selectedProfile && (
              <Button variant="outline" size="sm" onClick={handleDeleteProfile} title="删除当前配置">
                <Trash2 className="h-4 w-4 text-destructive" />
              </Button>
            )}
          </div>
          {selectedProfile && (
            <p className="text-xs text-muted-foreground">
              当前使用: <span className="font-medium text-foreground">{selectedProfile}</span>
            </p>
          )}
        </div>

        {/* Profile name (only when creating new or editing) */}
        <div className="space-y-2">
          <Label>配置名称</Label>
          <Input
            value={draftProfileName}
            onChange={(e) => setDraftProfileName(e.target.value)}
            placeholder="给这个配置起个名字，如 Anthropic、DeepSeek..."
          />
        </div>

        {/* Base URL */}
        <div className="space-y-2">
          <Label>Base URL</Label>
          <Input
            value={draftBaseUrl}
            onChange={(e) => setDraftBaseUrl(e.target.value)}
            placeholder="https://api.anthropic.com（留空使用默认）"
          />
          <p className="text-xs text-muted-foreground">支持 Anthropic 兼容格式的 API 地址</p>
        </div>

        {/* API Key */}
        <div className="space-y-2">
          <Label>API Key</Label>
          <div className="relative">
            <Input
              type={showApiKey ? 'text' : 'password'}
              value={draftApiKey}
              onChange={(e) => setDraftApiKey(e.target.value)}
              placeholder="sk-..."
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

        {/* Model */}
        <div className="space-y-2">
          <Label>Model</Label>
          <Input
            value={draftModel}
            onChange={(e) => setDraftModel(e.target.value)}
            placeholder="claude-sonnet-4-6"
          />
        </div>

        {/* Test result */}
        {testResult && (
          <div className={`flex items-start gap-2 p-3 rounded-lg text-sm ${testResult.success ? 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-400' : 'bg-destructive/10 text-destructive'}`}>
            {testResult.success
              ? <CheckCircle2 className="h-4 w-4 shrink-0 mt-0.5" />
              : <XCircle className="h-4 w-4 shrink-0 mt-0.5" />}
            <div className="flex flex-col gap-1">
              <span>{testResult.success ? '连接成功' : testResult.error}</span>
              {testResult.success && <CapabilitiesDisplay capabilities={testResult.capabilities} />}
              {testResult.success && !testResult.error && (
                <span className="text-xs opacity-60">配置已保存</span>
              )}
            </div>
          </div>
        )}

        {/* Persisted capabilities (when no active test result) */}
        {!testResult && settings?.llm?.capabilities && (
          <div className="flex items-start gap-2 p-3 rounded-lg bg-muted/50 text-sm">
            <AlertCircle className="h-4 w-4 shrink-0 mt-0.5 text-muted-foreground" />
            <CapabilitiesDisplay capabilities={settings.llm.capabilities} />
          </div>
        )}

        <div className="flex items-center gap-2 pt-4 border-t">
          <Button
            size="sm"
            onClick={handleTestAndSave}
            disabled={testing || !draftApiKey || !draftModel}
          >
            {testing
              ? <><Loader2 className="h-4 w-4 mr-2 animate-spin" />检测中...</>
              : <span>测试并保存</span>}
          </Button>
          {hasChanges && !testing && (
            <span className="text-xs text-muted-foreground">有未保存的更改</span>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
