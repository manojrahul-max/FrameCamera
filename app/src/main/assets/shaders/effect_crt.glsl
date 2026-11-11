vec3 effect(vec2 s) {
  vec2 c = s * 2.0 - 1.0;
  float k = uP0 * 0.22;
  vec2 cc = c * (1.0 + k * vec2(c.y * c.y, c.x * c.x));
  vec2 uv = cc * 0.5 + 0.5;
  float inside = step(0.0, uv.x) * step(uv.x, 1.0) * step(0.0, uv.y) * step(uv.y, 1.0);
  float off = uP1 * 0.004 * (0.6 + 0.8 * dot(c, c));
  vec3 col;
  col.r = S(uv + vec2(off, 0.0)).r;
  col.g = S(uv).g;
  col.b = S(uv - vec2(off, 0.0)).b;
  vec3 g = (S(uv + vec2(0.008, 0.0)) + S(uv - vec2(0.008, 0.0)) + S(uv + vec2(0.0, 0.008)) + S(uv - vec2(0.0, 0.008))) * 0.25;
  col += max(g - 0.45, 0.0) * uP2 * 1.6;
  float period = max(2.0, uRes.y / 270.0 * uScale);
  float sl = 0.5 + 0.5 * cos(gl_FragCoord.y / period * 6.2831853);
  col *= 0.5 + 0.5 * sl;
  float tri = mod(floor(gl_FragCoord.x / max(1.0, period / 3.0)), 3.0);
  vec3 mask = vec3(0.82) + 0.3 * vec3(1.0 - step(0.5, tri), step(0.5, tri) * (1.0 - step(1.5, tri)), step(1.5, tri));
  col *= mask;
  float fl = 1.0 - 0.07 * uP3 * (0.5 + 0.5 * sin(uTime * 50.0)) - 0.05 * uP3 * hash11(floor(uTime * 30.0));
  col *= 1.0 - 0.08 * uP3 * smoothstep(0.9, 1.0, fract(s.y - uTime * 0.12));
  col *= 1.0 - 0.3 * dot(c, c) * 0.5;
  return col * inside * fl * 1.15;
}
