vec3 heat(float t) {
  if (uP0 < 0.5) return ramp5(t, vec3(0.0, 0.0, 0.06), vec3(0.35, 0.0, 0.5), vec3(0.88, 0.12, 0.18), vec3(1.0, 0.62, 0.0), vec3(1.0, 1.0, 0.85));
  if (uP0 < 1.5) return ramp5(t, vec3(0.1, 0.1, 0.7), vec3(0.0, 0.7, 0.9), vec3(0.2, 0.85, 0.3), vec3(1.0, 0.9, 0.1), vec3(0.95, 0.1, 0.05));
  if (uP0 < 2.5) return vec3(t);
  return ramp5(t, vec3(0.05, 0.02, 0.3), vec3(0.45, 0.0, 0.65), vec3(0.85, 0.2, 0.45), vec3(0.98, 0.55, 0.15), vec3(0.98, 0.95, 0.35));
}
vec3 effect(vec2 s) {
  vec3 c = S(s);
  vec2 o = 10.0 / uRes;
  vec3 b = (S(s + vec2(o.x, 0.0)) + S(s - vec2(o.x, 0.0)) + S(s + vec2(0.0, o.y)) + S(s - vec2(0.0, o.y))) * 0.25;
  float t = luma(c) * 0.75 + max(c.r - c.b, 0.0) * 0.45;
  t = mix(t, max(t, luma(b) * 0.9), uP2);
  float sz = max(1.0, uRes.y / 700.0);
  float drift = (vnoise(s * vec2(uRes.x / uRes.y, 1.0) * 6.0 * uScale + vec2(uTime * 0.3, uTime * 0.2)) - 0.5) * uP1 * 0.3;
  float grain = (hash21(floor(gl_FragCoord.xy / sz) + floor(uTime * 20.0) * 3.0) - 0.5) * uP1 * 0.12;
  t = clamp(t + drift + grain, 0.0, 1.0);
  float steps = 18.0;
  t = mix(t, floor(t * steps + 0.5) / steps, uP3);
  return heat(t);
}
