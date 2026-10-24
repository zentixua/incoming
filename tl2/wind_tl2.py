import math
M=(1<<64)-1
def mix(day,salt):
    z=(day*0xBF58476D1CE4E5B9+salt)&M
    z=((z^(z>>30))*0xBF58476D1CE4E5B9)&M
    z=((z^(z>>27))*0x94D049BB133111EB)&M
    return z^(z>>31)
def unit(h): return (h>>11)*2.0**-53
def angle(d): return unit(mix(d,0x9E3779B97F4A7C15))*2*math.pi
def speed(d): return 5+10*unit(mix(d,0xD1B54A32D192ED03))
def wind(t):
    day=t//24000; f=(t-day*24000)/24000; k=f*f*(3-2*f)
    a0,a1,s0,s1=angle(day),angle(day+1),speed(day),speed(day+1)
    x=s0*math.cos(a0)*(1-k)+s1*math.cos(a1)*k; z=s0*math.sin(a0)*(1-k)+s1*math.sin(a1)*k
    return math.atan2(z,x), math.hypot(x,z)
for name,t,y in [("sam-side",22313,110),("sam-burst",25949,120)]:
    d,s=wind(t)
    for h in (10, y-63):
        u=s*(min(max(h,1),1000)/1000)**0.10
        print(name,t,"dir deg %.1f (blows toward x=%.3f z=%.3f)"%(math.degrees(d),math.cos(d),math.sin(d)),"U1000 %.2f"%s,"h=%d u=%.2f"%(h,u))
print("sandbox")
d,s=wind(6000)
print("dir %.1f deg toward x=%.3f z=%.3f U1000 %.2f"%(math.degrees(d),math.cos(d),math.sin(d),s))
for c,al in ((2,0.10),(3,0.15),(1,0.07)):
  print(c,[ "%d:%.2f"%(h, s*(h/1000)**al) for h in (10,30,60,100)])
