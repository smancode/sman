import { useState, useCallback } from 'react';
import { MessageCircleQuestion, Send, Check } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { useChatStore } from '@/stores/chat';

interface QuestionData {
  question: string;
  header: string;
  options: Array<{ label: string; description: string }>;
  multiSelect: boolean;
}

interface AskUserCardProps {
  askId: string;
  questions: QuestionData[];
  answered: boolean;
  answers?: Record<string, string[]>;
}

export function AskUserCard({ askId, questions, answered, answers }: AskUserCardProps) {
  const [selections, setSelections] = useState<Record<number, string[]>>({});
  const [otherTexts, setOtherTexts] = useState<Record<number, string>>({});
  const answerAskUser = useChatStore(s => s.answerAskUser);

  const toggleOption = useCallback((qIndex: number, option: string, multiSelect: boolean) => {
    setSelections(prev => {
      const current = prev[qIndex] ?? [];
      if (multiSelect) {
        const next = current.includes(option)
          ? current.filter(o => o !== option)
          : [...current, option];
        return { ...prev, [qIndex]: next };
      }
      return { ...prev, [qIndex]: current.includes(option) ? [] : [option] };
    });
  }, []);

  const handleSubmit = useCallback(() => {
    const finalAnswers: Record<string, string[]> = {};
    for (let i = 0; i < questions.length; i++) {
      const selected = selections[i] ?? [];
      const other = otherTexts[i]?.trim();
      finalAnswers[questions[i].question] = other ? [...selected, other] : selected;
    }
    answerAskUser(askId, finalAnswers);
  }, [questions, selections, otherTexts, askId, answerAskUser]);

  // Answered state
  if (answered) {
    return (
      <div className="w-full rounded-lg border border-border bg-muted/30 p-3 space-y-2">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Check className="h-4 w-4 text-green-500" />
          <span className="font-medium">已回答</span>
        </div>
        {questions.map((q, i) => (
          <div key={i} className="text-sm">
            <p className="text-foreground/80 mb-0.5">{q.question}</p>
            <p className="text-primary text-xs font-medium">
              {answers?.[q.question]?.join(', ') ?? '-'}
            </p>
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="w-full rounded-lg border border-primary/30 bg-card p-4 space-y-4 shadow-sm">
      <div className="flex items-center gap-2 text-sm font-medium text-foreground">
        <MessageCircleQuestion className="h-4 w-4 text-primary" />
        <span>Claude 想了解更多</span>
      </div>

      {questions.map((q, qIndex) => (
        <div key={qIndex} className="space-y-2">
          <p className="text-sm font-medium text-foreground">{q.question}</p>
          <div className="flex flex-wrap gap-2">
            {q.options?.map(option => {
              const selected = selections[qIndex]?.includes(option.label) ?? false;
              return (
                <button
                  key={option.label}
                  onClick={() => toggleOption(qIndex, option.label, q.multiSelect)}
                  title={option.description}
                  className={cn(
                    'px-3 py-1.5 rounded-full text-xs border transition-colors text-left',
                    selected
                      ? 'bg-primary text-primary-foreground border-primary'
                      : 'bg-muted text-muted-foreground border-border hover:border-primary/50',
                  )}
                >
                  {option.label}
                </button>
              );
            })}
          </div>
          <Input
            placeholder="其他..."
            value={otherTexts[qIndex] ?? ''}
            onChange={e => setOtherTexts(prev => ({ ...prev, [qIndex]: e.target.value }))}
            className="h-8 text-xs"
          />
        </div>
      ))}

      <Button size="sm" onClick={handleSubmit} className="w-full">
        <Send className="h-3.5 w-3.5 mr-1.5" />
        提交回答
      </Button>
    </div>
  );
}
