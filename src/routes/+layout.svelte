<script lang="ts">
    import "../app.css";
    import MainLayout from "../components/layout/MainLayout.svelte";

    interface Props {
        children?: import("svelte").Snippet;
    }

    let { children }: Props = $props();
</script>

<MainLayout>
    {@render children?.()}
</MainLayout>
