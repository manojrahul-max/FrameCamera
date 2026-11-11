vec3 effect(vec2 s) {
  float cell = max(1.0, floor(uScale * uRes.y / 600.0 + 0.5));
  vec2 cid = floor(gl_FragCoord.xy / cell);
  vec2 sc = (cid + 0.5) * cell / uRes;
  float l = luma(S(sc)) + uP1;
  float th;
  if (uP0 < 0.5) th = (b4r(cid) + 0.5) / 16.0;
  else if (uP0 < 1.5) th = (b8r(cid) + 0.5) / 64.0;
  else if (uP0 < 2.5) {
    vec2 r = vec2(cid.x + cid.y, cid.x - cid.y) * 0.7071 / 4.0;
    th = 0.5 + 0.25 * (cos(6.2831853 * r.x) + cos(6.2831853 * r.y));
  }
  else if (uP0 < 3.5) th = (mod(cid.y, 4.0) + 0.5) / 4.0 * 0.9 + 0.05 + (hash21(vec2(cid.x * 0.0, cid.y)) - 0.5) * 0.0;
  else th = hash21(cid + floor(uTime * 8.0));
  float on = step(th, l);
  if (uP3 > 0.5) on = 1.0 - on;
  vec3 dk = vec3(0.0), lt = vec3(1.0);
  if (uP2 > 0.5 && uP2 < 1.5) { dk = vec3(0.0, 0.05, 0.0); lt = vec3(0.25, 1.0, 0.35); }
  else if (uP2 > 1.5 && uP2 < 2.5) { dk = vec3(0.06, 0.03, 0.0); lt = vec3(1.0, 0.7, 0.15); }
  else if (uP2 > 2.5 && uP2 < 3.5) { dk = vec3(0.06, 0.22, 0.06); lt = vec3(0.61, 0.74, 0.06); }
  else if (uP2 > 3.5) { dk = vec3(0.10, 0.10, 0.14); lt = vec3(0.93, 0.90, 0.82); }
  return mix(dk, lt, on);
}
