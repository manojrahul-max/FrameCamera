float nearness(vec2 s) {
  vec2 q = Q(s);
  vec2 t = uHistTexel * uScale * 2.0;
  float g = 0.0;
  for (int i = -1; i <= 1; i++) {
    for (int j = -1; j <= 1; j++) {
      g += texture2D(uHist, q + vec2(float(i), float(j)) * t).a;
    }
  }
  g /= 9.0;
  float sharp = clamp(g * 10.0, 0.0, 1.0);
  float prior = 1.0 - s.y;
  return clamp(mix(sharp, prior, uP3) + uP0 * 0.5, 0.0, 1.0);
}
vec3 effect(vec2 s) {
  float n = nearness(s);
  vec3 src = S(s);
  vec3 turbo = ramp5(n, vec3(0.12, 0.10, 0.45), vec3(0.10, 0.55, 0.85), vec3(0.20, 0.80, 0.45), vec3(0.98, 0.82, 0.20), vec3(0.90, 0.20, 0.12));
  if (uP1 < 0.5) return mix(src, turbo, 0.88);
  if (uP1 < 1.5) return vec3(n);
  float m = 1.0 - smoothstep(0.0, 0.28, abs(n - uP2));
  float gray = luma(src);
  return mix(vec3(gray) * 0.18, src, m);
}
