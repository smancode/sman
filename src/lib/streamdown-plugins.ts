/**
 * Streamdown plugin configuration with Web Worker-based Shiki highlighting.
 *
 * Replaces @streamdown/code with a custom Worker implementation that offloads
 * Shiki initialization and codeToTokens() to a background thread, eliminating
 * main-thread blocking during syntax highlighting.
 */

import { useState, useEffect } from 'react';
import type { CodeHighlighterPlugin, ThemeInput } from 'streamdown';
import type { TokensResult, BundledLanguage } from 'shiki';
import { highlightCode } from './shiki-worker-client';

const themes: [string, string] = ['one-light', 'one-dark-pro'];

// In-memory cache for highlighted results (main thread)
const resultCache = new Map<string, TokensResult>();
const pendingCallbacks = new Map<string, Set<(result: TokensResult) => void>>();

function getCacheKey(code: string, language: string): string {
  const prefix = code.slice(0, 100);
  const suffix = code.length > 100 ? code.slice(-100) : '';
  return `${language}:${themes[0]}:${themes[1]}:${code.length}:${prefix}:${suffix}`;
}

// Create the plugin instance
function createWorkerPlugin(): CodeHighlighterPlugin {
  return {
    name: 'shiki' as const,
    type: 'code-highlighter',

    supportsLanguage(_language: BundledLanguage): boolean {
      // Shiki supports most languages, be permissive
      return true;
    },

    getSupportedLanguages(): BundledLanguage[] {
      return [] as BundledLanguage[];
    },

    getThemes(): [ThemeInput, ThemeInput] {
      return themes as [ThemeInput, ThemeInput];
    },

    highlight({ code, language }, callback) {
      const cacheKey = getCacheKey(code, language);

      // Cache hit — return immediately
      if (resultCache.has(cacheKey)) {
        return resultCache.get(cacheKey)!;
      }

      // Already pending — add callback to queue
      if (pendingCallbacks.has(cacheKey)) {
        if (callback) {
          pendingCallbacks.get(cacheKey)!.add(callback);
        }
        return null;
      }

      // New request — dispatch to worker
      const callbacks = new Set<(result: TokensResult) => void>();
      if (callback) {
        callbacks.add(callback);
      }
      pendingCallbacks.set(cacheKey, callbacks);

      highlightCode(code, language, themes)
        .then((result) => {
          resultCache.set(cacheKey, result);
          const cbs = pendingCallbacks.get(cacheKey);
          pendingCallbacks.delete(cacheKey);
          if (cbs) {
            for (const cb of cbs) {
              try { cb(result); } catch { /* ignore */ }
            }
          }
        })
        .catch((err) => {
          console.error('[ShikiWorker] Highlight failed:', err);
          pendingCallbacks.delete(cacheKey);
        });

      return null;
    },
  };
}

let cachedPlugin: CodeHighlighterPlugin | null = null;
let loadPromise: Promise<CodeHighlighterPlugin> | null = null;

function loadCodePlugin(): Promise<CodeHighlighterPlugin> {
  if (!loadPromise) {
    loadPromise = Promise.resolve(createWorkerPlugin());
    loadPromise.then((plugin) => {
      cachedPlugin = plugin;
    });
  }
  return loadPromise;
}

/**
 * Hook that returns the Shiki code highlighter plugin.
 * Returns undefined until the plugin is loaded, then the cached instance.
 */
export function useCodePlugin(): CodeHighlighterPlugin | undefined {
  const [plugin, setPlugin] = useState<CodeHighlighterPlugin | undefined>(cachedPlugin ?? undefined);

  useEffect(() => {
    if (cachedPlugin) {
      setPlugin(cachedPlugin);
      return;
    }
    loadCodePlugin().then(setPlugin);
  }, []);

  return plugin;
}
