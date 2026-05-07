import { User } from 'lucide-react';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { useSettingsStore } from '@/stores/settings';
import { t } from '@/locales';


export function UserProfileSettings() {
  const { settings, updateLlm } = useSettingsStore();

  const enabled = settings?.llm?.userProfile !== false;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <User className="h-5 w-5" />
          {t('userProfile.title')}
        </CardTitle>
        <CardDescription>{t('userProfile.desc')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex items-center justify-between py-2">
          <div className="space-y-0.5">
            <Label>{t('userProfile.enable')}</Label>
            <p className="text-xs text-muted-foreground">
              {t('userProfile.hint')}
            </p>
          </div>
          <Switch
            checked={enabled}
            onCheckedChange={(checked) => updateLlm({ userProfile: checked }).catch(() => {})}
          />
        </div>

        <div className="rounded-lg bg-muted/50 p-3 text-xs text-muted-foreground space-y-1">
          {enabled ? (
            <>
              <p>{t('userProfile.enabled')}。助手会在每次对话后自动更新画像，并在下次对话中参考。</p>
              <p>{t('userProfile.file')}<code className="bg-muted px-1 py-0.5 rounded">~/.sman/user-profile.md</code></p>
            </>
          ) : (
            <p>{t('userProfile.disabled')}。助手不会自动学习你的偏好。</p>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
