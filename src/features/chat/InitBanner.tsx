// Init is fully silent — all states (initializing/complete/error) are background work.
// The banner component is kept as a no-op mount point so the chat layout stays stable.
// If we ever need to show init status, add rendering back here.
export function InitBanner() {
  return null;
}
