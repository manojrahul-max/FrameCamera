vec3 effect(vec2 s) {
  float asp = uRes.x / uRes.y;
  vec2 p = vec2(s.x * asp, s.y);
  float t = uTime * uP1;
  float freq = 6.0 / uScale;
  vec2 d;
  d.x = sin(p.y * freq + t * 1.7) + 0.5 * sin(p.x * freq * 1.7 - t * 1.3 + p.y * 2.0);
  d.y = sin(p.x * freq + t * 1.4) + 0.5 * sin(p.y * freq * 1.9 + t * 1.1 - p.x * 2.0);
  d += (vec2(vnoise(p * freq * 0.8 + t), vnoise(p * freq * 0.8 - t + 9.0)) - 0.5) * 2.0;
  vec2 rc = vec2(0.5 * asp, 0.5) + vec2(sin(t * 0.3), cos(t * 0.23)) * 0.25;
  vec2 rv = p - rc;
  float r = length(rv);
  float rip = sin(r * freq * 3.0 - t * 4.0) * exp(-r * 2.5);
  d += rv / (r + 0.0001) * rip * 3.0 * uP2;
  vec2 off = d * 0.028 * uP0 * vec2(1.0 / asp, 1.0);
  vec2 uv = s + off;
  float ca = uP3 * 0.004;
  vec3 col;
  col.r = S(uv + vec2(ca, 0.0)).r;
  col.g = S(uv).g;
  col.b = S(uv - vec2(ca, 0.0)).b;
  float sheen = pow(max(0.0, dot(normalize(d + 0.0001), normalize(vec2(0.6, 0.8)))), 12.0);
  col += sheen * 0.07 * uP0;
  return col;
}
