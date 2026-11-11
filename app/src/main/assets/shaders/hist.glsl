// History pass: renders the camera frame at low resolution into quad space.
// Motion mode: a = smoothed motion mask with decaying trail.
// HIST_DEPTH:  a = local focus energy (sharpness), temporally smoothed.
void main() {
  vec2 q = gl_FragCoord.xy / uRes;
  vec2 e = 0.75 / uRes;
#ifdef HIST_DEPTH
  vec3 c0 = srcQ(q);
  float c = luma(c0);
  float g = 0.0;
  g += abs(luma(srcQ(q + vec2(e.x * 2.0, 0.0))) - c);
  g += abs(luma(srcQ(q - vec2(e.x * 2.0, 0.0))) - c);
  g += abs(luma(srcQ(q + vec2(0.0, e.y * 2.0))) - c);
  g += abs(luma(srcQ(q - vec2(0.0, e.y * 2.0))) - c);
  g += abs(luma(srcQ(q + e * 3.0)) - c) * 0.7;
  g += abs(luma(srcQ(q - e * 3.0)) - c) * 0.7;
  vec4 prev = texture2D(uPrevH, q);
  float a = (uFirst > 0.5) ? g : mix(g, prev.a, 0.8);
  gl_FragColor = vec4(c0, a);
#else
  vec3 cur = (srcQ(q + e) + srcQ(q - e) + srcQ(q + vec2(e.x, -e.y)) + srcQ(q + vec2(-e.x, e.y))) * 0.25;
  vec4 prev = texture2D(uPrevH, q);
  if (uFirst > 0.5) prev = vec4(cur, 0.0);
  float d = length(cur - prev.rgb);
  float thr = mix(0.2, 0.025, clamp(uP0, 0.0, 1.0));
  float m = smoothstep(thr, thr * 2.0 + 0.02, d);
  float decay = mix(0.55, 0.95, clamp(uP1, 0.0, 1.0));
  gl_FragColor = vec4(cur, max(m, prev.a * decay));
#endif
}
