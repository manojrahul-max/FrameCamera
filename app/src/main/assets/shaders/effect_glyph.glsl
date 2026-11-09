uniform sampler2D uGlyph;
vec3 effect(vec2 s) {
  float ch = max(5.0, uRes.y / (48.0 * uScale));
  vec2 cellSz = vec2(ch * 0.55, ch);
  vec2 fc = gl_FragCoord.xy;
  vec2 cid = floor(fc / cellSz);
  vec2 loc = fract(fc / cellSz);
  vec2 sc = (cid + 0.5) * cellSz / uRes;
  vec2 dx = vec2(cellSz.x * 0.3, 0.0) / uRes;
  vec2 dy = vec2(0.0, cellSz.y * 0.3) / uRes;
  vec3 c = (S(sc) + S(sc + dx) + S(sc - dx) + S(sc + dy) + S(sc - dy)) * 0.2;
  float l = pow(clamp((luma(c) - 0.5) * 1.2 + 0.5, 0.0, 1.0), 0.7) * 0.999;
  float idx = floor(l * 10.0);
  float row = floor(uP0 + 0.5);
  vec2 auv = vec2((idx + loc.x) / 10.0, (row + (1.0 - loc.y)) / 4.0);
  float gl = texture2D(uGlyph, auv).r;
  vec3 tint;
  vec3 fg;
  if (uP1 < 0.5) { fg = clamp(c * 1.6 + 0.1, 0.0, 1.0); tint = c; }
  else {
    if (uP1 < 1.5) tint = vec3(0.2, 1.0, 0.35);
    else if (uP1 < 2.5) tint = vec3(1.0, 0.7, 0.15);
    else if (uP1 < 3.5) tint = vec3(0.95);
    else tint = vec3(0.3, 0.85, 1.0);
    fg = tint;
  }
  vec3 bg = tint * luma(c) * uP3 * 0.6;
  vec3 col = bg + fg * gl * 1.5;
  col += fg * luma(c) * uP2 * 0.18;
  return col;
}
