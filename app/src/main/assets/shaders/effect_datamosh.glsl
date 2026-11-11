vec3 effect(vec2 s) {
  vec2 q = Q(s);
  float bleed = uP3 * 0.004;
  vec3 m;
  m.r = texture2D(uMosh, q + vec2(bleed, 0.0)).r;
  m.g = texture2D(uMosh, q).g;
  m.b = texture2D(uMosh, q - vec2(bleed, 0.0)).b;
  return floor(m * 28.0 + 0.5) / 28.0;
}
