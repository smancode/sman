// src/hooks/useTheme.ts

import { useEffect, useState } from 'react';

type Theme = 'light' | 'dark';

const THEME_KEY = 'smanweb-theme';

export function useTheme() {
  const [theme, setThemeState] = useState<Theme>(() => {
    // 从 localStorage 读取，默认 light
    if (typeof window === 'undefined') return 'light';
    const stored = localStorage.getItem(THEME_KEY);
    if (stored === 'dark' || stored === 'light') return stored;
    return 'light';
  });

  // 同步到 DOM 和 localStorage
  useEffect(() => {
    const root = document.documentElement;
    if (theme === 'dark') {
      root.classList.add('dark');
    } else {
      root.classList.remove('dark');
    }
    localStorage.setItem(THEME_KEY, theme);
  }, [theme]);

  const setTheme = (newTheme: Theme) => {
    setThemeState(newTheme);
  };

  const toggleTheme = () => {
    setThemeState((prev) => (prev === 'light' ? 'dark' : 'light'));
  };

  return { theme, setTheme, toggleTheme };
}
