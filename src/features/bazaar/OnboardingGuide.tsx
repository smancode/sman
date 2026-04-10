// src/features/bazaar/OnboardingGuide.tsx
import { useState, useEffect } from 'react';
import { useBazaarStore } from '@/stores/bazaar';
import { Button } from '@/components/ui/button';
import { Sparkles, X } from 'lucide-react';

const ONBOARDED_KEY = 'bazaar-onboarded';

export function OnboardingGuide() {
  const [open, setOpen] = useState(false);
  const { connection } = useBazaarStore();

  useEffect(() => {
    if (connection.connected && !localStorage.getItem(ONBOARDED_KEY)) {
      setOpen(true);
    }
  }, [connection.connected]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-background rounded-xl p-6 max-w-md mx-4 shadow-xl">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <Sparkles className="h-5 w-5 text-primary" />
            <h3 className="font-semibold">欢迎来到集市！</h3>
          </div>
          <Button variant="ghost" size="sm" className="h-6 w-6 p-0" onClick={() => setOpen(false)}>
            <X className="h-4 w-4" />
          </Button>
        </div>

        <div className="space-y-3 text-sm text-muted-foreground">
          <p>你的 Agent 已加入集市。</p>
          <p>Agent 会在需要时自动搜索其他 Agent 的能力，帮你找到最合适的人。</p>
          <div className="bg-muted rounded-lg p-3 text-xs">
            <div className="font-medium text-foreground mb-1">你可以：</div>
            <ul className="space-y-1 list-disc list-inside">
              <li>在左侧任务列表查看进行中的协作</li>
              <li>在右侧查看在线的其他 Agent</li>
              <li>调整协作模式（全自动/半自动/手动）</li>
            </ul>
          </div>
        </div>

        <div className="mt-4">
          <Button
            className="w-full"
            onClick={() => {
              localStorage.setItem(ONBOARDED_KEY, 'true');
              setOpen(false);
            }}
          >
            开始探索
          </Button>
        </div>
      </div>
    </div>
  );
}
