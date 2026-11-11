// Datamosh feedback pass: block motion (Lucas-Kanade) drags the previous
// output along instead of refreshing it, like a video stream that lost its keyframes.
void main() {
  vec2 q = gl_FragCoord.xy / uRes;
  vec3 cur = texture2D(uHist, q).rgb;
  if (uFirst > 0.5) { gl_FragColor = vec4(cur, 1.0); return; }
  float blocks = 36.0 / max(uScale, 0.25);
  vec2 grid = vec2(blocks, blocks * uRes.y / uRes.x);
  vec2 cell = floor(q * grid);
  vec2 b = (cell + 0.5) / grid;
  vec2 e = uHistTexel * 2.0;
  float A = 0.0, B = 0.0, C = 0.0, D = 0.0, E = 0.0;
  for (int i = -1; i <= 1; i++) {
    for (int j = -1; j <= 1; j++) {
      vec2 o = b + vec2(float(i), float(j)) * e;
      float lc = luma(texture2D(uHist, o).rgb);
      float lp = luma(texture2D(uPrevH, o).rgb);
      float ix = (luma(texture2D(uHist, o + vec2(e.x, 0.0)).rgb) - luma(texture2D(uHist, o - vec2(e.x, 0.0)).rgb)) * 0.5;
      float iy = (luma(texture2D(uHist, o + vec2(0.0, e.y)).rgb) - luma(texture2D(uHist, o - vec2(0.0, e.y)).rgb)) * 0.5;
      float it = lc - lp;
      A += ix * ix; B += ix * iy; C += iy * iy; D -= ix * it; E -= iy * it;
    }
  }
  float det = A * C - B * B + 2e-4;
  vec2 flow = vec2(C * D - B * E, A * E - B * D) / det;
  flow = clamp(flow, vec2(-3.0), vec2(3.0));
  float gain = 1.0 + 6.0 * uP0;
  vec2 disp = flow * e * gain;
  float tb = floor(uTime * 7.0);
  float h = hash21(cell + tb);
  vec2 jit = vec2(0.0);
  if (h < uP1 * 0.12) {
    jit = (vec2(hash21(cell + tb + 7.0), hash21(cell + tb + 13.0)) - 0.5) * 0.14 * uP1;
  }
  vec3 old = texture2D(uMosh, q - disp + jit).rgb;
  float ph = mod(uTime, max(uP2, 0.5));
  float iframe = 1.0 - smoothstep(0.0, 0.35, ph);
  float k = 0.012 + 0.55 * iframe;
  gl_FragColor = vec4(mix(old, cur, k), 1.0);
}
