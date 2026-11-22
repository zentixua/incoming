// Slot zero alone can hand its lofted dust and head to the young-wall pass. Its four
// radii are the inner and full handoff, fireball footprint and last table node.
const float DW_NODES = 128.0;
float wallFootprint(float cut, float r) {
    if (cut <= 0.0) return 1.0;
    return smoothstep(cut, cut + 40.0, r);
}

float wallShare(vec4 ring, float r) {
    if (ring.w <= 0.0) return 0.0;
    float inner = smoothstep(ring.x, ring.y, r);
    float footprint = wallFootprint(ring.z, r);
    return inner * footprint * (1.0 - smoothstep(ring.w * (1.0 - 1.0 / (DW_NODES - 1.0)), ring.w, r));
}
