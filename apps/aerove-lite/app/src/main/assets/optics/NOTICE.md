# AEROVE optics sidecar provenance

Geometry is derived from Flutter **3.47.6**, tag commit
`5fc346839b5d0eef006ed8404392afb4dfae428d`:

- `engine/src/flutter/impeller/geometry/round_superellipse_param.cc`: `ComputeNAndXj`,
  `ComputeOctant`, `ComputeQuadrant`, `RoundSuperellipseBuilder`, `MakeBoundsRadii`.
- `engine/src/flutter/impeller/geometry/rounding_radii.cc`: adjacent-pair global radius scaling.
- `engine/src/flutter/impeller/geometry/round_superellipse_unittests.cc`: independent
  `UniformSquareContains`, `SlimDiagonalContains`, `PointsOutsideOfSharpCorner` fixtures.

Canonical source: https://github.com/flutter/flutter/tree/5fc346839b5d0eef006ed8404392afb4dfae428d/engine/src/flutter/impeller/geometry

Copyright 2013 The Flutter Authors. All rights reserved. Flutter's BSD license is
retained in `FLUTTER_LICENSE`. Aerove modifications retain this repository's license.

`liquid_glass.agsl` and `foreground_ink.agsl` are adaptations of this repository's
`shaders/liquid_glass.frag` and `shaders/foreground_ink.frag`, baseline HEAD
`11c1ff06e448efad939172f2465cc1b2e8f473de`. Preserve their math when synchronizing
either client. Current source SHA-256:

- liquid glass: `b7a6ffd39a0bff64b6b53b5a3eb31b33a0d9fd511b34e260e6834ba5cc20a5ea`
- foreground ink: `4137cdfae8dda64c09a94295046a27dd72e17fa981bb4f9c85197be6c10501bb`

Only transport changes are intended: GLSL vector types to AGSL, fixed frost macro
expansion, pixel-coordinate child shader sampling, explicit asymmetric quadrant
frames, and foreground glyph-alpha masking equivalent to Flutter's srcATop mask.
Backdrop 2.0.1 capture, caching, exports and lifecycle are unchanged.

The clip uses the official two rational conics plus cubic circular-arc path per
octant. Rational conics are subdivided using their positive-weight control hull,
with 0.02 physical pixel control-to-chord tolerance; this supports API33 without
calling API34 Path.conicTo. That tolerance bounds subdivision of the official path,
not the official conic/cubic approximation's error against the analytic curve.
`curveUniforms()` describes that analytic superellipse/circular-arc construction.
As in Flutter, its inset is a first-order near-edge distance. Deep refraction uses
the separate rounded-box approximation already in the full shader. Neither is
advertised as an exact global signed distance function.

`uQuadrants[4]` stores origin and half size per physical corner, TL/TR/BR/BL;
`uCurves[12]` retains Flutter's three-vec4 layout per corner. Uniform nonzero radii
match `liquidGlassCornerCurves`; asymmetric radii use the official weighted splits
instead of the full shader's shared-center simplification. Sharp corners are
identified by n=0; their ignored payload need not equal the Dart zero payload.

The shader returns premultiplied color. Apply the same Shape as the outer clip;
do not add an n=4 distance mask. Haze requires an independently blurred child
shader, aligned to the same padded capture domain. Ink needs an acyclic backdrop
field, its actual coordinate mapping, and a glyph/icon mask; ordinary themes must
retain the source ink. No capture, UI wiring, GPU compilation or visual acceptance
is implied by these assets.
