/**
 * Settings Page
 * Application settings including gateway connection configuration
 */
import { ConnectionSettings } from './ConnectionSettings';

export function Settings() {
  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Settings</h1>
        <p className="text-muted-foreground mt-1">
          Configure your SmanWeb application
        </p>
      </div>

      <div className="max-w-2xl space-y-6">
        <ConnectionSettings />
      </div>
    </div>
  );
}

export default Settings;
