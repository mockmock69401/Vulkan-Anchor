//Visuals: a view position's full-resolution pixel (row 0 at the top), shared by lighting.comp and reflection.comp. The
// including shader declares proj (view to clip: only x, y and w are used) and sets fullSize before calling.

ivec2 fullSize;

//The full-resolution pixel a view position lands on, or -1 off screen or behind the camera
ivec2 toPixel(vec3 v) {
    vec4 clip = proj*vec4(v, 1.0);
    if (clip.w <= 0.0) {
        return ivec2(-1);
    }
    vec2 uvGl = clip.xy/clip.w*0.5 + 0.5;
    ivec2 p = ivec2(floor(vec2(uvGl.x, 1.0 - uvGl.y)*vec2(fullSize)));
    return all(greaterThanEqual(p, ivec2(0))) && all(lessThan(p, fullSize)) ? p : ivec2(-1);
}
