float blobMask(vec2 q) {
  vec2 t = uHistTexel * 1.6;
  float m = texture2D(uHist, q).a * 2.0;
  m += texture2D(uHist, q + vec2(t.x, 0.0)).a;
  m += texture2D(uHist, q - vec2(t.x, 0.0)).a;
  m += texture2D(uHist, q + vec2(0.0, t.y)).a;
  m += texture2D(uHist, q - vec2(0.0, t.y)).a;
  m += texture2D(uHist, q + t).a * 0.5;
  m += texture2D(uHist, q - t).a * 0.5;
  return m / 7.0;
}
float blobField(vec2 s) { return smoothstep(0.22, 0.42, blobMask(Q(s))); }
vec3 effect(vec2 s) {
  vec3 base = S(s);
  float r = max(1.0, uRes.y / 540.0 * uScale * 1.5);
  vec2 o = r / uRes;
  float f = blobField(s);
  float e = 0.0;
  e = max(e, abs(f - blobField(s + vec2(o.x, 0.0))));
  e = max(e, abs(f - blobField(s - vec2(o.x, 0.0))));
  e = max(e, abs(f - blobField(s + vec2(0.0, o.y))));
  e = max(e, abs(f - blobField(s - vec2(0.0, o.y))));
  vec3 col = hsv2rgb(vec3(uP3, 0.85, 1.0));
  vec3 outc = base;
  if (uP2 > 1.5) outc = vec3(f) * 0.22;
  else if (uP2 > 0.5) outc = mix(base * 0.8, col, f * 0.35);
  float halo = smoothstep(0.08, 0.4, blobMask(Q(s))) * 0.12;
  outc += col * halo;
  return mix(outc, col, clamp(e * 1.6, 0.0, 1.0));
}
