/**
 *极简 i18n 工具
 *使用 Zustand 管理 locale 状态
 *组件内调用 useLocale() 订阅 locale 变化，语言切换时该组件 re-render
 */

import { create } from 'zustand';
import zhCN from './zh-CN.json';
import enUS from './en-US.json';

type LocaleDict = Record<string, { text: string; context?: string }>;

const translations: Record<string, LocaleDict> = {
  'zh-CN': zhCN as LocaleDict,
  'en-US': enUS as LocaleDict,
};

interface LocaleState {
  locale: string;
}

const LOCALE_CACHE_KEY = 'sman-locale';

function getInitialLocale(): string {
  const cached = localStorage.getItem(LOCALE_CACHE_KEY);
  if (cached && translations[cached]) return cached;

  try {
    const xhr = new XMLHttpRequest();
    xhr.open('GET', '/api/language', false);
    xhr.timeout = 2000;
    xhr.send();
    if (xhr.status === 200) {
      const { language } = JSON.parse(xhr.responseText);
      if (language && translations[language]) {
        localStorage.setItem(LOCALE_CACHE_KEY, language);
        return language;
      }
    }
  } catch {
    // backend not ready yet
  }

  const browserLang = navigator.language || '';
  if (browserLang.toLowerCase().startsWith('zh')) return 'zh-CN';
  return 'en-US';
}

const useLocaleStore = create<LocaleState>(() => ({
  locale: getInitialLocale(),
}));

export function setLocale(locale: string) {
  if (!translations[locale]) {
    console.warn(`[i18n] Unsupported locale: ${locale}, falling back to zh-CN`);
    useLocaleStore.setState({ locale: 'zh-CN' });
    localStorage.setItem(LOCALE_CACHE_KEY, 'zh-CN');
    return;
  }
  useLocaleStore.setState({ locale });
  localStorage.setItem(LOCALE_CACHE_KEY, locale);
  console.log(`[i18n] Language switched to: ${locale}`);
}

export function t(key: string): string {
  const currentLocale = useLocaleStore.getState().locale;

  const dict = translations[currentLocale];
  if (dict?.[key]?.text) {
    return dict[key].text;
  }

  if (translations['zh-CN']?.[key]?.text) {
    return translations['zh-CN'][key].text;
  }

  console.error(`[i18n] Missing key: "${key}"`);
  return key;
}

export function getCurrentLocale(): string {
  return useLocaleStore.getState().locale;
}

/**
 *React Hook: 订阅 locale 变化，触发组件 re-render
 *所有使用 t() 渲染文本的组件都需要调用此 hook
 */
export function useLocale() {
  return useLocaleStore((s) => s.locale);
}

export const LANGUAGE_HINTS: Record<string, string> = {
  'zh-CN': '[IMPORTANT: You MUST respond in Simplified Chinese (简体中文). Do not use English unless the user explicitly asks for it.]',
  'en-US': '[IMPORTANT: You MUST respond in English. Do not use Chinese or any other language unless the user explicitly asks for it.]',
};
