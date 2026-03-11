import { writable, derived } from "svelte/store";
import type { Settings, AppSettings } from "../types";
import { appSettingsApi } from "../api/tauri";

// Default UI settings (local state)
const defaultUISettings: Settings = {
    theme: "dark",
    fontSize: "medium",
    autoSave: true,
    showFileChanges: true,
    maxHistoryItems: 100,
};

// State interface - combines UI settings and App settings
interface SettingsState {
    uiSettings: Settings; // Local UI preferences
    appSettings: AppSettings | null; // Backend settings (LLM, etc.)
    isLoading: boolean;
    error: string | null;
}

// Initial state
const initialState: SettingsState = {
    uiSettings: defaultUISettings,
    appSettings: null,
    isLoading: false,
    error: null,
};

// Main store
function createSettingsStore() {
    const { subscribe, set, update } = writable<SettingsState>(initialState);

    return {
        subscribe,

        // Load app settings from backend
        async loadSettings() {
            update((state) => ({ ...state, isLoading: true, error: null }));

            const response = await appSettingsApi.get();

            if (response.success && response.data) {
                update((state) => ({
                    ...state,
                    appSettings: response.data!,
                    isLoading: false,
                }));
            } else {
                update((state) => ({
                    ...state,
                    appSettings: null,
                    isLoading: false,
                    error: response.error || "Failed to load settings",
                }));
            }
        },

        // Update app settings
        async updateAppSettings(settings: AppSettings) {
            const response = await appSettingsApi.update(settings);

            if (response.success && response.data) {
                update((state) => ({
                    ...state,
                    appSettings: response.data!,
                }));
                return true;
            }

            update((state) => ({
                ...state,
                error: response.error || "Failed to update settings",
            }));
            return false;
        },

        // Update UI settings (local only)
        updateUISettings(updates: Partial<Settings>) {
            update((state) => ({
                ...state,
                uiSettings: { ...state.uiSettings, ...updates },
            }));
        },

        // Toggle theme (UI setting)
        toggleTheme() {
            update((state) => {
                const current = state.uiSettings.theme;
                const next =
                    current === "dark"
                        ? "light"
                        : current === "light"
                          ? "system"
                          : "dark";
                return {
                    ...state,
                    uiSettings: { ...state.uiSettings, theme: next },
                };
            });
        },

        // Clear error
        clearError() {
            update((state) => ({ ...state, error: null }));
        },

        // Reset to defaults
        resetToDefaults() {
            update((state) => ({
                ...state,
                uiSettings: defaultUISettings,
            }));
        },
    };
}

export const settingsStore = createSettingsStore();

// Derived stores for UI settings
export const currentTheme = derived(
    settingsStore,
    ($state) => $state.uiSettings.theme,
);

export const fontSize = derived(settingsStore, ($state) => {
    const sizes = {
        small: "14px",
        medium: "16px",
        large: "18px",
    };
    return sizes[$state.uiSettings.fontSize];
});

// Derived store for app settings
export const appSettings = derived(
    settingsStore,
    ($state) => $state.appSettings,
);
