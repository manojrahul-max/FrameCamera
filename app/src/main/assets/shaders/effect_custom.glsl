// Layered custom effect. Up to four layers, each with its own adjustments and a region mask.
uniform vec4 uL0[4]; uniform vec4 uL1[4]; uniform vec4 uL2[4]; uniform vec4 uL3[4];
uniform vec4 uL4[4]; uniform vec4 uL5[4]; uniform vec4 uL6[4]; uniform vec4 uL7[4]; uniform vec4 uL8[4];
uniform sampler2D uMask;   // RGBA: brush mask of layer 0..3 in channels R..A, output space

vec3 hueRot(vec3 c, float a) {
  float cs = cos(a * 6.2831853), sn = sin(a * 6.2831853);
  mat3 toYIQ = mat3(0.299, 0.596, 0.211, 0.587, -0.274, -0.523, 0.114, -0.322, 0.312);
  mat3 toRGB = mat3(1.0, 1.0, 1.0, 0.956, -0.272, -1.106, 0.621, -0.647, 1.703);
  vec3 y = toYIQ * c;
  y.yz = vec2(cs * y.y - sn * y.z, sn * y.y + cs * y.z);
  return toRGB * y;
}
float hueOf(vec3 c) {
  float mx = max(c.r, max(c.g, c.b)), mn = min(c.r, min(c.g, c.b));
  float d = mx - mn + 1e-5;
  float h;
  if (mx == c.r) h = mod((c.g - c.b) / d, 6.0);
  else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
  else h = (c.r - c.g) / d + 4.0;
  return fract(h / 6.0);
}
float maskAt(vec2 s, vec3 col, vec4 g, vec4 h, vec4 k, int ch) {
  float asp = uRes.x / uRes.y;
  vec2 p = (s - 0.5) * vec2(asp, 1.0);
  float m = 1.0;
  float type = g.x;
  if (type > 0.5 && type < 1.5) {
    float ca = cos(g.y), sa = sin(g.y);
    vec2 r = vec2(ca * p.x + sa * p.y, -sa * p.x + ca * p.y);
    float d = r.x - g.z * asp * 0.5 - h.y * r.y * r.y * 2.0;
    float f = max(g.w * 0.3, 0.002);
    m = smoothstep(-f, f, d);
  } else if (type < 2.5 && type > 1.5) {
    vec2 q = (p - vec2(h.z * asp, h.w)) / vec2(k.x, k.y);
    float f = clamp(g.w, 0.0, 1.0);
    m = 1.0 - smoothstep(max(1.0 - f, 0.0), 1.0 + f * 0.3 + 0.001, length(q));
  } else if (type < 3.5 && type > 2.5) {
    vec2 q = abs(p - vec2(h.z * asp, h.w)) - vec2(k.x * asp, k.y) * 0.5;
    float f = g.w * 0.3 + 0.002;
    m = 1.0 - smoothstep(-f, f, max(q.x, q.y));
  } else if (type < 4.5 && type > 3.5) {
    vec4 t = texture2D(uMask, vec2(s.x, 1.0 - s.y));
    if (ch == 0) m = t.r; else if (ch == 1) m = t.g; else if (ch == 2) m = t.b; else m = t.a;
  } else if (type > 4.5) {
    float hd = abs(hueOf(col) - k.z);
    hd = min(hd, 1.0 - hd);
    float mx = max(col.r, max(col.g, col.b)), mn = min(col.r, min(col.g, col.b));
    float sat = (mx - mn) / (mx + 1e-4);
    m = (1.0 - smoothstep(k.w, k.w + g.w * 0.3 + 0.01, hd)) * smoothstep(0.08, 0.22, sat);
  }
  if (h.x > 0.5) m = 1.0 - m;
  return m;
}
vec3 effect(vec2 s) {
  vec3 col = S(s);
  float asp = uRes.x / uRes.y;
  for (int i = 0; i < 4; i++) {
    vec4 A = uL0[i]; vec4 B = uL1[i]; vec4 C = uL2[i]; vec4 D = uL3[i];
    vec4 E = uL4[i]; vec4 F = uL5[i]; vec4 G = uL6[i]; vec4 Hh = uL7[i]; vec4 K = uL8[i];
    if (E.w > 0.001) {
      vec2 sm = s;
      if (F.z > 0.5) sm.x = (F.z < 1.5) ? min(s.x, 1.0 - s.x) : max(s.x, 1.0 - s.x);
      vec3 c0;
      float off = F.y * 0.012;
      if (off > 0.0) c0 = vec3(S(sm + vec2(off, 0.0)).r, S(sm).g, S(sm - vec2(off, 0.0)).b);
      else c0 = S(sm);
      vec3 c = c0;
      if (C.z > 0.01 || C.y > 0.01 || F.w > 0.01) {
        float rb = max(C.z * 0.03, 0.004);
        vec2 o = vec2(rb / asp, rb);
        vec2 h2 = o * 0.5;
        vec3 bl = c0;
        bl += S(sm + vec2(o.x, 0.0)) + S(sm - vec2(o.x, 0.0)) + S(sm + vec2(0.0, o.y)) + S(sm - vec2(0.0, o.y));
        bl += S(sm + o * 0.7071) + S(sm - o * 0.7071) + S(sm + vec2(o.x, -o.y) * 0.7071) + S(sm + vec2(-o.x, o.y) * 0.7071);
        bl += S(sm + vec2(h2.x, 0.0)) + S(sm - vec2(h2.x, 0.0)) + S(sm + vec2(0.0, h2.y)) + S(sm - vec2(0.0, h2.y));
        bl += S(sm + h2 * 0.7071) + S(sm - h2 * 0.7071) + S(sm + vec2(h2.x, -h2.y) * 0.7071) + S(sm + vec2(-h2.x, h2.y) * 0.7071);
        bl /= 17.0;
        if (C.z > 0.01) c = bl;
        c += (c0 - bl) * C.y * 1.5;
        c += max(bl - 0.55, 0.0) * F.w * 1.6;
      }
      c *= exp2(A.x);
      c += A.y;
      c = (c - 0.5) * A.z + 0.5;
      float l = luma(c);
      c += B.w * 0.3 * smoothstep(0.5, 1.0, l);
      c += C.x * 0.3 * (1.0 - smoothstep(0.0, 0.5, l));
      c.r += B.x * 0.12; c.b -= B.x * 0.12;
      c.g -= B.y * 0.08; c.r += B.y * 0.04; c.b += B.y * 0.04;
      if (abs(B.z) > 0.001) c = hueRot(c, B.z);
      l = luma(c);
      c = mix(vec3(l), c, 1.0 + A.w);
      c *= vec3(E.x, E.y, E.z);
      c = mix(c, vec3(luma(c)), D.y);
      vec3 sep = vec3(dot(c, vec3(0.393, 0.769, 0.189)), dot(c, vec3(0.349, 0.686, 0.168)), dot(c, vec3(0.272, 0.534, 0.131)));
      c = mix(c, sep, D.z);
      c = mix(c, c * vec3(1.06, 0.98, 0.84) * 0.88 + vec3(0.07, 0.05, 0.03), D.w);
      if (F.x > 0.001) {
        float lc = luma(c);
        float sw = 1.0 - smoothstep(0.0, 0.6, lc);
        float hw = smoothstep(0.4, 1.0, lc);
        c += F.x * (sw * (vec3(0.0, 0.35, 0.45) - 0.15) * 0.5 + hw * (vec3(0.55, 0.3, 0.0) - 0.2) * 0.5);
        c = mix(c, smoothstep(0.0, 1.0, c), F.x * 0.5);
      }
      c *= 1.0 - C.w * smoothstep(0.35, 1.1, length((s - 0.5) * vec2(asp, 1.0) * 1.5));
      c += (hash21(gl_FragCoord.xy + floor(uTime * 24.0) * 13.0) - 0.5) * D.x * 0.35;
      float m = maskAt(s, col, G, Hh, K, i);
      col = mix(col, clamp(c, 0.0, 1.0), m * E.w);
    }
  }
  return col;
}
