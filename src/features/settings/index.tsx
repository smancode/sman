/**
 * Settings Page
 * Application settings including gateway connection configuration
 */
import { useState, useEffect } from 'react';
import { Server } from 'lucide-react';
import { ConnectionSettings } from './ConnectionSettings';
import { fetchServerConfig } from '@/stores/gateway';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';

export function Settings() {
  const [integratedMode, setIntegratedMode] = useState<boolean | null>(null);

  useEffect(() => {
    // Check if backend config is available
    fetchServerConfig().then((config) => {
      setIntegratedMode(config !== null);
    });
  }, []);

  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Settings</h1>
        <p className="text-muted-foreground mt-1">
          Configure your SmanWeb application
        </p>
      </div>

      <div className="max-w-2xl space-y-6">
        {/* Integration Mode Status */}
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Server className="h-5 w-5" />
              Integration Mode
            </CardTitle>
            <CardDescription>
              System configuration source
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="flex items-center gap-3">
              <Badge
                variant={integratedMode === true ? 'default' : integratedMode === false ? 'outline' : 'secondary'}
                className={cn(
                  'text-xs',
                  integratedMode === true && 'bg-green-500/10 text-green-600 dark:text-green-400 border-green-500/20'
                )}
              >
                {integratedMode === null
                  ? 'Checking...'
                  : integratedMode
                    ? 'Integrated'
                    : 'Standalone'}
              </Badge>
              <span className="text-sm text-muted-foreground">
                {integratedMode === null
                  ? 'Detecting configuration source...'
                  : integratedMode
                    ? 'Using server-side configuration from backend'
                    : 'Using client-side configuration'}
              </span>
            </div>
          </CardContent>
        </Card>

        <ConnectionSettings />
      </div>
    </div>
  );
}

export default Settings;
