<script lang="ts">
  import Sidebar from './Sidebar.svelte';
  import Header from './Header.svelte';

  interface Props {
    children?: import('svelte').Snippet;
  }

  let { children }: Props = $props();
</script>

<div class="layout">
  <Sidebar />
  <div class="main-container">
    <Header />
    <main class="content">
      {@render children?.()}
    </main>
  </div>
</div>

<style>
  .layout {
    display: flex;
    height: 100vh;
    width: 100vw;
    background-color: var(--background);
    overflow: hidden;
  }

  .main-container {
    display: flex;
    flex-direction: column;
    flex: 1;
    min-width: 0;
  }

  .content {
    flex: 1;
    overflow: auto;
    padding: 1rem;
  }
</style>
