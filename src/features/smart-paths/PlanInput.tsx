import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Loader2, Wand2 } from 'lucide-react';
import { useSmartPathStore } from '@/stores/smart-path';

interface PlanInputProps {
  workspace: string;
  onPlanGenerated: (plan: any) => void;
}

export function PlanInput({ workspace, onPlanGenerated }: PlanInputProps) {
  const [description, setDescription] = useState('');
  const [generating, setGenerating] = useState(false);
  const generatePlan = useSmartPathStore((s) => s.generatePlan);

  const handleGenerate = async () => {
    if (!description.trim() || !workspace) return;

    setGenerating(true);
    try {
      const plan = await generatePlan(description, workspace);
      onPlanGenerated(plan);
      setDescription('');
    } catch (err) {
      console.error('Failed to generate plan:', err);
    } finally {
      setGenerating(false);
    }
  };

  return (
    <div className="space-y-2">
      <label className="text-sm font-medium">输入你想做什么：</label>
      <div className="flex gap-2">
        <Textarea
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="用自然语言描述你想完成的任务..."
          className="flex-1 min-h-[80px]"
          disabled={generating}
        />
        <Button
          onClick={handleGenerate}
          disabled={!description.trim() || generating}
          className="shrink-0"
        >
          {generating ? <Loader2 className="h-4 w-4 animate-spin" /> : <Wand2 className="h-4 w-4" />}
          生成计划
        </Button>
      </div>
    </div>
  );
}
