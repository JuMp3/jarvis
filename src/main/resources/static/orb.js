// Jarvis orb: a lamp whose lens rings react to state and to the audio level.
// States: idle (slow breathing), listening (follows the microphone), thinking (rings turn),
// speaking (pulses with Jarvis's voice).

const PALETTE = {
  lamp: [240, 180, 76],
  mist: [159, 184, 200],
};

const RINGS = [
  { radius: 1.18, width: 1.6, arcs: 3, gap: 0.55, speed: 0.12 },
  { radius: 1.42, width: 1.1, arcs: 5, gap: 0.35, speed: -0.08 },
  { radius: 1.7, width: 0.8, arcs: 7, gap: 0.25, speed: 0.05 },
  { radius: 2.0, width: 0.6, arcs: 2, gap: 1.2, speed: -0.03 },
];

const STATE_TUNING = {
  idle: { spin: 0.4, glow: 0.55, color: 'lamp', levelGain: 0 },
  listening: { spin: 0.6, glow: 0.75, color: 'mist', levelGain: 1.4 },
  thinking: { spin: 3.2, glow: 0.8, color: 'lamp', levelGain: 0 },
  speaking: { spin: 1.0, glow: 1.0, color: 'lamp', levelGain: 1.2 },
};

export class Orb {
  /**
   * @param {HTMLCanvasElement} canvas
   * @param {() => number} levelSource returns the current audio level in [0, 1]
   */
  constructor(canvas, levelSource) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.levelSource = levelSource;
    this.state = 'idle';
    this.level = 0;
    this.spin = STATE_TUNING.idle.spin;
    this.glow = STATE_TUNING.idle.glow;
    this.color = [...PALETTE.lamp];
    this.angle = 0;
    this.last = performance.now();
    this.reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');

    new ResizeObserver(() => this.resize()).observe(canvas);
    this.resize();
    requestAnimationFrame((t) => this.frame(t));
  }

  setState(state) {
    if (STATE_TUNING[state]) {
      this.state = state;
    }
  }

  resize() {
    const ratio = window.devicePixelRatio || 1;
    const { width, height } = this.canvas.getBoundingClientRect();
    this.canvas.width = Math.max(1, Math.round(width * ratio));
    this.canvas.height = Math.max(1, Math.round(height * ratio));
    this.ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    this.size = { width, height };
  }

  frame(now) {
    const dt = Math.min(0.05, (now - this.last) / 1000);
    this.last = now;
    this.update(dt, now / 1000);
    this.draw(now / 1000);
    requestAnimationFrame((t) => this.frame(t));
  }

  update(dt, time) {
    const tuning = STATE_TUNING[this.state];
    const ease = (from, to, rate) => from + (to - from) * Math.min(1, dt * rate);
    const motion = this.reducedMotion.matches ? 0.25 : 1;

    const rawLevel = tuning.levelGain > 0 ? Math.min(1, this.levelSource() * tuning.levelGain) : 0;
    this.level = ease(this.level, rawLevel, rawLevel > this.level ? 18 : 6);
    this.spin = ease(this.spin, tuning.spin * motion, 3);
    this.glow = ease(this.glow, tuning.glow, 4);
    const target = PALETTE[tuning.color];
    this.color = this.color.map((c, i) => ease(c, target[i], 4));
    this.angle += this.spin * dt;

    // Idle breathing so the lamp never looks switched off.
    this.breath = this.state === 'idle' ? (Math.sin(time * 1.3) + 1) * 0.06 * motion : 0;
  }

  draw(time) {
    const { ctx } = this;
    const { width, height } = this.size;
    const cx = width / 2;
    const cy = height / 2;
    const base = Math.min(width, height) * 0.2;
    const pulse = 1 + this.level * 0.35 + this.breath;
    const [r, g, b] = this.color.map(Math.round);
    const rgba = (alpha) => `rgba(${r}, ${g}, ${b}, ${alpha})`;

    ctx.clearRect(0, 0, width, height);

    // Halo
    const halo = ctx.createRadialGradient(cx, cy, base * 0.2, cx, cy, base * 2.6 * pulse);
    halo.addColorStop(0, rgba(0.32 * this.glow));
    halo.addColorStop(0.45, rgba(0.08 * this.glow));
    halo.addColorStop(1, rgba(0));
    ctx.fillStyle = halo;
    ctx.fillRect(0, 0, width, height);

    // Lamp core
    const core = ctx.createRadialGradient(cx - base * 0.15, cy - base * 0.2, base * 0.05, cx, cy, base * pulse);
    core.addColorStop(0, `rgba(255, 232, 186, ${0.95 * this.glow})`);
    core.addColorStop(0.35, rgba(0.9 * this.glow));
    core.addColorStop(1, rgba(0.05));
    ctx.fillStyle = core;
    ctx.beginPath();
    ctx.arc(cx, cy, base * pulse, 0, Math.PI * 2);
    ctx.fill();

    // Lens rings: broken arcs that turn at different speeds
    ctx.lineCap = 'round';
    RINGS.forEach((ring, index) => {
      const wobble = this.level * 0.12 * Math.sin(time * 6 + index * 1.7);
      const radius = base * (ring.radius + this.level * 0.25 * (index + 1) * 0.5 + wobble);
      const step = (Math.PI * 2) / ring.arcs;
      const rotation = this.angle * ring.speed * 10 + index;
      ctx.strokeStyle = rgba((0.75 - index * 0.14) * this.glow);
      ctx.lineWidth = ring.width * (1 + this.level);
      for (let a = 0; a < ring.arcs; a++) {
        const start = rotation + a * step;
        ctx.beginPath();
        ctx.arc(cx, cy, radius, start, start + step - ring.gap);
        ctx.stroke();
      }
    });
  }
}
