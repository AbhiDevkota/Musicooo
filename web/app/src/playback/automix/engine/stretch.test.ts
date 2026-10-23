import { describe, expect, it } from 'vitest';
import { stretchAllowed } from './transition-scheduler';

describe('stretchAllowed', () => {
  it('speeds a slower incoming song up by at most 10%', () => {
    // 120 BPM out, 110 BPM in: played at 120/110 = 1.09
    expect(stretchAllowed(120 / 110)).toBe(true);
    // 120 out, 105 in: 1.143, too far
    expect(stretchAllowed(120 / 105)).toBe(false);
  });

  it('slows a faster incoming song down by at most 25%', () => {
    // 120 out, 155 in: played at 0.774
    expect(stretchAllowed(120 / 155)).toBe(true);
    // 120 out, 165 in: 0.727, too far
    expect(stretchAllowed(120 / 165)).toBe(false);
  });
});
