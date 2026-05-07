/**
 *语言设置组件
 *允许用户选择界面语言
 */

import { useState } from 'react';
import { Languages } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { useSettingsStore } from '@/stores/settings';
import { t } from '@/locales';

interface LanguageSettingsProps {
  id: string;
}

export function LanguageSettings({ id }: LanguageSettingsProps) {
  const settings = useSettingsStore(s => s.settings);
  const updateLanguage = useSettingsStore(s => s.updateLanguage);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  const currentLanguage = settings?.language || 'zh-CN';

  const handleSave = async () => {
    setSaving(true);
    setSaved(false);
    try {
      await updateLanguage(currentLanguage);
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    } catch (error) {
      console.error('Failed to save language:', error);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card id={id}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Languages className="h-5 w-5" />
          {t('settings.language.title')}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div className="flex items-center gap-4">
          <div className="flex-1">
            <Label htmlFor="language-select" className="mb-2 block">
              {t('settings.language.label')}
            </Label>
            <Select
              value={currentLanguage}
              onValueChange={(value) => {
                // 立即更新本地状态，UI 立即响应
                updateLanguage(value);
              }}
            >
              <SelectTrigger id="language-select" className="w-full max-w-xs">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="zh-CN">{t('settings.language.zh-CN')}</SelectItem>
                <SelectItem value="en-US">{t('settings.language.en-US')}</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <Button
            onClick={handleSave}
            disabled={saving}
            className="mt-6"
          >
            {saving ? t('common.loading') : t('settings.language.save')}
          </Button>
        </div>
        {saved && (
          <p className="mt-3 text-sm text-green-600 dark:text-green-400">
            {t('settings.language.saved')}
          </p>
        )}
      </CardContent>
    </Card>
  );
}
