// Take 33's wall ownership (main b8dfcd09b), for dustwall_t33.glsl only: the shipped shaders have no ownership
// Slot zero alone can hand its lofted dust and head to the young-wall pass. Its four
// radii are the inner and full handoff, fireball footprint and last table node.
// (the dust table's 128 nodes: DW_NODES in dustwall.glsl)
float wallFootprint(float cut, float r) {
    if (cut <= 0.0) return 1.0;
    return smoothstep(cut - 40.0, cut, r);
}

float wallShare(vec4 ring, float r) {
    if (ring.w <= 0.0) return 0.0;
    float footprint = wallFootprint(ring.z, r);
    float outer = footprint * (1.0 - smoothstep(ring.w * (1.0 - 1.0 / 127.0), ring.w, r));
    if (r >= ring.y) return outer;
    if (r <= ring.x) return 0.0;
    return smoothstep(ring.x, ring.y, r) * outer;
}
