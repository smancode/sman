/**
 * Initialize capabilities registry — scans plugins/ and generates ~/.sman/capabilities.json
 *
 * Run during server startup to ensure the capability catalog is up to date.
 */
import type { CapabilityManifest } from '../server/capabilities/types.js';
export declare function initCapabilities(homeDir: string, pluginsDir: string): CapabilityManifest;
