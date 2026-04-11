import { CronTaskSettings } from '@/features/settings/CronTaskSettings';

export function CronTasksPage() {
  return (
    <div className="p-6 max-w-2xl mx-auto space-y-6">
      <CronTaskSettings />
    </div>
  );
}
