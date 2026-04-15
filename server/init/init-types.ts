export type ProjectType = 'java' | 'python' | 'node' | 'react' | 'go' | 'rust' | 'docs' | 'mixed' | 'empty';

export interface WorkspaceScanResult {
  types: ProjectType[];
  languages: Record<string, number>;
  markers: string[];
  packageJson?: { name: string; scripts: string[]; deps: string[] };
  pomXml?: { groupId: string; artifactId: string; deps: string[] };
  /** Top-level directory names (excluding node_modules, .git, dist, build, target) */
  topDirs: string[];
  fileCount: number;
  isGitRepo: boolean;
  hasClaudeMd: boolean;
}

export interface CapabilityMatch {
  capabilityId: string;
  reason: string;
}

export interface CapabilityMatchResult {
  matches: CapabilityMatch[];
  projectSummary: string;
  techStack: string[];
}

export interface InitResult {
  success: boolean;
  scanResult: WorkspaceScanResult;
  matchResult: CapabilityMatchResult;
  injectedSkills: string[];
  claudeMdGenerated: boolean;
  error?: string;
}

export type InitCardType = 'initializing' | 'complete' | 'already' | 'error';

export interface InitCard {
  type: InitCardType;
  workspace: string;
  /** For 'initializing' cards: which phase we're in */
  phase?: 'scanning' | 'matching' | 'injecting';
  /** For 'complete' and 'already' cards */
  projectSummary?: string;
  techStack?: string[];
  injectedSkills?: Array<{ id: string; name: string }>;
  /** For 'already' cards */
  initializedAt?: string;
  /** For 'error' cards */
  error?: string;
}
