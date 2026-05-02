/**
 * Web Worker for Shiki syntax highlighting.
 *
 * Offloads Shiki initialization and codeToTokens() to a background thread,
 * eliminating main-thread blocking during syntax highlighting.
 */

import { createHighlighter, type TokensResult, type BundledLanguage } from 'shiki';
import { createJavaScriptRegexEngine } from 'shiki/engine/javascript';

const engine = createJavaScriptRegexEngine({ forgiving: true });

// Cache: themeKey -> Highlighter promise
const highlighterCache = new Map<string, ReturnType<typeof createHighlighter>>();
// Cache: cacheKey -> TokensResult
const resultCache = new Map<string, TokensResult>();

function getCacheKey(code: string, language: string, themes: [string, string]): string {
  const prefix = code.slice(0, 100);
  const suffix = code.length > 100 ? code.slice(-100) : '';
  return `${language}:${themes[0]}:${themes[1]}:${code.length}:${prefix}:${suffix}`;
}

function getThemeKey(themes: [string, string]): string {
  return `${themes[0]}-${themes[1]}`;
}

async function ensureHighlighter(themes: [string, string]) {
  const key = getThemeKey(themes);
  if (!highlighterCache.has(key)) {
    highlighterCache.set(key, createHighlighter({ themes, langs: [], engine }));
  }
  return highlighterCache.get(key)!;
}

self.onmessage = async (event) => {
  const { id, code, language, themes } = event.data;

  try {
    const cacheKey = getCacheKey(code, language, themes);

    // Check result cache
    if (resultCache.has(cacheKey)) {
      self.postMessage({ id, result: resultCache.get(cacheKey) });
      return;
    }

    const highlighter = await ensureHighlighter(themes);

    // On-demand language loading
    const lang = language as BundledLanguage;
    if (!highlighter.getLoadedLanguages().includes(lang)) {
      await highlighter.loadLanguage(lang);
    }

    const result = highlighter.codeToTokens(code, {
      lang,
      themes: { light: themes[0], dark: themes[1] },
    });

    resultCache.set(cacheKey, result);
    self.postMessage({ id, result });
  } catch (err) {
    self.postMessage({
      id,
      result: null,
      error: err instanceof Error ? err.message : String(err),
    });
  }
};
