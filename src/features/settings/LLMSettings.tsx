import { useState, useEffect, useRef } from 'react';
import { Bot, Eye, EyeOff, Loader2, CheckCircle2, XCircle, AlertCircle, Trash2, Plus, ChevronDown, Globe } from 'lucide-react';
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
import { t } from '@/locales';

function CapabilitiesDisplay({ capabilities }: { capabilities?: DetectedCapabilities }) {
  if (!capabilities) return null;

  const items = [
    { label: t('settings.llm.capability.text'), supported: capabilities.text },
    { label: t('settings.llm.capability.image'), supported: capabilities.image },
    { label: t('settings.llm.capability.pdf'), supported: capabilities.pdf },
    { label: t('settings.llm.capability.audio'), supported: capabilities.audio },
    { label: t('settings.llm.capability.video'), supported: capabilities.video },
  ];

  return (
    <div className="flex items-center gap-3 flex-wrap text-sm">
      <span className="text-muted-foreground">{t('settings.llm.capabilities.label')}</span>
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

export function LLMSettings({ id }: { id?: string }) {
  const { settings, testAndSaveLlm, fetchModels, selectLlmProfile, deleteLlmProfile } = useSettingsStore();

  // Local draft state
  const [draftApiKey, setDraftApiKey] = useState('');
  const [draftModel, setDraftModel] = useState('');
  const [draftBaseUrl, setDraftBaseUrl] = useState('');
  const [draftProfileName, setDraftProfileName] = useState('');
  const [showApiKey, setShowApiKey] = useState(false);

  // Models dropdown state
  const [fetchedModels, setFetchedModels] = useState<{ id: string; displayName?: string }[]>([]);
  const [loadingModels, setLoadingModels] = useState(false);
  const [showModelDropdown, setShowModelDropdown] = useState(false);
  const [modelsUnsupported, setModelsUnsupported] = useState(false);
  const modelDropdownRef = useRef<HTMLDivElement>(null);

  // Whether user explicitly chose "new profile" mode
  const isNewProfile = useRef(false);

  // Base URL preset dropdown
  const [showBaseUrlPreset, setShowBaseUrlPreset] = useState(false);
  const baseUrlPresetRef = useRef<HTMLDivElement>(null);

  const baseUrlPresets = [
    { label: 'DeepSeek', url: 'https://api.deepseek.com/anthropic' },
    { label: 'GLM', url: 'https://open.bigmodel.cn/api/anthropic' },
    { label: 'MiniMax', url: 'https://api.minimaxi.com/anthropic' },
    { label: 'KIMI', url: 'https://api.kimi.com/coding' },
    { label: 'Claude', url: 'https://api.anthropic.com/' },
    { label: '腾讯云', url: 'https://api.lkeap.cloud.tencent.com/coding/anthropic' },
    { label: '火山方舟', url: 'https://ark.cn-beijing.volces.com/api/coding' },
    { label: '阿里云百炼', url: 'https://coding.dashscope.aliyuncs.com/apps/anthropic' },
    { label: 'OpenRouter', url: 'https://openrouter.ai/api' },
    { label: '硅基流动', url: 'https://api.siliconflow.cn' },
  ];

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
    } else if (!isNewProfile.current) {
      // Show current active LLM in form (only on init, not when user clicked "new")
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

  const handleProfileChange = async (name: string) => {
    if (name === '__new__') {
      isNewProfile.current = true;
      setSelectedProfile('');
      setDraftApiKey('');
      setDraftModel('');
      setDraftBaseUrl('');
      setDraftProfileName('');
      setTestResult(null);
      return;
    } else {
      isNewProfile.current = false;
      setSelectedProfile(name);
      // Immediately activate the selected profile
      await selectLlmProfile(name);
    }
    setTestResult(null);
  };

  const handleTestAndSave = async () => {
    setTesting(true);
    setTestResult(null);
    try {
      // Trim all inputs before saving
      const apiKey = draftApiKey.trim();
      const model = draftModel.trim();
      const baseUrl = draftBaseUrl.trim();
      const profileName = (draftProfileName || selectedProfile || '默认').trim();

      // Sync trimmed values back to draft state
      setDraftApiKey(apiKey);
      setDraftModel(model);
      setDraftBaseUrl(baseUrl);
      setDraftProfileName(profileName);

      const result = await testAndSaveLlm(apiKey, model, baseUrl || undefined, profileName);
      setTestResult(result);
      if (result.success) {
        setDraftProfileName(profileName);
        setSelectedProfile(profileName);
      }
    } catch {
      setTestResult({ success: false, error: t('settings.llm.test.failed') });
    } finally {
      setTesting(false);
    }
  };

  const handleDeleteProfile = async () => {
    if (!selectedProfile) return;
    if (!confirm(t('settings.llm.deleteProfile.confirm', { name: selectedProfile }))) return;
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

  const handleFetchModels = async () => {
    const apiKey = draftApiKey.trim();
    const baseUrl = draftBaseUrl.trim();
    if (!apiKey) return;
    setDraftApiKey(apiKey);
    setDraftBaseUrl(baseUrl);
    setLoadingModels(true);
    setFetchedModels([]);
    setModelsUnsupported(false);
    try {
      const result = await fetchModels(apiKey, baseUrl || undefined);
      setFetchedModels(result.models);
      setModelsUnsupported(result.unsupported ?? false);
      setShowModelDropdown(result.models.length > 0);
    } catch {
      // ignore
    } finally {
      setLoadingModels(false);
    }
  };

  const handleModelSelect = (modelId: string) => {
    setDraftModel(modelId);
    setShowModelDropdown(false);
  };

  // Close model dropdown on outside click
  useEffect(() => {
    if (!showModelDropdown) return;
    const handleClick = (e: MouseEvent) => {
      if (modelDropdownRef.current && !modelDropdownRef.current.contains(e.target as Node)) {
        setShowModelDropdown(false);
      }
    };
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [showModelDropdown]);

  // Close base URL preset dropdown on outside click
  useEffect(() => {
    if (!showBaseUrlPreset) return;
    const handleClick = (e: MouseEvent) => {
      if (baseUrlPresetRef.current && !baseUrlPresetRef.current.contains(e.target as Node)) {
        setShowBaseUrlPreset(false);
      }
    };
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [showBaseUrlPreset]);

  const hasChanges =
    draftApiKey !== (settings?.llm?.apiKey ?? '') ||
    draftModel !== (settings?.llm?.model ?? '') ||
    draftBaseUrl !== (settings?.llm?.baseUrl ?? '');

  return (
    <Card id={id}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Bot className="h-5 w-5" />
          {t('settings.llm.title')}
        </CardTitle>
        <CardDescription>{t('settings.llm.description')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* Saved LLM profiles dropdown */}
        <div className="space-y-2">
          <Label>{t('settings.llm.savedProfiles')}</Label>
          <div className="flex items-center gap-2">
            <Select value={selectedProfile} onValueChange={handleProfileChange}>
              <SelectTrigger className="flex-1">
                <SelectValue placeholder={t('settings.llm.selectProfile')} />
              </SelectTrigger>
              <SelectContent>
                {savedLlms.map(p => (
                  <SelectItem key={p.name} value={p.name}>
                    {p.name} ({p.model})
                  </SelectItem>
                ))}
                <SelectItem value="__new__">
                  <span className="flex items-center gap-1 text-muted-foreground">
                    <Plus className="h-3.5 w-3.5" /> {t('settings.llm.newProfile')}
                  </span>
                </SelectItem>
              </SelectContent>
            </Select>
            {selectedProfile && (
              <Button variant="outline" size="sm" onClick={handleDeleteProfile} title={t('settings.llm.deleteProfile')}>
                <Trash2 className="h-4 w-4 text-destructive" />
              </Button>
            )}
          </div>
          {selectedProfile && (
            <p className="text-xs text-muted-foreground">
              {t('settings.llm.currentProfile')} <span className="font-medium text-foreground">{selectedProfile}</span>
            </p>
          )}
        </div>

        {/* Profile name (only when creating new or editing) */}
        <div className="space-y-2">
          <Label>{t('settings.llm.profileName')}</Label>
          <Input
            value={draftProfileName}
            onChange={(e) => setDraftProfileName(e.target.value)}
            placeholder={t('settings.llm.profileName.placeholder')}
          />
        </div>

        {/* Base URL */}
        <div className="space-y-2">
          <Label>{t('settings.llm.baseUrl')}</Label>
          <div className="relative" ref={baseUrlPresetRef}>
            <div className="flex gap-1">
              <Input
                value={draftBaseUrl}
                onChange={(e) => setDraftBaseUrl(e.target.value)}
                placeholder={t('settings.llm.baseUrl.placeholder')}
                className="flex-1"
              />
              <Button
                variant="outline"
                size="icon"
                className="shrink-0"
                onClick={() => setShowBaseUrlPreset(!showBaseUrlPreset)}
                title={t('settings.llm.baseUrl.selectPreset')}
              >
                <Globe className="h-4 w-4" />
              </Button>
            </div>
            {showBaseUrlPreset && (
              <div className="absolute z-50 top-full left-0 right-0 mt-1 bg-card rounded-lg shadow-lg border border-border overflow-hidden max-h-[280px] overflow-y-auto">
                {baseUrlPresets.map((preset) => (
                  <button
                    key={preset.label}
                    type="button"
                    onClick={() => {
                      setDraftBaseUrl(preset.url);
                      if (!selectedProfile) {
                        setDraftProfileName(preset.label);
                      }
                      setShowBaseUrlPreset(false);
                    }}
                    className={`w-full text-left px-3 py-2 text-sm hover:bg-muted/50 transition-colors flex items-center justify-between ${preset.url === draftBaseUrl ? 'bg-primary/10 text-primary' : ''}`}
                  >
                    <span className="font-medium">{preset.label}</span>
                    <span className="text-xs text-muted-foreground truncate ml-2 max-w-[60%]">{preset.url}</span>
                  </button>
                ))}
              </div>
            )}
          </div>
          <p className="text-xs text-muted-foreground">{t('settings.llm.baseUrl.hint')}</p>
        </div>

        {/* API Key */}
        <div className="space-y-2">
          <Label>{t('settings.llm.apiKey')}</Label>
          <div className="relative">
            <Input
              type={showApiKey ? 'text' : 'password'}
              value={draftApiKey}
              onChange={(e) => setDraftApiKey(e.target.value)}
              placeholder={t('settings.llm.apiKey.placeholder')}
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
          <Label>{t('settings.llm.model')}</Label>
          <div className="relative" ref={modelDropdownRef}>
            <div className="flex gap-1">
              <Input
                value={draftModel}
                onChange={(e) => { setDraftModel(e.target.value); setShowModelDropdown(false); }}
                placeholder={t('settings.llm.model.placeholder')}
                className="flex-1"
              />
              <Button
                variant="outline"
                size="icon"
                className="shrink-0"
                onClick={handleFetchModels}
                disabled={loadingModels || !draftApiKey}
                title={draftApiKey ? t('settings.llm.model.fetch') : t('settings.llm.model.fetchDisabled')}
              >
                {loadingModels ? <Loader2 className="h-4 w-4 animate-spin" /> : <ChevronDown className="h-4 w-4" />}
              </Button>
            </div>
            {showModelDropdown && fetchedModels.length > 0 && (
              <div className="absolute z-50 top-full left-0 right-0 mt-1 bg-card rounded-lg shadow-lg border border-border overflow-hidden max-h-[240px] overflow-y-auto">
                {fetchedModels.map((m) => (
                  <button
                    key={m.id}
                    type="button"
                    onClick={() => handleModelSelect(m.id)}
                    className={`w-full text-left px-3 py-2 text-sm hover:bg-muted/50 transition-colors ${m.id === draftModel ? 'bg-primary/10 text-primary' : ''}`}
                  >
                    <span className="font-mono">{m.id}</span>
                    {m.displayName && m.displayName !== m.id && (
                      <span className="ml-2 text-muted-foreground text-xs">{m.displayName}</span>
                    )}
                  </button>
                ))}
              </div>
            )}
            {modelsUnsupported && (
              <p className="text-xs text-muted-foreground mt-1">{t('settings.llm.model.unsupported')}</p>
            )}
          </div>
        </div>

        {/* Test result */}
        {testResult && (
          <div className={`flex items-start gap-2 p-3 rounded-lg text-sm ${testResult.success ? 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-400' : 'bg-destructive/10 text-destructive'}`}>
            {testResult.success
              ? <CheckCircle2 className="h-4 w-4 shrink-0 mt-0.5" />
              : <XCircle className="h-4 w-4 shrink-0 mt-0.5" />}
            <div className="flex flex-col gap-1">
              <span>{testResult.success ? t('settings.llm.test.success') : testResult.error}</span>
              {testResult.success && <CapabilitiesDisplay capabilities={testResult.capabilities} />}
              {testResult.success && !testResult.error && (
                <span className="text-xs opacity-60">{t('settings.llm.test.saved')}</span>
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
              ? <><Loader2 className="h-4 w-4 mr-2 animate-spin" />{t('settings.llm.testing')}</>
              : <span>{t('settings.llm.save')}</span>}
          </Button>
          {hasChanges && !testing && (
            <span className="text-xs text-muted-foreground">{t('settings.llm.unsavedChanges')}</span>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
