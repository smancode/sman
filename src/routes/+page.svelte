<script lang="ts">
    import ChatWindow from "../components/chat/ChatWindow.svelte";
    import { projectsStore } from "../lib/stores/projects";
    import { tasksStore } from "../lib/stores/tasks";
    import { settingsStore } from "../lib/stores/settings";
    import { onMount } from "svelte";

    onMount(() => {
        // Load initial data
        projectsStore.loadProjects();
        settingsStore.loadSettings();

        return () => {
            // Cleanup
            tasksStore.destroy();
        };
    });
</script>

<ChatWindow />
