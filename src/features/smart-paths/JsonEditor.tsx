import { Textarea } from '@/components/ui/textarea';
import type { SmartPathStep } from '@/types/settings';

interface JsonEditorProps {
  steps: SmartPathStep[];
  onChange: (steps: SmartPathStep[]) => void;
}

export function JsonEditor({ steps, onChange }: JsonEditorProps) {
  const handleChange = (value: string) => {
    try {
      const parsed = JSON.parse(value);
      if (Array.isArray(parsed)) {
        onChange(parsed);
      }
    } catch {
      // Invalid JSON, ignore
    }
  };

  return (
    <div className="h-full">
      <Textarea
        value={JSON.stringify(steps, null, 2)}
        onChange={(e) => handleChange(e.target.value)}
        className="h-full font-mono text-xs resize-none"
        placeholder="Steps JSON..."
      />
    </div>
  );
}
