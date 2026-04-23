// src/features/stardom/ControlBar.tsx
import { useStardomStore } from '@/stores/stardom';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Label } from '@/components/ui/label';
import type { StardomMode } from '@/types/stardom';

const modeLabels: Record<StardomMode, string> = {
  auto: '全自动',
  notify: '半自动（推荐）',
  manual: '手动',
};

export function ControlBar() {
  const { connection, setMode } = useStardomStore();

  return (
    <div className="flex items-center gap-4 px-4 py-2 border-t bg-muted/30">
      <div className="flex items-center gap-2">
        <Label className="text-xs text-muted-foreground">模式</Label>
        <Select defaultValue="notify" onValueChange={(v) => setMode(v as StardomMode)}>
          <SelectTrigger className="h-7 w-32 text-xs">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {Object.entries(modeLabels).map(([value, label]) => (
              <SelectItem key={value} value={value} className="text-xs">{label}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="text-xs text-muted-foreground">
        槽位 {connection.activeSlots}/{connection.maxSlots}
      </div>
    </div>
  );
}
