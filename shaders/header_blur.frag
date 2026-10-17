#include <flutter/runtime_effect.glsl>

uniform vec2 uSize;
uniform float uSigmaScale;
uniform float uFadeStart;
uniform sampler2D uBackdrop;

out vec4 fragColor;

vec4 sampleBackdrop(vec2 point) {
  vec2 uv = clamp(point / uSize, 0.5 / uSize, 1.0 - 0.5 / uSize);
  #ifdef IMPELLER_TARGET_OPENGLES
    uv.y = 1.0 - uv.y;
  #endif
  return texture(uBackdrop, uv);
}

void main() {
  vec2 point = FlutterFragCoord().xy;
  float sigma = uSigmaScale * uSize.y
      * (1.0 - smoothstep(uFadeStart, 1.0, point.y / uSize.y));
  if (sigma < 0.1) {
    fragColor = sampleBackdrop(point);
    return;
  }
  vec4 color = vec4(0.0);
  for (int i = 0; i < 64; i++) {
    float fraction = (float(i) + 0.5) / 64.0;
    float radius = sigma * sqrt(-2.0 * log(1.0 - fraction * 0.99));
    float angle = float(i) * 2.39996323;
    color += sampleBackdrop(point + radius * vec2(cos(angle), sin(angle)));
  }
  fragColor = color / 64.0;
}
