// Slot zero alone can hand its lofted dust and head to the young-wall pass. Its four
// radii are the inner and full handoff, fireball footprint and last table node.
const int DW_NODES = 128;
float wallShare(vec4 ring, float r) {
    if (ring.w <= 0.0) return 0.0;
    float inner = smoothstep(ring.x, ring.y, r);
    float footprint = ring.z > 0.0 ? smoothstep(ring.z, ring.z + 40.0, r) : 1.0;
    return inner * footprint * (1.0 - smoothstep(ring.w * (1.0 - 1.0 / float(DW_NODES - 1)), ring.w, r));
}
