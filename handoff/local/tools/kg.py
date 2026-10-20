# Волна обычного заряда у земли: Кинни–Грэхем (давление, длительность, импульс), Джаррет, воронка.
# Запуск: python3 kg.py. Заряд — кг ТНТ; у земли W×1.8 (как BlastModel у ядерки).
import math
P0=101.325
def p(z): # кПа, Кинни–Грэхем
    a=z/4.5;b=z/0.048;c=z/0.32;d=z/1.35
    return P0*808*(1+a*a)/math.sqrt((1+b*b)*(1+c*c)*(1+d*d))
def td(z): # мс на кг^(1/3)
    return 980*(1+(z/0.54)**10)/((1+(z/0.02)**3)*(1+(z/0.74)**6)*math.sqrt(1+(z/6.9)**2))
def imp(z): # бар·мс на кг^(1/3)
    return 0.067*math.sqrt(1+(z/0.23)**4)/(z*z*(1+(z/1.55)**3)**(1/3))
def rng(kpa,W):
    lo,hi=1e-3,1e5
    for _ in range(200):
        m=math.sqrt(lo*hi)
        if p(m/W**(1/3))>kpa: lo=m
        else: hi=m
    return lo
def jarrett(K,W): return K*W**(1/3)/(1+(3175/W)**2)**(1/6)
cases=[("Lancet 3",3),("Grad 6.4",6.4),("Shahed 50",50),("Shahed 90",90),("Tomahawk ~150",150),("Kalibr 450",450),("MOP 2400",2400)]
psis=[("glass0.8",0.8),("wood5",5),("masonry12",12),("ground15",15)]
for name,Wc in cases:
    W=Wc*1.8
    row=[name]
    for lab,psi in psis:
        r=rng(psi*6.895,W); z=r/W**(1/3)
        row.append(f"{lab}: {r:6.1f} m t+={td(z)*W**(1/3):6.1f} ms I={imp(z)*W**(1/3)*100:7.1f} Pa*s")
    print(" | ".join(row))
    print("   jarrett A/B/Cb/Ca/D:", [round(jarrett(K,Wc),1) for K in (3.8,5.6,9.6,28,56)], " crater D 0.51-0.8:", round(0.51*Wc**(1/3),1), round(0.8*Wc**(1/3),1))
# ядерка 15 кт для сравнения
W=0.5*15*1e6*1.8
for lab,psi in psis:
    r=rng(psi*6.895,W); z=r/W**(1/3)
    print("nuke15", lab, round(r), "m t+", round(td(z)*W**(1/3)), "ms")
