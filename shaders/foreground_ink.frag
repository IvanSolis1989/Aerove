#version 460 core
#include <flutter/runtime_effect.glsl>
precision highp float;
uniform vec2 uSize;
uniform vec2 uFieldSize;
uniform vec2 uOrigin;
uniform vec2 uAxisX;
uniform vec2 uAxisY;
uniform float uStrength;
uniform float uDark;
uniform float uContrast;
uniform float uAlpha;
uniform vec3 uSource;
uniform sampler2D uMask;
uniform sampler2D uField;
out vec4 fragColor;

float luminance(vec3 c) {
  vec3 low = c / 12.92;
  vec3 high = pow((c + 0.055) / 1.055, vec3(2.4));
  return dot(mix(low, high, step(vec3(0.04045), c)),
             vec3(0.2126, 0.7152, 0.0722));
}
float contrastOn(vec3 ink, float backgroundLuminance) {
  float x = luminance(ink);
  return (max(x, backgroundLuminance) + 0.05) /
         (min(x, backgroundLuminance) + 0.05);
}
vec3 vivid(vec3 c) {
  float hi = max(c.r, max(c.g, c.b));
  float lo = min(c.r, min(c.g, c.b));
  float span = hi - lo;
  if (span < 0.015) return c;
  vec3 hue = (c - lo) / span;
  float saturation = min(mix(0.97, 0.92, uDark),
      span / max(hi, 0.001) * mix(3.1, 2.70, uDark) + mix(0.22, 0.175, uDark));
  float peak = mix(0.97, 0.95, uDark);
  return mix(vec3(peak), hue * peak, saturation);
}
vec3 readable(vec3 ink, vec3 background, float backgroundLuminance, float alpha) {
  float blackContrast = (backgroundLuminance + 0.05) / 0.05;
  float whiteContrast = 1.05 / (backgroundLuminance + 0.05);
  vec3 pole = blackContrast > whiteContrast ? vec3(0) : vec3(1);
  float low = 0.0, high = 1.0;
  if (contrastOn(mix(background, ink, alpha), backgroundLuminance) >= uContrast) return ink;
  for (int i = 0; i < 8; i++) {
    float mid = (low + high) * 0.5;
    vec3 testInk = mix(ink, pole, mid);
    if (contrastOn(mix(background, testInk, alpha), backgroundLuminance) >= uContrast) high = mid;
    else low = mid;
  }
  return mix(ink, pole, high);
}
void main() {
  vec2 uv = FlutterFragCoord().xy / uSize;
  vec4 mask = texture(uMask, uv);
  if (mask.a < 0.001) { fragColor = mask; return; }
  vec2 position = uOrigin + uAxisX * uv.x + uAxisY * uv.y;
  vec3 background = texture(uField, clamp(position / uFieldSize, 0.0, 1.0)).rgb;
  vec3 source = mask.rgb / mask.a;
  if (length(source - uSource) > 0.15) { fragColor = mask; return; }
  float backgroundLuminance = luminance(background);
  vec3 colored = readable(vivid(background), background, backgroundLuminance, 1.0);
  vec3 ink = readable(mix(source, colored, uStrength), background, backgroundLuminance, uAlpha);
  fragColor = vec4(ink * mask.a, mask.a);
}
