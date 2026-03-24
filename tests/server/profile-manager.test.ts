import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { ProfileManager } from '../../server/profile-manager.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('ProfileManager', () => {
  let pm: ProfileManager;
  let homeDir: string;

  beforeEach(() => {
    homeDir = path.join(os.tmpdir(), `smanbase-pm-${Date.now()}`);
    fs.mkdirSync(path.join(homeDir, 'profiles'), { recursive: true });
    pm = new ProfileManager(homeDir);
  });

  afterEach(() => {
    fs.rmSync(homeDir, { recursive: true, force: true });
  });

  it('should create a profile', () => {
    const profile = pm.createProfile({
      systemId: 'projectA',
      name: 'Project A',
      workspace: '/data/projectA',
      description: 'Spring Boot',
      skills: ['java-scanner'],
    });
    expect(profile.systemId).toBe('projectA');
    expect(profile.skills).toEqual(['java-scanner']);
  });

  it('should get a profile', () => {
    pm.createProfile({
      systemId: 'sys1',
      name: 'System 1',
      workspace: '/data/sys1',
      description: 'desc',
      skills: [],
    });
    const profile = pm.getProfile('sys1');
    expect(profile).toBeDefined();
    expect(profile!.name).toBe('System 1');
  });

  it('should list all profiles', () => {
    pm.createProfile({ systemId: 'a', name: 'A', workspace: '/a', description: '', skills: [] });
    pm.createProfile({ systemId: 'b', name: 'B', workspace: '/b', description: '', skills: [] });
    const profiles = pm.listProfiles();
    expect(profiles).toHaveLength(2);
  });

  it('should update a profile', () => {
    pm.createProfile({ systemId: 'sys1', name: 'Old', workspace: '/old', description: '', skills: [] });
    pm.updateProfile('sys1', { name: 'New', workspace: '/new' });
    const profile = pm.getProfile('sys1');
    expect(profile!.name).toBe('New');
    expect(profile!.workspace).toBe('/new');
  });

  it('should delete a profile', () => {
    pm.createProfile({ systemId: 'sys1', name: 'A', workspace: '/a', description: '', skills: [] });
    pm.deleteProfile('sys1');
    expect(pm.getProfile('sys1')).toBeUndefined();
  });

  it('should throw when creating profile with duplicate systemId', () => {
    pm.createProfile({ systemId: 'sys1', name: 'A', workspace: '/a', description: '', skills: [] });
    expect(() =>
      pm.createProfile({ systemId: 'sys1', name: 'B', workspace: '/b', description: '', skills: [] })
    ).toThrow();
  });
});
