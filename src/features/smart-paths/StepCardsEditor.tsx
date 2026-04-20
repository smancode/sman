import { useState } from 'react';
import { Plus, Trash2, GripVertical } from 'lucide-react';
import { Button } from '@/components/ui/button';
import type { SmartPathStep } from '@/types/settings';

interface StepCardsEditorProps {
  steps: SmartPathStep[];
  onChange: (steps: SmartPathStep[]) => void;
}

export function StepCardsEditor({ steps, onChange }: StepCardsEditorProps) {
  const [draggedIndex, setDraggedIndex] = useState<number | null>(null);

  const moveStep = (from: number, to: number) => {
    const newSteps = [...steps];
    const [removed] = newSteps.splice(from, 1);
    newSteps.splice(to, 0, removed);
    onChange(newSteps);
  };

  const addStep = () => {
    onChange([...steps, { mode: 'serial' as const, actions: [] }]);
  };

  const removeStep = (index: number) => {
    onChange(steps.filter((_, i) => i !== index));
  };

  return (
    <div className="space-y-2">
      {steps.map((step, index) => (
        <div
          key={index}
          className="border rounded-md p-3 space-y-2"
          draggable
          onDragStart={() => setDraggedIndex(index)}
          onDragOver={(e) => {
            e.preventDefault();
            if (draggedIndex !== null && draggedIndex !== index) {
              moveStep(draggedIndex, index);
              setDraggedIndex(index);
            }
          }}
          onDragEnd={() => setDraggedIndex(null)}
        >
          <div className="flex items-center gap-2">
            <GripVertical className="h-4 w-4 text-muted-foreground cursor-move" />
            <span className="text-sm font-medium">步骤 {index + 1}</span>
            <span className="text-xs text-muted-foreground">
              {step.mode === 'serial' ? '串行' : '并行'}
            </span>
            <Button
              variant="ghost"
              size="icon"
              className="ml-auto h-6 w-6"
              onClick={() => removeStep(index)}
            >
              <Trash2 className="h-3 w-3" />
            </Button>
          </div>

          <div className="text-sm space-y-1">
            {step.actions.map((action, actionIndex) => (
              <div key={actionIndex} className="flex items-center gap-2 text-xs">
                <span className="px-2 py-0.5 rounded bg-muted">
                  {action.type === 'python' ? 'Python' : action.skillId || 'skill'}
                </span>
                <span className="text-muted-foreground">
                  {action.type === 'python' ? '执行 Python 代码' : `调用 ${action.skillId} skill`}
                </span>
              </div>
            ))}
          </div>
        </div>
      ))}

      <Button variant="outline" className="w-full" onClick={addStep}>
        <Plus className="h-4 w-4 mr-1" />
        添加步骤
      </Button>
    </div>
  );
}
