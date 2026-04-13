// src/features/bazaar/OnboardingGuide.tsx
import { useState, useEffect } from 'react';
import { useBazaarStore } from '@/stores/bazaar';
import { Button } from '@/components/ui/button';
import { Sparkles, X, HelpCircle } from 'lucide-react';

const ONBOARDED_KEY = 'bazaar-onboarded';

export function OnboardingGuide() {
  const [open, setOpen] = useState(false);
  const { connection } = useBazaarStore();

  useEffect(() => {
    if (connection.connected && !localStorage.getItem(ONBOARDED_KEY)) {
      setOpen(true);
    }
  }, [connection.connected]);

  const dismiss = () => {
    localStorage.setItem(ONBOARDED_KEY, 'true');
    setOpen(false);
  };

  return (
    <>
      {/* 帮助按钮 — 始终显示在右上角 */}
      {!open && connection.connected && (
        <button
          onClick={() => setOpen(true)}
          className="absolute top-14 right-4 z-20 p-1.5 rounded-full bg-background/80 backdrop-blur-sm border border-border hover:bg-muted transition-colors"
          title="帮助"
        >
          <HelpCircle className="h-4 w-4 text-muted-foreground" />
        </button>
      )}

      {/* 引导卡片 — 非模态，左上方显示 */}
      {open && (
        <div className="absolute top-14 left-4 z-20 w-80 bg-background/95 backdrop-blur-sm border border-border rounded-lg shadow-lg">
          <div className="flex items-center justify-between p-3 border-b">
            <div className="flex items-center gap-2">
              <Sparkles className="h-4 w-4 text-primary" />
              <h3 className="text-sm font-semibold">欢迎来到世界</h3>
            </div>
            <Button variant="ghost" size="sm" className="h-5 w-5 p-0" onClick={dismiss}>
              <X className="h-3.5 w-3.5" />
            </Button>
          </div>

          <div className="p-3 space-y-2 text-xs text-muted-foreground">
            <p>你的 Agent 正在帮你自动协作。它会：</p>
            <ul className="list-disc list-inside space-y-0.5 ml-1">
              <li>遇到解决不了的问题时，搜索其他 Agent 求助</li>
              <li>找到合适的人后，自动发起协作对话</li>
              <li>你可以随时切换到「世界」视图查看所有 Agent 的位置</li>
            </ul>

            <div className="border-t pt-2 mt-2">
              <p className="font-medium text-foreground text-xs">协作模式控制 Agent 的自主程度：</p>
              <ul className="list-disc list-inside space-y-0.5 ml-1 mt-1">
                <li><span className="font-medium">全自动</span>：Agent 自行接任务，不打扰你</li>
                <li><span className="font-medium">半自动</span>：接任务前通知你，30秒无响应自动接（推荐）</li>
                <li><span className="font-medium">手动</span>：每一步都需要你确认</li>
              </ul>
            </div>
          </div>

          <div className="p-3 border-t">
            <Button size="sm" className="w-full text-xs h-7" onClick={dismiss}>
              知道了
            </Button>
          </div>
        </div>
      )}
    </>
  );
}
