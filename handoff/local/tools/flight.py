# Полёт точки с массой в стандартной атмосфере с кривой Cd(M): «Град», падение MOP, сопротивление изделий.
# Запуск: python3 flight.py. Кривая cd_proj — типовая форма для тонких оперённых тел, не продувка (оценка).
import math
g=9.80665
def isa(h):
    h=max(0.0,h)
    if h<11000:
        T=288.15-0.0065*h; p=101325*(T/288.15)**5.2559
    else:
        T=216.65; p=22632*math.exp(-(h-11000)/6341.6)
    return p/(287.05*T), math.sqrt(1.4*287.05*T)
def cd_proj(M):  # относительная кривая сопротивления, пик в трансзвуке
    if M<0.8: return 1.0
    if M<1.05: return 1.0+ (M-0.8)/0.25*0.8
    if M<1.2: return 1.8
    return max(1.1,1.8-(M-1.2)*0.45)
def fly(v0,elev,cda_k,m,h0=0,dt=0.01):
    x=0;h=h0;vx=v0*math.cos(math.radians(elev));vy=v0*math.sin(math.radians(elev));t=0;hmax=h0
    while True:
        rho,a=isa(h);v=math.hypot(vx,vy);M=v/a
        D=0.5*rho*v*v*cda_k*cd_proj(M)
        ax=-D/m*vx/v; ay=-g-D/m*vy/v
        vx+=ax*dt;vy+=ay*dt;x+=vx*dt;h+=vy*dt;t+=dt;hmax=max(hmax,h)
        if h<0 and t>0.5: return x,t,hmax,math.hypot(vx,vy),math.hypot(vx,vy)/isa(0)[1],math.degrees(math.atan2(-vy,vx))
# «Град»: CdA до звука такой, чтобы наибольшая дальность при углах до 55° была 20 380 м
# при скорости 690 м/с на выгорании и массе ~46.6 кг (66.6 − ~20 кг топлива, уточнить)
m=46.6
def best(k):
    return max((fly(690,e,k,m)[0],e) for e in range(20,56,1))
lo,hi=1e-4,0.05
for _ in range(40):
    mid=(lo+hi)/2
    if best(mid)[0]>20380: lo=mid
    else: hi=mid
k=lo; R,e=best(k)
A=math.pi*0.061**2
print("Grad CdA(subsonic)=%.5f m2, Cd_sub=%.2f, best elev=%d"%(k,k/A,e))
for el in (e,30,15):
    x,t,hm,vi,Mi,ang=fly(690,el,k,m)
    print(" elev %2d: range %.1f km, time %.0f s, apogee %.1f km, impact %.0f m/s (M%.2f) at %.0f deg"%(el,x/1000,t,hm/1000,vi,Mi,ang))
for el in range(1,60):
    x,t,hm,vi,Mi,ang=fly(690,el,k,m)
    if x>5000: print(" ~5 km at elev %d: time %.1f s apogee %.2f km impact %.0f m/s M%.2f angle %.0f"%(el,t,hm/1000,vi,Mi,ang));break
# MOP: сброс с 12.2 км на 250 м/с, масса 12.3 т (по другим данным 13.6), диаметр 0.8 м
for cd in (0.2,0.35):
    x,t,hm,vi,Mi,ang=fly(250,0,cd*math.pi*0.4**2,12304,h0=12200)
    print("MOP Cd %.2f: fall %.0f s, travel %.1f km, impact %.0f m/s (M%.2f) at %.0f deg"%(cd,t,x/1000,vi,Mi,ang))
# «Томагавк»: тяга 3.1 кН = сопротивление на 255 м/с у земли
print("Tomahawk CdA=%.4f"%(2*3100/(1.225*255**2)))
# Шахед: 37.3 кВт при 51.4 м/с с КПД винта 0.6–0.75; пике под 30, 45, 90 градусов
P=37300
for eta in (0.6,0.75):
    D=eta*P/51.4; cda=2*D/(1.225*51.4**2)
    out=[]
    for gam in (30,45,90):
        v=51.4
        for _ in range(200):
            T=eta*P/v
            v=math.sqrt(2*(200*g*math.sin(math.radians(gam))+T)/(1.225*cda))
        out.append(f"{gam}deg {v:.0f} m/s")
    print("Shahed eta %.2f CdA=%.3f dive:"%(eta,cda), out)
# «Ланцет»: отвесное пике 300 км/ч при 12 кг
cdaL=2*12*g/(1.225*83.3**2)
print("Lancet CdA=%.4f, parasitic power at 110 km/h=%.0f W"%(cdaL,0.5*1.225*(30.56**3)*cdaL))
# Пробитие Янга (SAND97-2426, прил. A) для MOP: D = 0.000018·S·N·(m/A)^0.7·(V − 30.5), V ≥ 61 м/с
def young(S,N,m,A,V):
    return 0.000018*S*N*(m/A)**0.7*(V-30.5) if V>=61 else 0.0008*S*N*(m/A)**0.7*math.log(1+2.15e-4*V*V)
N=0.18*math.sqrt(6-0.25)+0.56
for m_,V in ((12300,505),(13600,523)):
    A_=math.pi*0.4**2
    print("MOP m=%d V=%d: D per S = %.1f m (N=%.2f); stone S=0.84 -> %.1f m, deepslate 0.67 -> %.1f m, dirt 10 -> %.0f m"%(
        m_,V,young(1,N,m_,A_,V),N,young(0.84,N,m_,A_,V),young(0.67,N,m_,A_,V),young(10,N,m_,A_,V)))
