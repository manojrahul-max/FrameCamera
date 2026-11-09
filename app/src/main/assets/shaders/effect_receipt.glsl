vec3 effect(vec2 s) {
  vec2 fc = gl_FragCoord.xy;
  float cell = max(2.0, uRes.y / 450.0 * uScale);
  vec2 cid = floor(fc / cell);
  vec2 sc = (cid + 0.5) * cell / uRes;
  float l = luma(S(sc));
  l = clamp((l - 0.5) * 1.25 + 0.5, 0.0, 1.0);
  float th = (b4r(cid) + 0.5) / 16.0;
  float streak = (hash11(cid.x * 1.7) - 0.5) * uP2 * 0.5;
  float dens = clamp(1.0 - l + streak, 0.0, 1.0);
  float ink = step(th, dens);
  float fade = uP0;
  float inkAmt = ink * (1.0 - 0.55 * fade) * (0.85 + 0.15 * hash21(cid * 3.1));
  vec3 paper = vec3(0.96, 0.95, 0.92);
  if (uP1 > 0.5) paper = vec3(0.95, 0.91, 0.80);
  if (uP1 > 1.5) paper = vec3(0.86, 0.89, 0.90);
  float fibre = vnoise(fc / max(1.5, cell * 0.8)) * 0.04 + (hash21(fc) - 0.5) * 0.05;
  float crease = vnoise(vec2(s.x * 2.0, s.y * 55.0));
  paper += fibre - 0.05 * crease;
  vec3 inkc = mix(vec3(0.05, 0.05, 0.07), vec3(0.38, 0.35, 0.36), fade);
  vec3 col = mix(paper, inkc, inkAmt);
  col *= 0.9 + 0.1 * smoothstep(0.0, 0.1, s.x) * smoothstep(0.0, 0.1, 1.0 - s.x);
  return col;
}
