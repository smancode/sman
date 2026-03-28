import { CronTaskSettings } from '@/features/settings/CronTaskSettings';

export function CronTasksPage() {
  return (
    <div className="p-6 max-w-2xl space-y-6">
      <CronTaskSettings />
    </div>
  );
}
