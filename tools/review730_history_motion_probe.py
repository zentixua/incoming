# /// script
# dependencies = ["pyopengl", "numpy"]
# ///
"""Temporal dust on a continuous surface while the camera moves or turns (#730).

Run directly with uv or as part of dust_wall_gl.py.
"""
import sys

import numpy as np

from dust_wall_contract_gl import bind, context, g, link, loc, low, make_scene, quad, read, rgba, texture, target

projection = np.array([[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, -1.02, -0.2], [0, 0, -1, 0]], dtype=np.float32)


def half(T, start, end):
    T = np.asarray(T, dtype=np.float32)
    light = np.zeros((low, low, 4), dtype=np.float32)
    light[:, :, 3] = T
    depth = np.zeros_like(light)
    c = 1 - T
    depth[:, :, 0], depth[:, :, 1], depth[:, :, 2], depth[:, :, 3] = c * start, c * end, c * (start + end) / 2, T
    return np.concatenate((light, depth), axis=1)


def resolve(history_end, prev_depth=20.05, here=20.0, forward=0.05, yaw=0.0):
    p = link("fx_dustwall_resolve")
    target(2 * low, low, [rgba])
    g.glUseProgram(p)
    noise = np.where((np.add.outer(np.arange(low), np.arange(low)) % 2) == 0, 0.7, 0.5)
    bind(p, 0, texture(*rgba, 2 * low, low, half(noise, 0.5, here)))
    bind(p, 1, texture(*rgba, 2 * low, low, half(np.full((low, low), 0.4), 0.5, history_end)))
    g.glTexParameteri(g.GL_TEXTURE_2D, g.GL_TEXTURE_MIN_FILTER, g.GL_LINEAR)
    g.glTexParameteri(g.GL_TEXTURE_2D, g.GL_TEXTURE_MAG_FILTER, g.GL_LINEAR)
    bind(p, 2, make_scene(here))
    bind(p, 3, make_scene(prev_depth))
    rotation = np.eye(4, dtype=np.float32)
    rotation[0, 0] = rotation[2, 2] = np.cos(yaw)
    rotation[0, 2], rotation[2, 0] = np.sin(yaw), -np.sin(yaw)
    g.glUniformMatrix4fv(loc(p, "PrevViewProj"), 1, True, projection @ rotation)
    g.glUniformMatrix4fv(loc(p, "InvViewProj"), 1, True, np.linalg.inv(projection).astype(np.float32))
    g.glUniform3f(loc(p, "PallForward"), 0, 0, -1)
    g.glUniform1f(loc(p, "DwDiv"), 4)
    g.glUniform1f(loc(p, "DwPrevDiv"), 4)
    # this eye minus the previous eye: moved forward (−z) by `forward`
    g.glUniform4f(loc(p, "DwReproject"), 0, 0, -forward, 1)
    g.glUniform2f(loc(p, "DwHistory"), 0.1, 10)
    g.glUniform1f(loc(p, "DwPrevScene"), 1)
    quad(p)
    return read(2 * low, low)[1, 1, 3]


def main():
    context()
    moving = resolve(history_end=20.05)
    control = resolve(history_end=19.9)
    # At the centre of the frame yaw changes previous view-axis depth by cos(yaw).
    yaw = np.deg2rad(25)
    previous = 20 * np.cos(yaw)
    turning = resolve(previous, prev_depth=previous, forward=0, yaw=yaw)
    ceiling = resolve(100, prev_depth=100)
    print(f"Current T=0.7000, moving={moving:.4f}, control={control:.4f}, turning={turning:.4f}, new ceiling={ceiling:.4f}")
    return not np.allclose([moving, control, turning, ceiling], [.4858, .4858, .4858, .7], atol=.001)


if __name__ == "__main__":
    sys.exit(main())
