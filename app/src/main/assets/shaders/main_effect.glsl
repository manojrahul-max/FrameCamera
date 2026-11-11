void main() {
  vec2 s = gl_FragCoord.xy / uRes;
  vec3 base = S(s);
  vec3 fx = grade(effect(s));
  gl_FragColor = vec4(clamp(mix(base, fx, uIntensity), 0.0, 1.0), 1.0);
}
