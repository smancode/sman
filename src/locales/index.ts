/**
 *极简 i18n 工具
 *使用 Zustand 管理 locale 状态，语言切换时自动触发 React re-render
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

const useLocaleStore = create<LocaleState>(() => ({
  locale: 'zh-CN',
}));

/**
 *设置当前语言，同时更新 Zustand store 触发 React re-render
 */
export function setLocale(locale: string) {
  if (!translations[locale]) {
    console.warn(`[i18n] Unsupported locale: ${locale}, falling back to zh-CN`);
    useLocaleStore.setState({ locale: 'zh-CN' });
    return;
  }
  useLocaleStore.setState({ locale });
  console.log(`[i18n] Language switched to: ${locale}`);
}

/**
 *翻译函数
 *@param key - 翻译键，如 'menu.settings'
 *@returns 翻译后的文本
 */
export function t(key: string): string {
  const currentLocale = useLocaleStore.getState().locale;

  // 1. 尝试当前语言
  const dict = translations[currentLocale];
  if (dict?.[key]?.text) {
    return dict[key].text;
  }

  // 2. 降级到中文
  if (translations['zh-CN']?.[key]?.text) {
    console.warn(`[i18n] Missing "${key}" in ${currentLocale}, using zh-CN`);
    return translations['zh-CN'][key].text;
  }

  // 3. 返回 key（避免空白）
  console.error(`[i18n] Missing key: "${key}"`);
  return key;
}

/**
 *获取当前语言
 */
export function getCurrentLocale(): string {
  return useLocaleStore.getState().locale;
}

/**
 *React Hook: 订阅 locale 变化，触发组件 re-render
 */
export function useLocale() {
  return useLocaleStore((s) => s.locale);
}

/**
 *语言提示词（用于 user message）
 *在发送消息前自动注入，告诉 LLM 用什么语言回复
 */
export const LANGUAGE_HINTS: Record<string, string> = {
  'zh-CN': '[请用中文回复]',
  'en-US': '[Please respond in English]',
};
