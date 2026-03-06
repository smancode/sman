import { writable, derived, get } from "svelte/store";
import type { Project } from "../types";
import { projectApi } from "../api/tauri";

// State interface
interface ProjectsState {
    projects: Project[];
    projectOrder: string[];
    hiddenProjectIds: string[];
    selectedProjectId: string | null;
    isLoading: boolean;
    error: string | null;
}

const PROJECT_ORDER_STORAGE_KEY = "smanclaw.projectOrder";
const HIDDEN_PROJECTS_STORAGE_KEY = "smanclaw.hiddenProjects";

function loadProjectOrder(): string[] {
    if (typeof window === "undefined") {
        return [];
    }
    try {
        const raw = window.localStorage.getItem(PROJECT_ORDER_STORAGE_KEY);
        if (!raw) {
            return [];
        }
        const parsed = JSON.parse(raw);
        if (!Array.isArray(parsed)) {
            return [];
        }
        return parsed.filter(
            (item): item is string => typeof item === "string",
        );
    } catch {
        return [];
    }
}

function saveProjectOrder(projectOrder: string[]) {
    if (typeof window === "undefined") {
        return;
    }
    window.localStorage.setItem(
        PROJECT_ORDER_STORAGE_KEY,
        JSON.stringify(projectOrder),
    );
}

function loadHiddenProjectIds(): string[] {
    if (typeof window === "undefined") {
        return [];
    }
    try {
        const raw = window.localStorage.getItem(HIDDEN_PROJECTS_STORAGE_KEY);
        if (!raw) {
            return [];
        }
        const parsed = JSON.parse(raw);
        if (!Array.isArray(parsed)) {
            return [];
        }
        return parsed.filter(
            (item): item is string => typeof item === "string",
        );
    } catch {
        return [];
    }
}

function saveHiddenProjectIds(hiddenProjectIds: string[]) {
    if (typeof window === "undefined") {
        return;
    }
    window.localStorage.setItem(
        HIDDEN_PROJECTS_STORAGE_KEY,
        JSON.stringify(hiddenProjectIds),
    );
}

function sortByLastAccessed(projects: Project[]): Project[] {
    return [...projects].sort(
        (a, b) =>
            new Date(b.lastAccessed).getTime() -
            new Date(a.lastAccessed).getTime(),
    );
}

function normalizeProjectOrder(
    projects: Project[],
    currentOrder: string[],
): string[] {
    const existingIds = new Set(projects.map((project) => project.id));
    const kept = currentOrder.filter((id) => existingIds.has(id));
    const missing = sortByLastAccessed(projects)
        .map((project) => project.id)
        .filter((id) => !kept.includes(id));
    return [...kept, ...missing];
}

function resolveSelectedProjectId(
    projects: Project[],
    projectOrder: string[],
    selectedProjectId: string | null,
): string | null {
    if (projects.length === 0) {
        return null;
    }
    if (
        selectedProjectId &&
        projects.some((project) => project.id === selectedProjectId)
    ) {
        return selectedProjectId;
    }
    const firstOrderedId = projectOrder.find((id) =>
        projects.some((project) => project.id === id),
    );
    return firstOrderedId || projects[0].id;
}

function getVisibleProjects(
    projects: Project[],
    hiddenProjectIds: string[],
): Project[] {
    if (hiddenProjectIds.length === 0) {
        return projects;
    }
    const hiddenSet = new Set(hiddenProjectIds);
    return projects.filter((project) => !hiddenSet.has(project.id));
}

const initialState: ProjectsState = {
    projects: [],
    projectOrder: loadProjectOrder(),
    hiddenProjectIds: loadHiddenProjectIds(),
    selectedProjectId: null,
    isLoading: false,
    error: null,
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
                update((state) => {
                    const hiddenProjectIds = state.hiddenProjectIds.filter(
                        (id) =>
                            response.data!.some((project) => project.id === id),
                    );
                    const visibleProjects = getVisibleProjects(
                        response.data!,
                        hiddenProjectIds,
                    );
                    const projectOrder = normalizeProjectOrder(
                        visibleProjects,
                        state.projectOrder,
                    );
                    const selectedProjectId = resolveSelectedProjectId(
                        visibleProjects,
                        projectOrder,
                        state.selectedProjectId,
                    );
                    return {
                        ...state,
                        projects: response.data!,
                        hiddenProjectIds,
                        projectOrder,
                        selectedProjectId,
                        isLoading: false,
                    };
                });
                const state = get({ subscribe });
                saveProjectOrder(state.projectOrder);
                saveHiddenProjectIds(state.hiddenProjectIds);
            } else {
                update((state) => ({
                    ...state,
                    error: response.error || "Failed to load projects",
                    isLoading: false,
                }));
            }
        },

        // Select a project
        selectProject(projectId: string | null) {
            update((state) => ({
                ...state,
                selectedProjectId: projectId,
            }));
        },

        reorderProjects(draggedId: string, targetId: string) {
            if (!draggedId || !targetId || draggedId === targetId) {
                return;
            }
            update((state) => {
                const visibleProjects = getVisibleProjects(
                    state.projects,
                    state.hiddenProjectIds,
                );
                const normalized = normalizeProjectOrder(
                    visibleProjects,
                    state.projectOrder,
                );
                const draggedIndex = normalized.indexOf(draggedId);
                const targetIndex = normalized.indexOf(targetId);
                if (draggedIndex < 0 || targetIndex < 0) {
                    return state;
                }
                const reordered = [...normalized];
                const [moved] = reordered.splice(draggedIndex, 1);
                reordered.splice(targetIndex, 0, moved);
                saveProjectOrder(reordered);
                return {
                    ...state,
                    projectOrder: reordered,
                };
            });
        },

        // Create a new project (add existing project by path)
        async createProject(path: string) {
            const response = await projectApi.create(path);

            if (response.success && response.data) {
                update((state) => {
                    const projects = [
                        ...state.projects.filter(
                            (project) => project.id !== response.data!.id,
                        ),
                        response.data!,
                    ];
                    const hiddenProjectIds = state.hiddenProjectIds.filter(
                        (id) => id !== response.data!.id,
                    );
                    const visibleProjects = getVisibleProjects(
                        projects,
                        hiddenProjectIds,
                    );
                    const projectOrder = [
                        response.data!.id,
                        ...normalizeProjectOrder(
                            visibleProjects,
                            state.projectOrder,
                        ).filter((id) => id !== response.data!.id),
                    ];
                    return {
                        ...state,
                        projects,
                        hiddenProjectIds,
                        projectOrder,
                        selectedProjectId: response.data!.id,
                        error: null,
                    };
                });
                const state = get({ subscribe });
                saveProjectOrder(state.projectOrder);
                saveHiddenProjectIds(state.hiddenProjectIds);
                return response.data;
            }

            const refreshed = await projectApi.list();
            if (refreshed.success && refreshed.data) {
                update((state) => {
                    const existing = refreshed.data!.find(
                        (project) => project.path === path,
                    );
                    const hiddenProjectIds = existing
                        ? state.hiddenProjectIds.filter(
                              (id) => id !== existing.id,
                          )
                        : state.hiddenProjectIds.filter((id) =>
                              refreshed.data!.some(
                                  (project) => project.id === id,
                              ),
                          );
                    const visibleProjects = getVisibleProjects(
                        refreshed.data!,
                        hiddenProjectIds,
                    );
                    const projectOrder = normalizeProjectOrder(
                        visibleProjects,
                        state.projectOrder,
                    );
                    const selectedProjectId = existing
                        ? existing.id
                        : resolveSelectedProjectId(
                              visibleProjects,
                              projectOrder,
                              state.selectedProjectId,
                          );
                    return {
                        ...state,
                        projects: refreshed.data!,
                        hiddenProjectIds,
                        projectOrder,
                        selectedProjectId,
                        error: response.error || "Failed to create project",
                    };
                });
                const state = get({ subscribe });
                saveProjectOrder(state.projectOrder);
                saveHiddenProjectIds(state.hiddenProjectIds);
            } else {
                update((state) => ({
                    ...state,
                    error: response.error || "Failed to create project",
                }));
            }
            return null;
        },

        // Delete a project
        async deleteProject(id: string) {
            update((state) => {
                const hiddenProjectIds = state.hiddenProjectIds.includes(id)
                    ? state.hiddenProjectIds
                    : [...state.hiddenProjectIds, id];
                const visibleProjects = getVisibleProjects(
                    state.projects,
                    hiddenProjectIds,
                );
                const projectOrder = normalizeProjectOrder(
                    visibleProjects,
                    state.projectOrder.filter((projectId) => projectId !== id),
                );
                const selectedProjectId = resolveSelectedProjectId(
                    visibleProjects,
                    projectOrder,
                    state.selectedProjectId === id
                        ? null
                        : state.selectedProjectId,
                );
                return {
                    ...state,
                    hiddenProjectIds,
                    projectOrder,
                    selectedProjectId,
                    error: null,
                };
            });
            const state = get({ subscribe });
            saveProjectOrder(state.projectOrder);
            saveHiddenProjectIds(state.hiddenProjectIds);
            return true;
        },

        // Open project dialog
        async openProjectDialog() {
            console.log("[projectsStore.openProjectDialog] Starting...");
            const response = await projectApi.openDialog();
            console.log(
                "[projectsStore.openProjectDialog] Response:",
                response,
            );

            if (response.success && response.data) {
                return response.data;
            }

            update((state) => ({
                ...state,
                error: response.error || "Failed to open dialog",
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
        },
    };
}

export const projectsStore = createProjectsStore();

// Derived stores
export const selectedProject = derived(projectsStore, ($state) =>
    getVisibleProjects($state.projects, $state.hiddenProjectIds).find(
        (p) => p.id === $state.selectedProjectId,
    ),
);

export const sortedProjects = derived(projectsStore, ($state) => {
    const visibleProjects = getVisibleProjects(
        $state.projects,
        $state.hiddenProjectIds,
    );
    const byId = new Map(
        visibleProjects.map((project) => [project.id, project]),
    );
    const ordered = $state.projectOrder
        .map((id) => byId.get(id))
        .filter((project): project is Project => Boolean(project));
    const missing = sortByLastAccessed(visibleProjects).filter(
        (project) => !$state.projectOrder.includes(project.id),
    );
    return [...ordered, ...missing];
});
