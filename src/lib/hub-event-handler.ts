import { queryClient } from './query-client';

let registered = false;

export function registerHubEventHandlers(): void {
  if (registered) return;
  registered = true;

  // Periodic refresh for hub data (every 30s while the hub page is visible)
  setInterval(() => {
    queryClient.invalidateQueries({ queryKey: ['hub'] });
  }, 30_000);
}
