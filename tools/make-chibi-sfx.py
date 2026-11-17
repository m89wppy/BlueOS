#!/usr/bin/env python3
"""
BlueOS 0.9: the chibis' own little sounds, synthesized here (no recordings, nothing from the game).

  python3 tools/make-chibi-sfx.py [out_dir]     (default media/chibi/sfx; needs numpy + ffmpeg)

  BlueOS_Chibi_Pick.ogg — a soft rising "pop" when a finger picks her up
  BlueOS_Chibi_Hop.ogg  — a small springy "boing" when she jumps to another widget
  BlueOS_Chibi_Land.ogg — a soft low "tup" when she lands
"""
import os, subprocess, sys, tempfile, wave
import numpy as np

SR = 44100


def env(n, attack, decay):
    t = np.arange(n) / SR
    a = np.minimum(1, t / attack)
    return a * np.exp(-t / decay)


def sweep(f0, f1, dur, curve=1.0):
    n = int(SR * dur)
    k = (np.arange(n) / n) ** curve
    f = f0 + (f1 - f0) * k
    return np.sin(2 * np.pi * np.cumsum(f) / SR), n


def pick():
    s, n = sweep(480, 1180, .11, .7)
    h, _ = sweep(960, 2360, .11, .7)
    return (s + .18 * h) * env(n, .004, .035)


def hop():
    n = int(SR * .2)
    t = np.arange(n) / SR
    f = 330 + 360 * (1 - np.exp(-t / .04)) + 40 * np.sin(2 * np.pi * 22 * t) * np.exp(-t / .08)
    s = np.sin(2 * np.pi * np.cumsum(f) / SR)
    return s * env(n, .006, .06)


def land():
    s, n = sweep(150, 62, .14, .5)
    rng = np.random.default_rng(7)
    noise = rng.standard_normal(n)
    noise = np.convolve(noise, np.ones(24) / 24, 'same')         # dull it: a soft cloth "tup", no click
    return (s * env(n, .002, .05) + .35 * noise * env(n, .001, .012))


def write(name, x, out):
    x = x / np.max(np.abs(x)) * 10 ** (-6 / 20)                   # peak -6 dBFS
    fade = int(SR * .004)
    x[-fade:] *= np.linspace(1, 0, fade)
    pcm = (x * 32767).astype('<i2')
    with tempfile.TemporaryDirectory() as d:
        w = os.path.join(d, 'x.wav')
        with wave.open(w, 'wb') as f:
            f.setnchannels(1); f.setsampwidth(2); f.setframerate(SR); f.writeframes(pcm.tobytes())
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', w, '-c:a', 'libvorbis', '-q:a', '5',
                        '-map_metadata', '-1', os.path.join(out, name)], check=True)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, '..', 'media', 'chibi', 'sfx')
    os.makedirs(out, exist_ok=True)
    for name, fn in (('BlueOS_Chibi_Pick.ogg', pick), ('BlueOS_Chibi_Hop.ogg', hop), ('BlueOS_Chibi_Land.ogg', land)):
        write(name, fn(), out)
        print(os.path.join(out, name))


if __name__ == '__main__':
    main()
