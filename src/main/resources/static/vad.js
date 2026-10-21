// Voice activity detection on the microphone level, for hands-free mode. Pure logic: the caller feeds it a
// level in [0, 1] every few milliseconds and gets 'start' or 'end' back.
// Speech starts when the level stays above the room's noise floor for a moment, and ends after a pause.
// The floor adapts to the room; a higher sensitivity factor (used while Jarvis speaks) keeps the echo of
// the speakers from interrupting him.

export class VoiceDetector {
  constructor({ startMs = 150, endMs = 900, ratio = 2.5, minLevel = 0.06, maxMs = 30000 } = {}) {
    Object.assign(this, { startMs, endMs, ratio, minLevel, maxMs });
    this.floor = 0.02;
    this.speaking = false;
    this.aboveSince = null;
    this.lastLoud = 0;
    this.startedAt = 0;
  }

  /**
   * @param {number} level current microphone level, 0..1
   * @param {number} now milliseconds
   * @param {number} sensitivity 1 normally, higher to require louder speech (Jarvis is talking)
   * @returns {'start'|'end'|null}
   */
  update(level, now, sensitivity = 1) {
    const threshold = Math.max(this.minLevel, this.floor * this.ratio) * sensitivity;
    if (!this.speaking) {
      if (level <= threshold) {
        this.floor = this.floor * 0.97 + level * 0.03; // learn the room from quiet moments only
        this.aboveSince = null;
        return null;
      }
      this.aboveSince ??= now;
      if (now - this.aboveSince < this.startMs) return null;
      this.speaking = true;
      this.startedAt = now;
      this.lastLoud = now;
      return 'start';
    }
    if (level > threshold * 0.6) this.lastLoud = now; // softer syllables still count as speech
    if (now - this.lastLoud >= this.endMs || now - this.startedAt >= this.maxMs) {
      this.speaking = false;
      this.aboveSince = null;
      return 'end';
    }
    return null;
  }

  reset() {
    this.speaking = false;
    this.aboveSince = null;
  }
}
