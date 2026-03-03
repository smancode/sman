import { writable, derived } from 'svelte/store';
import type { Settings } from '../types';
import { settingsApi } from '../api/tauri';

// Default settings
const defaultSettings: Settings = {
  theme: 'dark',
  fontSize: 'medium',
  autoSave: true,
  showFileChanges: true,
  maxHistoryItems: 100
};

// State interface
interface SettingsState {
  settings: Settings;
  isLoading: boolean;
  error: string | null;
}

// Initial state
const initialState: SettingsState = {
  settings: defaultSettings,
  isLoading: false,
  error: null
};

// Main store
function createSettingsStore() {
  const { subscribe, set, update } = writable<SettingsState>(initialState);

  return {
    subscribe,

    // Load settings
    async loadSettings() {
      update((state) => ({ ...state, isLoading: true, error: null }));

      const response = await settingsApi.get();

      if (response.success && response.data) {
        update((state) => ({
          ...state,
          settings: { ...defaultSettings, ...response.data },
          isLoading: false
        }));
      } else {
        // Use defaults if load fails
        update((state) => ({
          ...state,
          settings: defaultSettings,
          isLoading: false
        }));
      }
    },

    // Update settings
    async updateSettings(updates: Partial<Settings>) {
      const response = await settingsApi.update(updates);

      if (response.success && response.data) {
        update((state) => ({
          ...state,
          settings: response.data!
        }));
        return true;
      }

      update((state) => ({
        ...state,
        error: response.error || 'Failed to update settings'
      }));
      return false;
    },

    // Update a single setting
    async setSetting<K extends keyof Settings>(key: K, value: Settings[K]) {
      return this.updateSettings({ [key]: value });
    },

    // Toggle theme
    async toggleTheme() {
      const current = get({ subscribe }).settings.theme;
      const next = current === 'dark' ? 'light' : current === 'light' ? 'system' : 'dark';
      return this.setSetting('theme', next);
    },

    // Clear error
    clearError() {
      update((state) => ({ ...state, error: null }));
    },

    // Reset to defaults
    async resetToDefaults() {
      return this.updateSettings(defaultSettings);
    }
  };
}

// Helper to get current value
function get(store: { subscribe: (run: (value: SettingsState) => void) => () => void }): SettingsState {
  let value: SettingsState = initialState;
  const unsubscribe = store.subscribe((v) => {
    value = v;
  });
  unsubscribe();
  return value;
}

export const settingsStore = createSettingsStore();

// Derived stores
export const currentTheme = derived(settingsStore, ($state) => $state.settings.theme);

export const fontSize = derived(settingsStore, ($state) => {
  const sizes = {
    small: '14px',
    medium: '16px',
    large: '18px'
  };
  return sizes[$state.settings.fontSize];
});
