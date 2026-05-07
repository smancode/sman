/**
 *语言切换 Hook
 *监听设置中的语言变化，自动更新 i18n 工具的语言
 */

import { useEffect } from 'react';
import { useSettingsStore } from '@/stores/settings';
import { setLocale } from '@/locales';

export function useLanguage() {
  const settings = useSettingsStore(s => s.settings);
  const language = settings?.language || 'zh-CN';

  useEffect(() => {
    setLocale(language);
  }, [language]);
}
