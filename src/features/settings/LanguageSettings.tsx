/**
 *语言设置组件
 *选择即生效，无需保存按钮
 */

import { Languages } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
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
  const currentLanguage = settings?.language || 'zh-CN';

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
        </div>
      </CardContent>
    </Card>
  );
}
