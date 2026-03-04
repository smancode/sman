import { writable, derived, get } from 'svelte/store';
import type { Project } from '../types';
import { projectApi } from '../api/tauri';

// State interface
interface ProjectsState {
  projects: Project[];
  selectedProjectId: string | null;
  isLoading: boolean;
  error: string | null;
}

// Initial state
const initialState: ProjectsState = {
  projects: [],
  selectedProjectId: null,
  isLoading: false,
  error: null
};

// Main store
function createProjectsStore() {
  const { subscribe, set, update } = writable<ProjectsState>(initialState);

  return {
    subscribe,

    // Load all projects
    async loadProjects() {
      update((state) => ({ ...state, isLoading: true, error: null }));

      const response = await projectApi.list();

      if (response.success && response.data) {
        update((state) => ({
          ...state,
          projects: response.data!,
          isLoading: false
        }));
      } else {
        update((state) => ({
          ...state,
          error: response.error || 'Failed to load projects',
          isLoading: false
        }));
      }
    },

    // Select a project
    selectProject(projectId: string | null) {
      update((state) => ({
        ...state,
        selectedProjectId: projectId
      }));
    },

    // Create a new project
    async createProject(name: string, path: string, description?: string) {
      const response = await projectApi.create(name, path, description);

      if (response.success && response.data) {
        update((state) => ({
          ...state,
          projects: [...state.projects, response.data!]
        }));
        return response.data;
      }

      update((state) => ({
        ...state,
        error: response.error || 'Failed to create project'
      }));
      return null;
    },

    // Update a project
    async updateProject(id: string, updates: Partial<Project>) {
      const response = await projectApi.update(id, updates);

      if (response.success && response.data) {
        update((state) => ({
          ...state,
          projects: state.projects.map((p) => (p.id === id ? response.data! : p))
        }));
        return response.data;
      }

      update((state) => ({
        ...state,
        error: response.error || 'Failed to update project'
      }));
      return null;
    },

    // Delete a project
    async deleteProject(id: string) {
      const response = await projectApi.delete(id);

      if (response.success) {
        update((state) => ({
          ...state,
          projects: state.projects.filter((p) => p.id !== id),
          selectedProjectId: state.selectedProjectId === id ? null : state.selectedProjectId
        }));
        return true;
      }

      update((state) => ({
        ...state,
        error: response.error || 'Failed to delete project'
      }));
      return false;
    },

    // Open project dialog
    async openProjectDialog() {
      console.log('[projectsStore.openProjectDialog] Starting...');
      const response = await projectApi.openDialog();
      console.log('[projectsStore.openProjectDialog] Response:', response);

      if (response.success && response.data) {
        return response.data;
      }

      update((state) => ({
        ...state,
        error: response.error || 'Failed to open dialog'
      }));
      return null;
    },

    // Clear error
    clearError() {
      update((state) => ({ ...state, error: null }));
    },

    // Reset store
    reset() {
      set(initialState);
    }
  };
}

export const projectsStore = createProjectsStore();

// Derived stores
export const selectedProject = derived(projectsStore, ($state) =>
  $state.projects.find((p) => p.id === $state.selectedProjectId)
);

export const sortedProjects = derived(projectsStore, ($state) =>
  [...$state.projects].sort((a, b) => new Date(b.lastAccessed).getTime() - new Date(a.lastAccessed).getTime())
);
