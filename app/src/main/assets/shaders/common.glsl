#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
#ifdef EXTERNAL_TEX
uniform samplerExternalOES uTex;
#else
uniform sampler2D uTex;
#endif
uniform mat4 uTexMat;     // stream texture matrix (quad space -> stream texture space)
uniform mat4 uInvT;       // maps output coordinates (0..1) to history/quad coordinates (0..1)
uniform sampler2D uHist;  // current frame (rgb) + analysis channel (a), quad space
uniform sampler2D uPrevH; // previous history frame
uniform sampler2D uMosh;  // datamosh feedback buffer
uniform vec2 uRes;        // output size in pixels
uniform vec2 uHistTexel;  // 1 / history texture size
uniform float uTime;
uniform float uFirst;
uniform float uIntensity, uScale, uContrast, uBrightness;
uniform float uP0, uP1, uP2, uP3;

vec2 Q(vec2 s) { return (uInvT * vec4(s, 0.0, 1.0)).xy; }
vec3 srcQ(vec2 q) { return texture2D(uTex, (uTexMat * vec4(q, 0.0, 1.0)).xy).rgb; }
vec3 S(vec2 s) { return srcQ(Q(s)); }
vec4 H(vec2 s) { return texture2D(uHist, Q(s)); }
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
float hash11(float p) { p = fract(p * 0.1031); p *= p + 33.33; p *= p + p; return fract(p); }
float hash21(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}
float vnoise(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash21(i), hash21(i + vec2(1.0, 0.0)), f.x),
             mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), f.x), f.y);
}
vec3 grade(vec3 c) { return (c - 0.5) * uContrast + 0.5 + uBrightness; }
vec3 hsv2rgb(vec3 c) {
  vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
  return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}
vec3 ramp5(float t, vec3 a, vec3 b, vec3 c, vec3 d, vec3 e) {
  t = clamp(t, 0.0, 1.0) * 4.0;
  if (t < 1.0) return mix(a, b, t);
  if (t < 2.0) return mix(b, c, t - 1.0);
  if (t < 3.0) return mix(c, d, t - 2.0);
  return mix(d, e, t - 3.0);
}
float b2(vec2 p) { return mod(p.x * 2.0 + p.y * 3.0, 4.0); }
float b4r(vec2 p) { return 4.0 * b2(mod(p, 2.0)) + b2(mod(floor(p / 2.0), 2.0)); }
float b8r(vec2 p) { return 4.0 * b4r(mod(p, 4.0)) + b2(mod(floor(p / 4.0), 2.0)); }
