/**
 *语言切换 Hook
 *监听设置中的语言变化，自动更新 i18n 工具的语言
 *订阅 locale store 确保语言切换时触发 React re-render
 */

import { useEffect } from 'react';
import { useSettingsStore } from '@/stores/settings';
import { setLocale, useLocale } from '@/locales';

export function useLanguage() {
  const settings = useSettingsStore(s => s.settings);
  const language = settings?.language || 'zh-CN';

  useEffect(() => {
    setLocale(language);
  }, [language]);

  // Subscribe to locale store so React re-renders when setLocale() fires
  useLocale();
}
