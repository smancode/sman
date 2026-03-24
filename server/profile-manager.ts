import fs from 'fs';
import path from 'path';
import { createLogger, type Logger } from './utils/logger.js';
import type { Profile } from './types.js';

interface CreateProfileInput {
  systemId: string;
  name: string;
  workspace: string;
  description: string;
  skills: string[];
  autoTriggers?: {
    onInit?: string[];
    onConversationStart?: string[];
  };
  claudeMdTemplate?: string;
}

type UpdateProfileInput = Partial<Omit<CreateProfileInput, 'systemId'>>;

export class ProfileManager {
  private homeDir: string;
  private profilesDir: string;
  private log: Logger;

  constructor(homeDir: string) {
    this.homeDir = homeDir;
    this.profilesDir = path.join(homeDir, 'profiles');
    this.log = createLogger('ProfileManager');
    if (!fs.existsSync(this.profilesDir)) {
      fs.mkdirSync(this.profilesDir, { recursive: true });
    }
  }

  private profilePath(systemId: string): string {
    const dir = path.join(this.profilesDir, systemId);
    return path.join(dir, 'profile.json');
  }

  createProfile(input: CreateProfileInput): Profile {
    const filePath = this.profilePath(input.systemId);
    if (fs.existsSync(filePath)) {
      throw new Error(`Profile already exists: ${input.systemId}`);
    }

    const dir = path.join(this.profilesDir, input.systemId);
    fs.mkdirSync(dir, { recursive: true });

    const profile: Profile = {
      systemId: input.systemId,
      name: input.name,
      workspace: input.workspace,
      description: input.description,
      skills: input.skills,
      autoTriggers: {
        onInit: input.autoTriggers?.onInit ?? [],
        onConversationStart: input.autoTriggers?.onConversationStart ?? [],
      },
      claudeMdTemplate: input.claudeMdTemplate,
    };

    fs.writeFileSync(filePath, JSON.stringify(profile, null, 2), 'utf-8');
    this.log.info(`Created profile: ${input.systemId}`);
    return profile;
  }

  getProfile(systemId: string): Profile | undefined {
    const filePath = this.profilePath(systemId);
    if (!fs.existsSync(filePath)) return undefined;
    const raw = fs.readFileSync(filePath, 'utf-8');
    return JSON.parse(raw) as Profile;
  }

  listProfiles(): Profile[] {
    if (!fs.existsSync(this.profilesDir)) return [];
    const dirs = fs.readdirSync(this.profilesDir, { withFileTypes: true })
      .filter(d => d.isDirectory());
    const profiles: Profile[] = [];
    for (const dir of dirs) {
      const filePath = path.join(this.profilesDir, dir.name, 'profile.json');
      if (fs.existsSync(filePath)) {
        const raw = fs.readFileSync(filePath, 'utf-8');
        profiles.push(JSON.parse(raw) as Profile);
      }
    }
    return profiles;
  }

  updateProfile(systemId: string, updates: UpdateProfileInput): Profile {
    const profile = this.getProfile(systemId);
    if (!profile) throw new Error(`Profile not found: ${systemId}`);
    const updated = { ...profile, ...updates };
    const filePath = this.profilePath(systemId);
    fs.writeFileSync(filePath, JSON.stringify(updated, null, 2), 'utf-8');
    this.log.info(`Updated profile: ${systemId}`);
    return updated;
  }

  deleteProfile(systemId: string): void {
    const dir = path.join(this.profilesDir, systemId);
    if (fs.existsSync(dir)) {
      fs.rmSync(dir, { recursive: true, force: true });
      this.log.info(`Deleted profile: ${systemId}`);
    }
  }
}
