/*
 * BitChord: the loop mix, a DJ transition on AutoMix's grids and stretch
 * decks. AGPL-3.0-only with the rest of playback/automix (see ../NOTICE.md).
 *
 * The outgoing song hands over from its <audio> element to a stretch deck at
 * the start of an instrumental loop near its end and loops it. The incoming
 * song comes in on its own instrumental loop, bass cut, at the outgoing tempo.
 * One master tempo then ramps from the outgoing song's BPM to the incoming
 * one's, both decks following it beat by beat, so they stay locked however
 * far apart they started. The bass swaps, the outgoing loop drains away
 * through a rising high-pass, the incoming loop lets go into the rest of its
 * song at its own tempo, and its <audio> element takes over two bars later.
 *
 * Everything is placed on one beat clock: beat k of the mix (in outgoing
 * beats) is at beatTimes[k], and each deck's position at beat k is worked
 * out from the loop it is in, so nothing drifts and every change can be
 * posted with an exact input position.
 */
import type { BeatSide, TrackAnalysis, VocalWindow } from '../analysis/types';
import type { LoopChange, LoopDeck } from './loopDeck';

/** Bars per loop, in each song's own bars. */
export const LOOP_BARS = 4;
/** Bars the incoming loop takes to come in at the outgoing tempo. */
const ENTER_BARS = 4;
/** Bars the outgoing loop takes to drain away once the tempo has arrived. */
const EXIT_BARS = 2;
/** Bars after the incoming loop lets go before its <audio> element takes over. */
const SETTLE_BARS = 2;
/** The fastest the master tempo moves: past this a ramp is heard as a lurch, not a mix. */
const MAX_BPM_PER_BAR = 2.5;
const MIN_RAMP_BARS = 2;
const MAX_RAMP_BARS = 24;
/** A frame above this is sung (the phone's VOCAL_ACTIVE_THRESHOLD). */
const VOCAL_ACTIVE = 0.6;
/** Share of sung frames up to which a loop still counts as instrumental (a breath, a stray ad-lib). */
const INSTRUMENTAL_SHARE = 0.1;
/** A loop quieter than this share of the stretch around it is a breakdown or a fade, not a groove. */
const MIN_LOUDNESS = 0.6;
/** How far from the end the outgoing loop is looked for, and from the start the incoming one. */
export const OUT_SEARCH_SEC = 60;
export const IN_SEARCH_SEC = 45;
/** Seconds of each song decoded for the decks: the search span plus room around it. */
export const OUT_REGION_SEC = OUT_SEARCH_SEC + 5;
export const IN_REGION_SEC = IN_SEARCH_SEC + 40;
/** Element <-> deck handovers: identical audio crossed this quickly is heard as one. */
const HANDOVER_SEC = 0.025;
const BASS_CUT_DB = -18;

export interface LoopPick {
  key: string;
  /** Track seconds. */
  loop: [number, number];
  /** Track seconds per beat on this song's grid. */
  beatSec: number;
  bpm: number;
  /** Share of the loop that is sung (0-1); null when the mask doesn't cover it. */
  vocal: number | null;
}

export interface LoopMixPlan {
  out: LoopPick;
  in: LoopPick;
  /** Incoming beats per outgoing beat: 1, or 2 / 0.5 when double or half time is closer. */
  fold: number;
  enterBars: number;
  rampBars: number;
  exitBars: number;
  /** Outgoing bars from the outgoing loop's start to the incoming loop's release. */
  totalBars: number;
}

/** What a plan becomes once it's on the clock. */
export interface LoopMixTimes {
  /** The outgoing element reaches its loop start; the decks take over. */
  t0: number;
  /** Beat k of the mix, in outgoing beats. */
  beatTimes: number[];
  /** Middle of the ramp: the app moves to the incoming song. */
  handoffAt: number;
  /** The incoming loop lets go. */
  releaseAt: number;
  /** The incoming <audio> element takes over, at this track position. */
  elementAt: number;
  elementTrackTime: number;
}

// ---- Choosing the loops ------------------------------------------------------------------

/**
 * The bars to loop on each side, or null when either song has no usable
 * grid there. [buffers] are the decoded songs, for loudness;
 * [earliestOutSec] is the earliest the outgoing loop may start (the playhead
 * plus the time it takes to set up).
 */
export function planLoopMix(
  out: TrackAnalysis,
  outBuffer: AudioBuffer,
  inn: TrackAnalysis,
  inBuffer: AudioBuffer,
  earliestOutSec: number,
): LoopMixPlan | null {
  if (!out.beatIntervalSec || out.firstDownbeatSec === null || !out.bpm) return null;
  if (!inn.beatIntervalSec || inn.firstDownbeatSec === null || !inn.bpm) return null;
  const outBar = out.beatIntervalSec * 4;
  const inBar = inn.beatIntervalSec * 4;

  // outgoing: the latest instrumental loop that ends before the song does
  const outEnd = Math.min(out.durationSec - 0.5, outBuffer.duration - 0.1);
  const outFirst = Math.max(earliestOutSec, out.durationSec - OUT_SEARCH_SEC);
  const outLoops = loopsBetween(out.tailBeats, out.firstDownbeatSec, outBar, outFirst, outEnd).reverse();
  const outPick = pickLoop(outLoops, outBuffer, out.vocals ?? []);
  if (outPick === null) return null;

  // incoming: the earliest instrumental loop in its opening
  const inEnd = Math.min(IN_SEARCH_SEC + LOOP_BARS * inBar, inBuffer.duration - 20);
  const inLoops = loopsBetween(inn.headBeats, inn.firstDownbeatSec, inBar, 0, inEnd);
  const inPick = pickLoop(inLoops, inBuffer, inn.vocals ?? []);
  if (inPick === null) return null;
  // a loop's own length is its tempo: 16 beats exactly, bar line to bar line
  const outBeat = (outPick.end - outPick.start) / (LOOP_BARS * 4);
  const inBeat = (inPick.end - inPick.start) / (LOOP_BARS * 4);
  const outBpm = 60 / outBeat;
  const inBpm = 60 / inBeat;

  const fold = [1, 2, 0.5].reduce((best, f) =>
    Math.abs(Math.log(inBpm / f / outBpm)) < Math.abs(Math.log(inBpm / best / outBpm)) ? f : best,
  );
  const target = inBpm / fold;
  const rampBars = Math.min(MAX_RAMP_BARS, Math.max(MIN_RAMP_BARS, Math.ceil(Math.abs(target - outBpm) / MAX_BPM_PER_BAR)));
  // the incoming loop lets go only at its own loop boundary: round the mix up to whole incoming loops
  const inLoopOutBars = LOOP_BARS / fold;
  const totalBars = Math.ceil((ENTER_BARS + rampBars + EXIT_BARS) / inLoopOutBars) * inLoopOutBars;

  return {
    out: { key: out.fileKey, loop: [outPick.start, outPick.end], beatSec: outBeat, bpm: outBpm, vocal: outPick.vocal },
    in: { key: inn.fileKey, loop: [inPick.start, inPick.end], beatSec: inBeat, bpm: inBpm, vocal: inPick.vocal },
    fold,
    enterBars: ENTER_BARS,
    rampBars,
    exitBars: totalBars - ENTER_BARS - rampBars,
    totalBars,
  };
}

type Span = { start: number; end: number };

/**
 * 4-bar loops lying in [from, to]. With the model's downbeats, each loop runs
 * from a detected beat 1 to the detected beat 1 four bars on, so its seam is
 * the music's own bar line however the tempo wanders. Without them (or where
 * the model found too few), AutoMix's constant grid.
 */
function loopsBetween(side: BeatSide | null | undefined, anchor: number, bar: number, from: number, to: number): Span[] {
  const loops: Span[] = [];
  if (side && side.downbeats.length >= LOOP_BARS + 1) {
    const beat = 60 / side.bpm;
    const downs = side.downbeats;
    for (const start of downs) {
      if (start < from) continue;
      const target = start + LOOP_BARS * 4 * beat;
      const end = downs.reduce((best, d) => (Math.abs(d - target) < Math.abs(best - target) ? d : best), downs[0]!);
      // a missed or doubled downbeat in between shows as an end far from four bars on
      if (Math.abs(end - target) > beat / 4 || end > to) continue;
      loops.push({ start, end });
    }
    if (loops.length) return loops;
  }
  if (to < from) return loops;
  const len = LOOP_BARS * bar;
  for (let t = anchor + Math.ceil((from - anchor) / bar) * bar; t + len <= to; t += bar) loops.push({ start: t, end: t + len });
  return loops;
}

/** The first candidate (in the order given) that is instrumental and loud enough; else the least vocal loud one. */
function pickLoop(loops: Span[], buffer: AudioBuffer, vocals: VocalWindow[]): (Span & { vocal: number | null }) | null {
  if (!loops.length) return null;
  // loudness of the quietest bar: a loop that runs into a fade-out or a breakdown isn't a groove
  const quietestBar = ({ start, end }: Span) =>
    Math.min(...[0, 1, 2, 3].map((b) => rms(buffer, start + (b * (end - start)) / 4, start + ((b + 1) * (end - start)) / 4)));
  const scored = loops.map((l) => ({ ...l, vocal: sungShare(vocals, l.start, l.end), loud: quietestBar(l) }));
  const louds = scored.map((s) => s.loud).sort((a, b) => a - b);
  const typical = louds[Math.floor(louds.length * 0.6)] ?? 0;
  const loudEnough = scored.filter((s) => s.loud >= typical * MIN_LOUDNESS && s.loud > 0.01);
  const pool = loudEnough.length ? loudEnough : scored;
  const instrumental = pool.find((s) => s.vocal !== null && s.vocal <= INSTRUMENTAL_SHARE);
  if (instrumental) return instrumental;
  // no clean bars: the least vocal of the measured ones, else the first loud one
  const measured = pool.filter((s) => s.vocal !== null).sort((a, b) => a.vocal! - b.vocal!);
  return measured[0] ?? pool[0]!;
}

/** Share of [from, to) the vocal curve calls sung, or null when the windows cover under 80% of it. */
export function sungShare(windows: VocalWindow[], from: number, to: number): number | null {
  let sung = 0;
  let n = 0;
  let covered = 0;
  for (const w of windows) {
    const a = Math.max(0, Math.ceil((from - w.startSec) * w.fps));
    const b = Math.min(w.curve.length, Math.floor((to - w.startSec) * w.fps));
    for (let i = a; i < b; i++) {
      if (w.curve[i]! > VOCAL_ACTIVE) sung++;
      n++;
    }
    if (b > a) covered += (b - a) / w.fps;
  }
  return n && covered >= (to - from) * 0.8 ? sung / n : null;
}

function rms(buffer: AudioBuffer, from: number, to: number): number {
  const data = buffer.getChannelData(0);
  const a = Math.max(0, Math.floor(from * buffer.sampleRate));
  const b = Math.min(data.length, Math.floor(to * buffer.sampleRate));
  let ss = 0;
  let n = 0;
  for (let i = a; i < b; i += 16) {
    ss += data[i]! * data[i]!;
    n++;
  }
  return n ? Math.sqrt(ss / n) : 0;
}

// ---- Putting it on the clock -------------------------------------------------------------

/**
 * Schedules the whole mix on the two loop decks, from [t0] (the AudioContext
 * time the outgoing element reaches its loop start). Returns the times the
 * player needs: the handoff, and when and where the incoming element takes over.
 */
export function scheduleLoopMix(plan: LoopMixPlan, outDeck: LoopDeck, inDeck: LoopDeck, t0: number): LoopMixTimes {
  const { out, in: inn, fold } = plan;
  const beats = plan.totalBars * 4;
  const settleBeats = Math.round(SETTLE_BARS * 4 / fold);
  const rampFrom = plan.enterBars * 4;
  const rampTo = (plan.enterBars + plan.rampBars) * 4;
  const target = inn.bpm / fold;

  // master tempo per outgoing beat, and the time of each beat
  const tempo = (k: number) =>
    k < rampFrom ? out.bpm : k >= rampTo ? target : out.bpm + ((target - out.bpm) * (k - rampFrom + 0.5)) / (rampTo - rampFrom);
  const beatTimes = [t0];
  for (let k = 0; k < beats + settleBeats; k++) beatTimes.push(beatTimes[k]! + 60 / tempo(k));

  // positions by whole beats, so a wrap lands exactly on the loop start
  const loopBeats = LOOP_BARS * 4;
  const inPerBeat = fold * inn.beatSec; // incoming track seconds per outgoing beat
  const outChanges: LoopChange[] = [];
  const inChanges: LoopChange[] = [];
  for (let k = 0; k < beats; k++) {
    const t = beatTimes[k]!;
    const T = tempo(k);
    outChanges.push({
      output: t,
      input: out.loop[0] + (k % loopBeats) * out.beatSec,
      rate: T * out.beatSec / 60,
      active: true,
      loop: out.loop,
    });
    inChanges.push({
      output: t,
      input: inn.loop[0] + ((k * fold) % loopBeats) * inn.beatSec,
      rate: (T * inPerBeat) / 60,
      active: true,
      loop: inn.loop,
    });
  }
  const releaseAt = beatTimes[beats]!;
  outChanges.push({ output: releaseAt + 0.05, input: out.loop[0], rate: 1, active: false, loop: null });
  // the incoming loop lets go at its end: the song carries on from there at its own tempo
  inChanges.push({ output: releaseAt, input: inn.loop[1], rate: 1, active: true, loop: null });
  const elementAt = beatTimes[beats + settleBeats]!;
  const elementTrackTime = inn.loop[1] + (elementAt - releaseAt);
  inChanges.push({ output: elementAt + 0.1, input: elementTrackTime + 0.1, rate: 1, active: false, loop: null });
  outDeck.schedule(outChanges);
  inDeck.schedule(inChanges);

  // gains: outgoing takes over from its element, the incoming loop rises over the entry and the ramp
  const og = outDeck.gain.gain;
  const ig = inDeck.gain.gain;
  og.cancelScheduledValues(0);
  ig.cancelScheduledValues(0);
  og.setValueAtTime(0, t0 - HANDOVER_SEC);
  og.linearRampToValueAtTime(1, t0);
  const enterEnd = beatTimes[rampFrom]!;
  const rampEnd = beatTimes[rampTo]!;
  ig.setValueAtTime(0, t0);
  ig.linearRampToValueAtTime(0.55, enterEnd);
  og.setValueAtTime(1, enterEnd);
  og.linearRampToValueAtTime(0.6, rampEnd);
  ig.linearRampToValueAtTime(1, rampEnd);
  og.linearRampToValueAtTime(0, releaseAt);
  ig.setValueAtTime(1, elementAt);
  ig.linearRampToValueAtTime(0, elementAt + HANDOVER_SEC);

  // bass: the incoming loop comes in without it; they swap over one bar mid-ramp
  const mid = beatTimes[Math.round((rampFrom + rampTo) / 2)]!;
  const bar = 4 * (60 / tempo(Math.round((rampFrom + rampTo) / 2)));
  inDeck.eq.setGainAt('low', BASS_CUT_DB, t0);
  outDeck.eq.rampGain('low', 0, BASS_CUT_DB, mid - bar / 2, bar);
  inDeck.eq.rampGain('low', BASS_CUT_DB, 0, mid - bar / 2, bar);
  // the outgoing loop drains away through a rising high-pass once the tempo has arrived
  const hp = outDeck.sweep.highpass.frequency;
  hp.setValueAtTime(20, rampEnd);
  hp.exponentialRampToValueAtTime(1500, releaseAt);

  return { t0, beatTimes, handoffAt: mid, releaseAt, elementAt, elementTrackTime };
}

/** Where a deck is in its song right now, for settling a cancelled mix onto the <audio> element. */
export function deckTrackTime(deck: LoopDeck): number {
  return deck.inputTime;
}
