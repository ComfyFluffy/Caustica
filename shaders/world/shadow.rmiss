#version 460
#extension GL_EXT_ray_tracing : require

// Shadow / sky-visibility miss (SBT miss index 1). Secondary rays are traced with
// TerminateOnFirstHit | SkipClosestHit, so reaching this shader means the ray escaped without
// hitting geometry -> the surface point is visible to the sun (or to open sky for AO). The caller
// initializes shadowVis.rgb to 1.0 transmittance and shadowVis.a to 0.0 before tracing, so leave
// accumulated colored-glass transmittance intact and only mark the ray escaped here.
layout(location = 1) rayPayloadInEXT vec4 shadowVis;

void main() {
    shadowVis.a = 1.0;
}
