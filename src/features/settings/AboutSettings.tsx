import { useState } from 'react';
import { Info, ChevronDown, ChevronUp } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { t } from '@/locales';

interface LicenseEntry {
  name: string;
  copyright: string;
  license: string;
  licenseUrl: string;
}

const OPEN_SOURCE_LICENSES: LicenseEntry[] = [
  { name: 'React', copyright: 'Copyright Meta Platforms, Inc.', license: 'MIT License', licenseUrl: 'https://github.com/facebook/react/blob/main/LICENSE' },
  { name: 'React Router', copyright: 'Copyright Remix Software Inc.', license: 'MIT License', licenseUrl: 'https://github.com/remix-run/react-router/blob/main/LICENSE' },
  { name: 'React DOM', copyright: 'Copyright Meta Platforms, Inc.', license: 'MIT License', licenseUrl: 'https://github.com/facebook/react/blob/main/LICENSE' },
  { name: 'Electron', copyright: 'Copyright Electron contributors.', license: 'MIT License', licenseUrl: 'https://github.com/electron/electron/blob/main/LICENSE' },
  { name: 'Express', copyright: 'Copyright TJ Holowaychuk and contributors.', license: 'MIT License', licenseUrl: 'https://github.com/expressjs/express/blob/master/LICENSE' },
  { name: 'Vite', copyright: 'Copyright Yuxi (Evan) You and Vite contributors.', license: 'MIT License', licenseUrl: 'https://github.com/vitejs/vite/blob/main/LICENSE' },
  { name: 'TypeScript', copyright: 'Copyright Microsoft Corporation.', license: 'Apache-2.0 License', licenseUrl: 'https://github.com/microsoft/TypeScript/blob/main/LICENSE' },
  { name: 'Zustand', copyright: 'Copyright Paul Henschel and contributors.', license: 'MIT License', licenseUrl: 'https://github.com/pmndrs/zustand/blob/main/LICENSE' },
  { name: 'TailwindCSS', copyright: 'Copyright Adam Wathan and Tailwind Labs.', license: 'MIT License', licenseUrl: 'https://github.com/tailwindlabs/tailwindcss/blob/main/LICENSE' },
  { name: 'Radix UI', copyright: 'Copyright WorkOS, Inc.', license: 'MIT License', licenseUrl: 'https://github.com/radix-ui/primitives/blob/main/LICENSE' },
  { name: 'CodeMirror', copyright: 'Copyright Marijn Haverbeke and contributors.', license: 'MIT License', licenseUrl: 'https://github.com/codemirror/codemirror/blob/main/LICENSE' },
  { name: 'Shiki', copyright: 'Copyright Shiki contributors.', license: 'MIT License', licenseUrl: 'https://github.com/shikijs/shiki/blob/main/LICENSE' },
  { name: 'better-sqlite3', copyright: 'Copyright Joshua Wise and contributors.', license: 'MIT License', licenseUrl: 'https://github.com/WiseLibs/better-sqlite3/blob/master/LICENSE' },
  { name: 'ws', copyright: 'Copyright Einar Otto Stangvik and contributors.', license: 'MIT License', licenseUrl: 'https://github.com/websockets/ws/blob/master/LICENSE' },
  { name: 'Zod', copyright: 'Copyright Colin McDonnell and contributors.', license: 'MIT License', licenseUrl: 'https://github.com/colinhacks/zod/blob/main/LICENSE' },
  { name: 'TanStack Query', copyright: 'Copyright Tanner Linsley and contributors.', license: 'MIT License', licenseUrl: 'https://github.com/TanStack/query/blob/main/LICENSE' },
  { name: 'Lucide Icons', copyright: 'Copyright Lucide contributors.', license: 'ISC License', licenseUrl: 'https://github.com/lucide-icons/lucide/blob/main/LICENSE' },
  { name: 'React Markdown', copyright: 'Copyright Titus Wormer and contributors.', license: 'MIT License', licenseUrl: 'https://github.com/remarkjs/react-markdown/blob/main/license' },
  { name: 'remark-gfm', copyright: 'Copyright Titus Wormer and contributors.', license: 'MIT License', licenseUrl: 'https://github.com/remarkjs/remark-gfm/blob/main/license' },
  { name: 'Streamdown', copyright: 'Copyright Streamdown contributors.', license: 'Apache-2.0 License', licenseUrl: 'https://github.com/nicoalfonso/streamdown/blob/main/LICENSE' },
  { name: 'electron-builder', copyright: 'Copyright electron-builder contributors.', license: 'MIT License', licenseUrl: 'https://github.com/electron-userland/electron-builder/blob/master/LICENSE' },
  { name: 'electron-updater', copyright: 'Copyright electron-builder contributors.', license: 'MIT License', licenseUrl: 'https://github.com/electron-userland/electron-updater/blob/master/LICENSE' },
  { name: 'Phaser', copyright: 'Copyright Richard Davey and Photon Storm Ltd.', license: 'MIT License', licenseUrl: 'https://github.com/phaserjs/phaser/blob/master/LICENSE' },
  { name: 'qrcode', copyright: 'Copyright Ryan Day and contributors.', license: 'MIT License', licenseUrl: 'https://github.com/soldair/node-qrcode/blob/master/license' },
  { name: 'uuid', copyright: 'Copyright uuid contributors.', license: 'MIT License', licenseUrl: 'https://github.com/uuidjs/uuid/blob/main/LICENSE.md' },
  { name: 'yaml', copyright: 'Copyright Eemeli Aro and contributors.', license: 'ISC License', licenseUrl: 'https://github.com/eemeli/yaml/blob/main/LICENSE' },
  { name: 'cron-parser', copyright: 'Copyright Harri Sarsa and contributors.', license: 'MIT License', licenseUrl: 'https://github.com/harrsirott/cron-parser/blob/master/LICENSE' },
  { name: 'node-cron', copyright: 'Copyright node-cron contributors.', license: 'ISC License', licenseUrl: 'https://github.com/node-cron/node-cron/blob/master/LICENSE.md' },
  { name: 'gray-matter', copyright: 'Copyright Jon Schlinkert and contributors.', license: 'MIT License', licenseUrl: 'https://github.com/jonschlinkert/gray-matter/blob/master/LICENSE' },
  { name: 'clsx', copyright: 'Copyright Luke Edwards and contributors.', license: 'MIT License', licenseUrl: 'https://github.com/lukeed/clsx/blob/master/license' },
  { name: 'tailwind-merge', copyright: 'Copyright David Castilho and contributors.', license: 'MIT License', licenseUrl: 'https://github.com/dcastil/tailwind-merge/blob/main/LICENSE' },
  { name: 'class-variance-authority', copyright: 'Copyright Joe Bell and contributors.', license: 'Apache-2.0 License', licenseUrl: 'https://github.com/joe-bell/cva/blob/main/LICENSE' },
  { name: '@larksuiteoapi/node-sdk', copyright: 'Copyright Larksuite and contributors.', license: 'MIT License', licenseUrl: 'https://github.com/larksuite/node-sdk/blob/main/LICENSE' },
  { name: 'Vitest', copyright: 'Copyright Vitest contributors.', license: 'MIT License', licenseUrl: 'https://github.com/vitest-dev/vitest/blob/main/LICENSE' },
  { name: 'electron-screenshots', copyright: 'Copyright nashaofu and contributors.', license: 'MIT License', licenseUrl: 'https://github.com/nashaofu/electron-screenshots/blob/main/LICENSE' },
  { name: '@anthropic-ai/tokenizer', copyright: 'Copyright Anthropic PBC.', license: 'Apache-2.0 License', licenseUrl: 'https://github.com/anthropics/anthropic-sdk-typescript/blob/main/LICENSE' },
];

const PROPRIETARY_LICENSES: LicenseEntry[] = [
  {
    name: '@anthropic-ai/claude-agent-sdk',
    copyright: 'Copyright Anthropic PBC. All rights reserved.',
    license: 'Proprietary',
    licenseUrl: 'https://code.claude.com/docs/en/legal-and-compliance',
  },
  {
    name: '@anthropic-ai/claude-code',
    copyright: 'Copyright Anthropic PBC. All rights reserved.',
    license: 'Proprietary',
    licenseUrl: 'https://code.claude.com/docs/en/legal-and-compliance',
  },
];

const LICENSE_GROUPS = [
  { key: 'oss', entries: OPEN_SOURCE_LICENSES },
  { key: 'proprietary', entries: PROPRIETARY_LICENSES },
];

export function AboutSettings({ id }: { id?: string }) {
  const [expanded, setExpanded] = useState(false);

  return (
    <Card id={id}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Info className="h-5 w-5" />
          {t('settings.about.title')}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-3">
          <p className="text-xs text-muted-foreground/80 leading-relaxed">
            {t('settings.about.licenses.intro')}
          </p>

          <button
            type="button"
            onClick={() => setExpanded(!expanded)}
            className="flex items-center justify-between w-full text-sm font-medium hover:text-foreground/80 transition-colors"
          >
            <span>{t('settings.about.licenses')}</span>
            {expanded ? (
              <ChevronUp className="h-4 w-4 text-muted-foreground" />
            ) : (
              <ChevronDown className="h-4 w-4 text-muted-foreground" />
            )}
          </button>

          {expanded && (
            <div className="mt-3 space-y-4">
              {LICENSE_GROUPS.map((group) => (
                <div key={group.key}>
                  <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2">
                    {t(`settings.about.licenses.${group.key}`)}
                  </h4>
                  <div className="space-y-2.5">
                    {group.entries.map((entry) => (
                      <div key={entry.name} className="text-xs leading-relaxed">
                        <div className="font-medium text-foreground/90">{entry.name}</div>
                        <div className="text-muted-foreground/70">{entry.copyright}</div>
                        <div className="flex items-center gap-1">
                          <span className={entry.license === 'Proprietary' ? 'text-amber-600 dark:text-amber-400 font-medium' : 'text-muted-foreground'}>
                            {t('settings.about.licenses.licensedUnder')}
                          </span>
                          <a
                            href={entry.licenseUrl}
                            target="_blank"
                            rel="noopener noreferrer"
                            className={entry.license === 'Proprietary' ? 'text-amber-600 dark:text-amber-400 hover:underline' : 'text-primary hover:underline'}
                          >
                            {entry.license}
                          </a>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              ))}

              <p className="text-[11px] text-muted-foreground/60 leading-relaxed pt-3 border-t border-border/40">
                {t('settings.about.licenses.disclaimer')}
              </p>
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
