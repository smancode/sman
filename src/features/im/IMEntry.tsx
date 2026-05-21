import { useLocale, t } from '@/locales';

export default function IMEntry() {
  useLocale();
  return (
    <div className="flex h-full items-center justify-center text-muted-foreground">
      {t('im.title')}
    </div>
  );
}
