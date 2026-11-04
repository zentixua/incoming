// Conservatively integrated source-local turbulent density. See DustGrid and DustGridTest.
float dustErf(float x) {
    float ax = abs(x), t = 1.0 / (1.0 + 0.3275911 * ax);
    float y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-ax * ax);
    return sign(x) * y;
}
// Integral of a modulated, truncated standard Gaussian. In its cumulative-mass coordinate u,
// 1 + a*cos(2*pi*n*u + phase) redistributes mass: the primitive has exactly unit difference
// between u=0 and u=1 for integer n. No random-density multiplier can create or remove mass.
float dustCdf(float q) {
    return clamp((dustErf(q * 0.70710678) + 0.9973002) / 1.9946004, 0.0, 1.0);
}
float dustIntegral(float q0, float q1, float phase, float frequency) {
    float a = dustCdf(q0), b = dustCdf(q1), k = 6.2831853 * frequency;
    return max(0.0, b - a + 0.92 / k * (sin(k*b + phase) - sin(k*a + phase)));
}
