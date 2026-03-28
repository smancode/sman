import { useEffect } from 'react';
import { LLMSettings } from './LLMSettings';
import { WebSearchSettings } from './WebSearchSettings';
import { CronTaskSettings } from './CronTaskSettings';
import { BatchTaskSettings } from './BatchTaskSettings';
import { ChatbotSettings } from './ChatbotSettings';
import { useSettingsStore } from '@/stores/settings';

export function Settings() {
  const fetchSettings = useSettingsStore((s) => s.fetchSettings);

  useEffect(() => {
    fetchSettings();
  }, [fetchSettings]);

  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">设置</h1>
        <p className="text-muted-foreground mt-1">配置 Sman</p>
      </div>

      <div className="max-w-2xl space-y-6">
        <CronTaskSettings />
        <BatchTaskSettings />
        <LLMSettings />
        <WebSearchSettings />
        <ChatbotSettings />
      </div>
    </div>
  );
}

export default Settings;
