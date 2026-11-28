#version 150

#define PALL_FROXEL_SUM
// the sums in two draws (fx_pall.glsl: 1 the blocks, 2 the cells from them; 0 one draw)
uniform float PallSumBlocks;
#moj_import <incoming:fx_pall.glsl>
