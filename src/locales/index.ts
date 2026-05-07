/**
 *极简 i18n 工具
 *不使用复杂库，自实现 50 行工具
 */

import zhCN from './zh-CN.json';
import enUS from './en-US.json';

type LocaleDict = Record<string, { text: string; context?: string }>;

const translations: Record<string, LocaleDict> = {
  'zh-CN': zhCN as LocaleDict,
  'en-US': enUS as LocaleDict,
};

let currentLocale = 'zh-CN';

/**
 *设置当前语言
 */
export function setLocale(locale: string) {
  if (!translations[locale]) {
    console.warn(`[i18n] Unsupported locale: ${locale}, falling back to zh-CN`);
    currentLocale = 'zh-CN';
    return;
  }
  currentLocale = locale;
  console.log(`[i18n] Language switched to: ${locale}`);
}

/**
 *翻译函数
 *@param key - 翻译键，如 'menu.settings'
 *@returns 翻译后的文本
 */
export function t(key: string): string {
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
  return currentLocale;
}

/**
 *语言提示词（用于 user message）
 *在发送消息前自动注入，告诉 LLM 用什么语言回复
 */
export const LANGUAGE_HINTS: Record<string, string> = {
  'zh-CN': '[请用中文回复]',
  'en-US': '[Please respond in English]',
};
