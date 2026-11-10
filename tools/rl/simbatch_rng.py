"""Python's random.Random (MT19937, CPython 3.12) in numba, one generator per arena: the same seed gives the same
draws as random.Random(seed) does - random(), randint, randrange, uniform, choice, choices with weights, gauss - so the
batch simulator (tools/rl/simbatch.py) can follow tools/rl/sim_env.py draw for draw and be checked against it exactly.

The state of arena e: mt[e, 0:624] (uint32), mt[e, 624] the index, gnext[e] gauss's spare (NaN: none).
"""
from __future__ import annotations

import math

import numba as nb
import numpy as np
from numba.extending import register_jitable

N_MT = 624
M_MT = 397
MATRIX_A = np.uint32(0x9908B0DF)
UPPER = np.uint32(0x80000000)
LOWER = np.uint32(0x7FFFFFFF)
TWOPI = 2.0 * math.pi


@register_jitable
def init_genrand(mt, e, s):
    mt[e, 0] = np.uint32(s)
    for i in range(1, N_MT):
        prev = np.uint64(mt[e, i - 1])
        v = (np.uint64(1812433253) * (prev ^ (prev >> np.uint64(30))) + np.uint64(i)) & np.uint64(0xFFFFFFFF)
        mt[e, i] = np.uint32(v)
    mt[e, N_MT] = np.uint32(N_MT)


@register_jitable
def seed_words(mt, gnext, e, key):
    """init_by_array(key): CPython's random_seed for an int (its 32-bit words, least significant first)"""
    init_genrand(mt, e, 19650218)
    i, j = 1, 0
    n = key.shape[0]
    k = N_MT if N_MT > n else n
    while k > 0:
        prev = np.uint64(mt[e, i - 1])
        v = (np.uint64(mt[e, i]) ^ ((prev ^ (prev >> np.uint64(30))) * np.uint64(1664525))) + np.uint64(key[j]) + np.uint64(j)
        mt[e, i] = np.uint32(v & np.uint64(0xFFFFFFFF))
        i += 1
        j += 1
        if i >= N_MT:
            mt[e, 0] = mt[e, N_MT - 1]
            i = 1
        if j >= n:
            j = 0
        k -= 1
    k = N_MT - 1
    while k > 0:
        prev = np.uint64(mt[e, i - 1])
        v = (np.uint64(mt[e, i]) ^ ((prev ^ (prev >> np.uint64(30))) * np.uint64(1566083941))) - np.uint64(i)
        mt[e, i] = np.uint32(v & np.uint64(0xFFFFFFFF))
        i += 1
        if i >= N_MT:
            mt[e, 0] = mt[e, N_MT - 1]
            i = 1
        k -= 1
    mt[e, 0] = np.uint32(0x80000000)
    mt[e, N_MT] = np.uint32(N_MT)
    gnext[e] = np.nan


def key_of(seed: int) -> np.ndarray:
    """the 32-bit words of abs(seed), as CPython splits an int for init_by_array (0: one word 0)"""
    s = abs(int(seed))
    words = []
    while s:
        words.append(s & 0xFFFFFFFF)
        s >>= 32
    return np.asarray(words or [0], np.uint32)


@register_jitable
def genrand_u32(mt, e):
    idx = mt[e, N_MT]
    if idx >= N_MT:
        for kk in range(N_MT - M_MT):
            y = (mt[e, kk] & UPPER) | (mt[e, kk + 1] & LOWER)
            mt[e, kk] = mt[e, kk + M_MT] ^ (y >> np.uint32(1)) ^ (MATRIX_A if (y & np.uint32(1)) else np.uint32(0))
        for kk in range(N_MT - M_MT, N_MT - 1):
            y = (mt[e, kk] & UPPER) | (mt[e, kk + 1] & LOWER)
            mt[e, kk] = mt[e, kk + (M_MT - N_MT)] ^ (y >> np.uint32(1)) ^ (MATRIX_A if (y & np.uint32(1)) else np.uint32(0))
        y = (mt[e, N_MT - 1] & UPPER) | (mt[e, 0] & LOWER)
        mt[e, N_MT - 1] = mt[e, M_MT - 1] ^ (y >> np.uint32(1)) ^ (MATRIX_A if (y & np.uint32(1)) else np.uint32(0))
        idx = np.uint32(0)
    y = mt[e, idx]
    mt[e, N_MT] = idx + np.uint32(1)
    y ^= (y >> np.uint32(11))
    y ^= (y << np.uint32(7)) & np.uint32(0x9D2C5680)
    y ^= (y << np.uint32(15)) & np.uint32(0xEFC60000)
    y ^= (y >> np.uint32(18))
    return y


@register_jitable
def rnd(mt, e):
    """random.random()"""
    a = genrand_u32(mt, e) >> np.uint32(5)
    b = genrand_u32(mt, e) >> np.uint32(6)
    return (float(a) * 67108864.0 + float(b)) * (1.0 / 9007199254740992.0)


@register_jitable
def below(mt, e, n):
    """random._randbelow(n) by getrandbits (n >= 1, n < 2**32)"""
    k = 0
    m = n
    while m:
        k += 1
        m >>= 1
    r = genrand_u32(mt, e) >> np.uint32(32 - k)
    while r >= n:
        r = genrand_u32(mt, e) >> np.uint32(32 - k)
    return int(r)


@register_jitable
def randint(mt, e, a, b):
    return a + below(mt, e, b - a + 1)


@register_jitable
def uniform(mt, e, a, b):
    return a + (b - a) * rnd(mt, e)


@register_jitable
def gauss(mt, gnext, e, mu, sigma):
    z = gnext[e]
    gnext[e] = np.nan
    if np.isnan(z):
        x2pi = rnd(mt, e) * TWOPI
        g2rad = math.sqrt(-2.0 * math.log(1.0 - rnd(mt, e)))
        z = math.cos(x2pi) * g2rad
        gnext[e] = math.sin(x2pi) * g2rad
    return mu + z * sigma


@register_jitable
def choices1(mt, e, cum):
    """random.choices(population, weights)[0]'s index: cum the weights' running sums (itertools.accumulate)"""
    n = cum.shape[0]
    total = cum[n - 1] + 0.0
    x = rnd(mt, e) * total
    lo, hi = 0, n - 1   # bisect_right(cum, x, 0, n - 1)
    while lo < hi:
        mid = (lo + hi) // 2
        if x < cum[mid]:
            hi = mid
        else:
            lo = mid + 1
    return lo


def new_state(n: int):
    mt = np.zeros((n, N_MT + 1), np.uint32)
    gnext = np.full(n, np.nan)
    return mt, gnext


# for Python (the checks): the same functions compiled on their own (inside the kernels they are compiled into them)
rnd_py = nb.njit(rnd)
below_py = nb.njit(below)
randint_py = nb.njit(randint)
uniform_py = nb.njit(uniform)
gauss_py = nb.njit(gauss)
choices1_py = nb.njit(choices1)
_seed_words_py = nb.njit(seed_words)


def seed(mt, gnext, e: int, s: int):
    _seed_words_py(mt, gnext, e, key_of(s))
