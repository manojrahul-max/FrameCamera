vec3 effect(vec2 s) {
  float ngs = max(1.0, uRes.y / 700.0 * uScale);
  vec2 nc = floor(gl_FragCoord.xy / ngs);
  float n = hash21(nc + floor(uTime * 24.0) * 17.0);
  vec3 c = S(s);
  vec2 o = 8.0 / uRes;
  vec3 b = (S(s + vec2(o.x, 0.0)) + S(s - vec2(o.x, 0.0)) + S(s + vec2(0.0, o.y)) + S(s - vec2(0.0, o.y))) * 0.25;
  float l = pow(clamp(luma(c) * uP0, 0.0, 2.0), 0.8);
  float lb = luma(b) * uP0;
  l += (n - 0.5) * uP1 * 0.7 * (0.35 + 0.65 * (1.0 - min(l, 1.0)));
  float g = l + max(lb - 0.55, 0.0) * uP2 * 1.4;
  vec3 col = vec3(0.12, 1.0, 0.28) * g + vec3(0.0, 0.03, 0.0);
  col *= 0.94 + 0.06 * sin(gl_FragCoord.y * 1.2 + uTime * 40.0);
  vec2 v = s * 2.0 - 1.0;
  v.x *= uRes.x / uRes.y * 0.7;
  col *= 1.0 - uP3 * smoothstep(0.55, 1.35, length(v));
  return col;
}
