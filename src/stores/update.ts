import { create } from 'zustand';

type UpdateStatus = 'idle' | 'checking' | 'downloading' | 'ready' | 'not-available' | 'error';

interface UpdateState {
  status: UpdateStatus;
  newVersion: string | null;
  errorMessage: string | null;
  bannerDismissed: boolean;
  isElectron: boolean;

  checkUpdate: () => void;
  installUpdate: () => void;
  dismissBanner: () => void;
  initListeners: () => () => void;
}

export const useUpdateStore = create<UpdateState>((set) => ({
  status: 'idle',
  newVersion: null,
  errorMessage: null,
  bannerDismissed: false,
  isElectron: !!window.sman?.updater,

  checkUpdate: () => {
    if (!window.sman?.updater) return;
    set({ status: 'checking' });
    window.sman.updater.check().catch(() => {
      set({ status: 'error', errorMessage: 'Failed to check' });
    });
  },

  installUpdate: () => {
    if (!window.sman?.updater) return;
    window.sman.updater.install();
  },

  dismissBanner: () => {
    set({ bannerDismissed: true });
  },

  initListeners: () => {
    if (!window.sman?.updater) return () => {};

    const unsubAvailable = window.sman.updater.onUpdateAvailable((info) => {
      set({ status: 'downloading', newVersion: info.version, bannerDismissed: false });
    });

    const unsubNotAvailable = window.sman.updater.onUpdateNotAvailable(() => {
      set({ status: 'not-available' });
    });

    const unsubDownloaded = window.sman.updater.onUpdateDownloaded((info) => {
      set({ status: 'ready', newVersion: info.version, bannerDismissed: false });
    });

    const unsubError = window.sman.updater.onUpdateError((info) => {
      set({ status: 'error', errorMessage: info.message });
    });

    return () => {
      unsubAvailable();
      unsubNotAvailable();
      unsubDownloaded();
      unsubError();
    };
  },
}));
