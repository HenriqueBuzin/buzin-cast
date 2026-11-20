import {supportsH264Mode} from './mediaCapabilities';

export const experienceProfiles = {
  default: 'Padrão',
  cinema: 'Cinema',
  presentation: 'Apresentação',
  games: 'Jogos',
} as const;

export type ExperienceProfile = keyof typeof experienceProfiles;
export type MirrorVideoMode = {width: number; height: number; fps: number};

export function isExperienceProfile(value: string): value is ExperienceProfile {
  return Object.prototype.hasOwnProperty.call(experienceProfiles, value);
}

const hd30: MirrorVideoMode = {width: 1280, height: 720, fps: 30};
const hd60: MirrorVideoMode = {...hd30, fps: 60};
const full30: MirrorVideoMode = {width: 1920, height: 1080, fps: 30};
const full60: MirrorVideoMode = {...full30, fps: 60};
const modes = {
  cinema: [full30, hd30],
  presentation: [full60, full30, hd60, hd30],
  games: [hd60, hd30],
};

export function chooseMirrorVideoMode(profile: ExperienceProfile): MirrorVideoMode {
  if (profile === 'default') {return full30;}
  try {
    return modes[profile].find(supportsH264Mode) ?? hd30;
  } catch {
    // The VVD and unavailable media services can report no usable capabilities.
    return hd30;
  }
}
