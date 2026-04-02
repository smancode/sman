import { User, ToggleLeft, ToggleRight } from 'lucide-react';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { useSettingsStore } from '@/stores/settings';

export function UserProfileSettings() {
  const { settings, updateLlm } = useSettingsStore();

  const llm = settings?.llm;
  const enabled = llm?.userProfile !== false;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <User className="h-5 w-5" />
          用户画像
        </CardTitle>
        <CardDescription>自动学习你的偏好和习惯，让助手越用越懂你</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* Enable Toggle */}
        <div className="flex items-center justify-between py-2">
          <div className="space-y-0.5">
            <Label>启用用户画像</Label>
            <p className="text-xs text-muted-foreground">
              每次对话后自动分析，积累你的偏好画像
            </p>
          </div>
          <button
            type="button"
            onClick={() => updateLlm({ userProfile: !enabled }).catch(() => {})}
            className="cursor-pointer"
          >
            {enabled
              ? <ToggleRight className="h-8 w-8 text-primary" />
              : <ToggleLeft className="h-8 w-8 text-muted-foreground" />
            }
          </button>
        </div>

        {/* Status description */}
        <div className="rounded-lg bg-muted/50 p-3 text-xs text-muted-foreground space-y-1">
          {enabled ? (
            <>
              <p>画像功能已开启。助手会在每次对话后自动更新画像，并在下次对话中参考。</p>
              <p>画像文件：<code className="bg-muted px-1 py-0.5 rounded">~/.sman/user-profile.md</code></p>
            </>
          ) : (
            <p>画像功能已关闭。助手不会自动学习你的偏好。</p>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
