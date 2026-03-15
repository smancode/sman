import { useEffect, useState } from 'react';
import { FolderOpen, Loader2, Tag } from 'lucide-react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Label } from '@/components/ui/label';
import { useBusinessSystemsStore } from '@/stores/business-systems';
import type { BusinessSystem } from '@/types/business-system';

interface SystemSelectorProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSelect: (systemId: string) => void;
}

function SystemCard({
  system,
  selected,
  onSelect,
}: {
  system: BusinessSystem;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <div
      className={`flex items-start gap-3 rounded-lg border p-4 cursor-pointer transition-colors ${
        selected
          ? 'border-primary bg-primary/5'
          : 'border-border hover:border-primary/50 hover:bg-muted/50'
      }`}
      onClick={onSelect}
    >
      <RadioGroupItem value={system.id} id={system.id} className="mt-1" />
      <div className="flex-1 min-w-0">
        <Label htmlFor={system.id} className="text-base font-medium cursor-pointer">
          {system.name}
        </Label>
        {system.description && (
          <p className="text-sm text-muted-foreground mt-1">{system.description}</p>
        )}
        <div className="flex flex-wrap items-center gap-2 mt-2 text-xs text-muted-foreground">
          <span className="flex items-center gap-1">
            <FolderOpen className="h-3 w-3" />
            {system.path}
          </span>
        </div>
        {system.techStack && system.techStack.length > 0 && (
          <div className="flex flex-wrap gap-1 mt-2">
            {system.techStack.map((tech) => (
              <span
                key={tech}
                className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-muted text-xs"
              >
                <Tag className="h-2.5 w-2.5" />
                {tech}
              </span>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

export function SystemSelector({ open, onOpenChange, onSelect }: SystemSelectorProps) {
  const {
    systems,
    systemsLoading,
    systemsError,
    loadSystems,
  } = useBusinessSystemsStore();

  const [selectedId, setSelectedId] = useState<string | null>(null);

  // 加载业务系统列表
  useEffect(() => {
    if (open && systems.length === 0) {
      loadSystems();
    }
  }, [open, systems.length, loadSystems]);

  const handleConfirm = () => {
    if (selectedId) {
      onSelect(selectedId);
      onOpenChange(false);
      setSelectedId(null);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[500px]">
        <DialogHeader>
          <DialogTitle>选择业务系统</DialogTitle>
          <DialogDescription>
            选择要操作的业务系统，开始新会话
          </DialogDescription>
        </DialogHeader>

        <div className="py-4">
          {systemsLoading ? (
            <div className="flex items-center justify-center py-8">
              <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
            </div>
          ) : systemsError ? (
            <div className="text-center py-8 text-destructive">
              <p>{systemsError}</p>
              <Button variant="outline" size="sm" className="mt-2" onClick={loadSystems}>
                重试
              </Button>
            </div>
          ) : systems.length === 0 ? (
            <div className="text-center py-8 text-muted-foreground">
              <p>没有可用的业务系统</p>
              <p className="text-sm mt-1">请联系管理员配置业务系统</p>
            </div>
          ) : (
            <RadioGroup value={selectedId ?? ''} onValueChange={setSelectedId}>
              <div className="space-y-3 max-h-[400px] overflow-y-auto">
                {systems.map((system) => (
                  <SystemCard
                    key={system.id}
                    system={system}
                    selected={selectedId === system.id}
                    onSelect={() => setSelectedId(system.id)}
                  />
                ))}
              </div>
            </RadioGroup>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button onClick={handleConfirm} disabled={!selectedId}>
            开始会话
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
