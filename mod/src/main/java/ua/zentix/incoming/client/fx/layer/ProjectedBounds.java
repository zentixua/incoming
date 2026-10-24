package ua.zentix.incoming.client.fx.layer;

/** Sutherland–Hodgman clipping in homogeneous viewport planes, before perspective division. */
final class ProjectedBounds {
    private final float[] a = new float[32], b = new float[32];
    private float x0, y0, x1, y1;

    /** Corners are clip-space xyzw in quad order. Both real GL triangles contribute to the conservative rectangle. */
    void quad(float[] corners, float[] out, int offset) {
        x0 = y0 = 1; x1 = y1 = 0;
        int any=0, common=15;
        boolean behind=true, regular=true;
        for(int n=0;n<4;n++) {
            int o=4*n; float x=corners[o],y=corners[o+1],w=corners[o+3];
            int code=(x < -w ? 1 : 0)|(x > w ? 2 : 0)|(y < -w ? 4 : 0)|(y > w ? 8 : 0);
            any|=code; common&=code; behind&=w<=0; regular&=w>1e-10f;
        }
        if(common!=0 || behind) {
            out[offset]=out[offset+1]=out[offset+2]=out[offset+3]=0;
            return;
        }
        if(any==0 && regular) {
            for(int n=0;n<4;n++) point(corners,4*n);
        } else {
            triangle(corners, 0, 1, 2);
            triangle(corners, 2, 3, 0);
        }
        if (x1 <= x0 || y1 <= y0) x0 = y0 = x1 = y1 = 0;
        out[offset] = x0; out[offset+1] = y0; out[offset+2] = x1; out[offset+3] = y1;
    }

    private void triangle(float[] corners, int i, int j, int k) {
        System.arraycopy(corners, 4*i, a, 0, 4);
        System.arraycopy(corners, 4*j, a, 4, 4);
        System.arraycopy(corners, 4*k, a, 8, 4);
        float[] in = a, dst = b;
        int count = 3;
        // Ignore z clipping: this remains conservative with depth clamp or a different depth convention under Iris.
        for (int plane=0; plane<4 && count>0; plane++) {
            int written=0, previous=count-1;
            float before=distance(in,previous,plane);
            for (int current=0; current<count; current++) {
                float after=distance(in,current,plane);
                if ((before>=0)!=(after>=0)) {
                    float t=before/(before-after);
                    for (int c=0;c<4;c++) dst[4*written+c]=in[4*previous+c]+t*(in[4*current+c]-in[4*previous+c]);
                    written++;
                }
                if (after>=0) { System.arraycopy(in,4*current,dst,4*written,4); written++; }
                before=after; previous=current;
            }
            count=written;
            float[] swap=in; in=dst; dst=swap;
        }
        for (int n=0;n<count;n++) {
            int o=4*n;
            if (!(in[o+3]>1e-10f)) {
                // A singular eye-plane vertex is rare; charge the whole frame rather than underestimate it.
                x0=y0=0; x1=y1=1;
                return;
            }
            point(in,o);
        }
    }

    private void point(float[] p,int o) {
        float x=Math.clamp(p[o]/p[o+3]*0.5f+0.5f,0,1),y=Math.clamp(p[o+1]/p[o+3]*0.5f+0.5f,0,1);
        x0=Math.min(x0,x); y0=Math.min(y0,y); x1=Math.max(x1,x); y1=Math.max(y1,y);
    }

    private static float distance(float[] p,int index,int plane) {
        int o=4*index;
        return p[o+3]+((plane&1)==0 ? p[o+(plane>>1)] : -p[o+(plane>>1)]);
    }
}
