"""Generated nonempty source-schema fixtures. No Minecraft/runtime dependencies."""
import gzip
import json
from pathlib import Path
import struct
import unittest
import ledger_audit as la

BASE=Path(__file__).parent/'fixtures'
BASE.mkdir(exist_ok=True)
def string(s):
    b=s.encode(); return struct.pack('>H',len(b))+b
def val(t,x):
    if t in (1,3,4,6):return struct.pack({1:'>b',3:'>i',4:'>q',6:'>d'}[t],x)
    if t==8:return string(x)
    if t==10:return compound(x)
    if t==9:
        et,rows=x;return struct.pack('>Bi',et,len(rows))+b''.join(val(et,r) for r in rows)
    if t in (7,11,12):
        return struct.pack('>i',len(x))+(bytes(x) if t==7 else b''.join(struct.pack('>i' if t==11 else '>q',v) for v in x))
    raise AssertionError(t)
def compound(fields):return b''.join(bytes([t])+string(k)+val(t,v) for k,(t,v) in fields.items())+b'\0'
def state():return {'Name':(8,'minecraft:stone')}
def material(vals,crater=False):return {('state' if crater else 'block'):(10,state()) if crater else (8,'minecraft:stone'),**{'v'+str(i):(6,v) for i,v in enumerate(vals)}}
def write(name,data,case):
    folder=BASE/case;folder.mkdir(exist_ok=True);p=folder/name
    raw=b'\x0a\0\0'+compound({'DataVersion':(3,3955),'data':(10,data)})
    p.write_bytes(gzip.compress(raw,mtime=0));return p

def ruin(legacy=False):
    numeric={'source':1,'id':2,'scorch':3,'scorch_event':4,'scorch_time':5,'volume':2.,'mass':5000.,'x':1.,'y':80.,'z':2.,'vx':.1,'vy':-.3,'vz':.2,'tick':4.,'released_tick':1.,'wind_x':.01,'wind_z':.02,'drag':.1,'pulse':2.,'outside':0}
    row={'state':(10,state()),'contents':(10,{'Items':(9,(10,[{'id':(8,'minecraft:diamond'),'count':(3,7)}]))})}
    if legacy:
        for k,v in numeric.items():row[k]=(1 if k=='outside' else 3 if k in ('scorch','scorch_event') else 4 if k in ('source','id','scorch_time') else 6,v)
    else:row['motion']=(7,struct.pack('>qqiiq14dB',*(numeric[k] for k in numeric)))
    return {'materials':(9,(10,[material([5,12500,1,2500,1,2500])])),
            **{k:(4,v) for k,v in {'released':4,'deposited':1,'dispersed':1}.items()},
            **{k:(6,v) for k,v in {'released_volume':5,'released_mass':12500,'deposited_volume':1,'deposited_mass':2500,'dispersed_volume':1,'dispersed_mass':2500}.items()},
            'moving':(9,(10,[row])), 'stranded':(9,(10,[{'state':(10,state()),'count':(4,1),'volume':(6,1),'mass':(6,2500)}])),
            'supports':(12,[123,456]),'support_events':(11,[4,4]),
            'support_sources':(9,(10,[{'position':(4,1),'event':(3,4),'state':(10,state())}])),
            'support_originals':(9,(10,[{'position':(4,2),'event':(3,4),'state':(10,state())}])),
            'cold_edges':(9,(10,[{'position':(4,456),'face_2':(10,state())}]))}

class Tests(unittest.TestCase):
    def test_packed_and_legacy(self):
        for legacy in (False,True):
            r=la.audit_file(write('incoming_ruin_matter.dat',ruin(legacy),'legacy' if legacy else 'packed'))
            self.assertEqual(r['status'],'pass',r)
            self.assertEqual(r['pending_count_volume_mass'],[2,3.,7500.])
            self.assertEqual(r['moving_and_stranded_count_volume_mass']['moving'],[1,2.,5000.])
            self.assertEqual(r['cargo']['represented_pieces'],1)
            self.assertEqual(r['support_arrays']['supports']['count'],2)
            self.assertEqual(r['residuals']['_mass'],0)
    def test_legacy_empty_lists_and_semantic_motion(self):
        reports=[]
        for legacy in (False,True):
            reports.append(la.audit_file(write('incoming_ruin_matter.dat',ruin(legacy),'semantic'+str(legacy))))
        self.assertEqual(reports[0]['semantic_moving_digest'],reports[1]['semantic_moving_digest'])
        data={'passes':(9,(0,[])),'settled':(9,(0,[]))}
        self.assertEqual(la.audit_file(write('incoming_crater_matter.dat',data,'legacy-empty'))['status'],'pass')
        data=ruin();data['moving']=(9,(0,[]));data['released']=(4,3);data['released_volume']=(6,3);data['released_mass']=(6,7500)
        data['materials']=(9,(10,[material([3,7500,1,2500,1,2500])]))
        self.assertEqual(la.audit_file(write('incoming_ruin_matter.dat',data,'legacy-empty-moving'))['status'],'pass')
    def test_crater_progress(self):
        row=material([10,25000,2,5000,6,15000,2,5000],True);row['available']=(4,6)
        crater={'passes':(9,(10,[{'event':(3,4),'soil':(8,'minecraft:stone'),'columns':(12,[1,2]),'ground':(11,[64,65]),'done':(12,[1]),'materials':(9,(10,[row]))}])), 'settled':(9,(10,[material([2,5000,0,0,2,5000,0,0],True)]))}
        r=la.audit_file(write('incoming_crater_matter.dat',crater,'crater'));self.assertEqual(r['status'],'pass',r);self.assertEqual(r['material_v0_to_v7'][0],12)
        for version in (0,2,3):
            row={'cursor':(4,123),'scan_version':(3,version),'complete':(1,0),'archive':(8,'ruin-plan-4.bin'),'pending':(12,[100,200])}
            r=la.audit_file(write('incoming_ruin_settle.dat',{'4':(10,row)},'progress'+str(version)))
            self.assertEqual(r['status'],'unsupported' if version==3 else 'pass',r)
            if version!=3:self.assertEqual(r['event_samples'][0]['effective_cursor'],123 if version==2 else 0)
    def test_bad_inputs(self):
        data=ruin();data['released_mass']=(6,1)
        r=la.audit_file(write('incoming_ruin_matter.dat',data,'negative-residual'));self.assertEqual(r['status'],'fail');self.assertLess(r['residuals']['_mass'],0)
        data=ruin();data['new_schema']=(3,1)
        self.assertEqual(la.audit_file(write('incoming_ruin_matter.dat',data,'unknown'))['status'],'unsupported')
        data=ruin();data['support_events']=(11,[4]);self.assertEqual(la.audit_file(write('incoming_ruin_matter.dat',data,'bad-support'))['status'],'fail')
        data=ruin();data['moving'][1][1][0]['motion']=(7,b'x'*157)
        self.assertEqual(la.audit_file(write('incoming_ruin_matter.dat',data,'live157'))['status'],'unsupported')
        p=write('incoming_ruin_matter.dat',ruin(),'truncated');p.write_bytes(p.read_bytes()[:-8]);self.assertEqual(la.audit_file(p)['status'],'fail')
        p=write('incoming_ruin_matter.dat',ruin(),'corrupted');raw=bytearray(p.read_bytes());raw[-8]^=1;p.write_bytes(raw);self.assertEqual(la.audit_file(p)['status'],'fail')
        p=write('incoming_ruin_matter.dat',ruin(),'corrupt-deflate');raw=bytearray(p.read_bytes());raw[10]=255;p.write_bytes(raw);self.assertEqual(la.audit_file(p)['status'],'fail')
    def test_typed_cargo_and_nan_motion(self):
        out=[]
        for t in (1,4):
            data=ruin();data['moving'][1][1][0]['contents']=(10,{'custom':(t,7)})
            out.append(la.audit_file(write('incoming_ruin_matter.dat',data,'typed-cargo'+str(t))))
        self.assertNotEqual(out[0]['row_digests']['data/moving'],out[1]['row_digests']['data/moving'])
        data=ruin();row=data['moving'][1][1][0];raw=bytearray(row['motion'][1]);struct.pack_into('>d',raw,48,float('nan'));row['motion']=(7,raw)
        self.assertEqual(la.audit_file(write('incoming_ruin_matter.dat',data,'nan-motion'))['status'],'fail')
    def test_separate_crater_cursor(self):
        data={'next_id':(3,5),'late_from':(3,1),'craters':(10,{'4':(3,123)}),**{k:(9,(0,[])) for k in ('detonations','past','scheduled')}}
        r=la.audit_file(write('incoming_nuclear.dat',data,'nuclear'))
        self.assertEqual(r['status'],'pass',r);self.assertEqual(r['crater_chunk_cursors'],{'4':123})
    def test_fold_order(self):
        a=la.Fold();b=la.Fold()
        for i in [1,2,3]:a.add(i)
        for i in [3,1,2]:b.add(i)
        self.assertEqual(a.result()['sum_sha256_mod_2_256'],b.result()['sum_sha256_mod_2_256'])
        self.assertNotEqual(a.result()['ordered_sha256'],b.result()['ordered_sha256'])
    def test_large_array_stream(self):
        # More bytes than the row cap, no array allocation by the reader.
        p=BASE/'array-stream.nbt'
        with p.open('wb') as f:
            f.write(struct.pack('>i',(la.MAX_ROW//8)+1))
            for _ in range((la.MAX_ROW//8)+1):f.write(b'\0'*8)
        with p.open('rb') as f:
            r=la.Reader(f,la.Audit('progress'));r.row_start=0
            out=r.payload(12,('data','4','pending'));self.assertEqual(out['count'],la.MAX_ROW//8+1)

if __name__=='__main__':unittest.main()
