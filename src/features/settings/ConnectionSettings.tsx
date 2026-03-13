/**
 * ConnectionSettings Component
 * UI for configuring and managing gateway connection
 */
import { useState, useEffect } from 'react';
import { Save, Wifi, WifiOff, RefreshCw, Check, X } from 'lucide-react';
import { useGatewayStore } from '@/stores/gateway';
import { useGatewayConnection } from '@/hooks/use-gateway-connection';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';

export function ConnectionSettings() {
  const storedUrl = useGatewayStore((s) => s.url);
  const storedToken = useGatewayStore((s) => s.token);
  const setConfig = useGatewayStore((s) => s.setConfig);

  const [url, setUrl] = useState(storedUrl);
  const [token, setToken] = useState(storedToken);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<'success' | 'failed' | null>(null);
  const [saved, setSaved] = useState(false);

  const { status, connect, disconnect, testConnection, isConnected, isConnecting } = useGatewayConnection();

  // Sync form with store
  useEffect(() => {
    setUrl(storedUrl);
    setToken(storedToken);
  }, [storedUrl, storedToken]);

  const handleSave = () => {
    setConfig(url, token);
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  };

  const handleTest = async () => {
    setTesting(true);
    setTestResult(null);

    // Temporarily update config for testing
    const originalUrl = storedUrl;
    const originalToken = storedToken;
    setConfig(url, token);

    const result = await testConnection();
    setTestResult(result ? 'success' : 'failed');

    // Restore original if test failed
    if (!result) {
      setConfig(originalUrl, originalToken);
    }

    setTesting(false);
  };

  const handleConnect = () => {
    if (isConnected) {
      disconnect();
    } else {
      connect();
    }
  };

  const hasChanges = url !== storedUrl || token !== storedToken;

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <div>
            <CardTitle className="flex items-center gap-2">
              <Wifi className="h-5 w-5" />
              Gateway Connection
            </CardTitle>
            <CardDescription>
              Configure connection to OpenClaw Gateway
            </CardDescription>
          </div>
          <Badge
            variant={isConnected ? 'default' : isConnecting ? 'secondary' : 'outline'}
            className={cn(
              'text-xs',
              isConnected && 'bg-green-500/10 text-green-600 dark:text-green-400 border-green-500/20'
            )}
          >
            {isConnected ? 'Connected' : isConnecting ? 'Connecting...' : 'Disconnected'}
          </Badge>
        </div>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* Status Message */}
        {status.state === 'disconnected' && status.error && (
          <div className="flex items-center gap-2 p-3 rounded-lg bg-destructive/10 text-destructive text-sm">
            <WifiOff className="h-4 w-4 shrink-0" />
            <span>{status.error}</span>
          </div>
        )}

        {/* Gateway URL */}
        <div className="space-y-2">
          <Label htmlFor="gateway-url">Gateway URL</Label>
          <Input
            id="gateway-url"
            type="url"
            placeholder="http://localhost:8080"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
          />
        </div>

        {/* Token */}
        <div className="space-y-2">
          <Label htmlFor="gateway-token">Authentication Token (Optional)</Label>
          <Input
            id="gateway-token"
            type="password"
            placeholder="Bearer token"
            value={token}
            onChange={(e) => setToken(e.target.value)}
          />
        </div>

        {/* Actions */}
        <div className="flex items-center gap-2 pt-2">
          <Button
            variant="outline"
            size="sm"
            onClick={handleTest}
            disabled={!url || testing}
          >
            {testing ? (
              <RefreshCw className="h-4 w-4 mr-2 animate-spin" />
            ) : testResult === 'success' ? (
              <Check className="h-4 w-4 mr-2 text-green-500" />
            ) : testResult === 'failed' ? (
              <X className="h-4 w-4 mr-2 text-destructive" />
            ) : (
              <RefreshCw className="h-4 w-4 mr-2" />
            )}
            Test
          </Button>

          <Button
            variant="outline"
            size="sm"
            onClick={handleSave}
            disabled={!hasChanges}
          >
            {saved ? (
              <Check className="h-4 w-4 mr-2 text-green-500" />
            ) : (
              <Save className="h-4 w-4 mr-2" />
            )}
            Save
          </Button>

          <Button
            size="sm"
            onClick={handleConnect}
            disabled={!url}
            variant={isConnected ? 'destructive' : 'default'}
          >
            {isConnected ? 'Disconnect' : 'Connect'}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
