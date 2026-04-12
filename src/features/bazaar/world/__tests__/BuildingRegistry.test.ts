import { describe, it, expect } from 'vitest';
import { BuildingRegistry } from '../BuildingRegistry';

describe('BuildingRegistry', () => {
  it('should return action for known building type', () => {
    const reg = new BuildingRegistry();
    expect(reg.getAction('reputation')).toEqual({ panel: 'leaderboard' });
  });

  it('should return null for unknown building type', () => {
    const reg = new BuildingRegistry();
    expect(reg.getAction('unknown')).toBeNull();
  });

  it('should allow overrides via constructor', () => {
    const reg = new BuildingRegistry({ search: { panel: 'chat' } });
    expect(reg.getAction('search')).toEqual({ panel: 'chat' });
    expect(reg.getAction('reputation')).toEqual({ panel: 'leaderboard' });
  });

  it('should allow runtime registration', () => {
    const reg = new BuildingRegistry();
    reg.register('mailbox', { panel: 'chat' });
    expect(reg.getAction('mailbox')).toEqual({ panel: 'chat' });
  });
});
