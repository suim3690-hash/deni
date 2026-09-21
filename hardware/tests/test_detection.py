import json
import multiprocessing as mp
from pathlib import Path
import tempfile
import threading
import time
import unittest
import urllib.request
import urllib.error
from unittest.mock import patch
from pc_dashboard import CameraFeed, Controls, Dashboard, INPUT_TTL, VIDEO_TTL
from detection import config as C
from detection.service import LatestFrame, DetectionService
from detection.risk_engine import RiskEngine
from detection.event_store import EventStore, history


def obj(label='coin', confidence=.9, track=1, model='object'):
    return dict(model=model,label=label,class_id={'coin':0,'battery':1,'person':2}.get(label,3),
                confidence=confidence,track_id=track,bbox=[10,10,40,40])


def delayed_worker(mailbox, output, stop):
    item=None
    while item is None and not stop.wait(.01):item=mailbox.take(0)
    if item:output.put(item[1])
    stop.wait(1)
    item=mailbox.take(item[1] if item else 0)
    if item:output.put(item[1])


class Tests(unittest.TestCase):
    def test_vote_cooldown_and_escalation(self):
        e=RiskEngine();d=obj()
        for n in range(9):self.assertEqual(e.evaluate([d],n*.1)[1],[])
        self.assertEqual(len(e.evaluate([d],.9)[1]),1)
        self.assertEqual(e.evaluate([d],1)[1],[])
        risk,events=e.evaluate([d,obj('person',model='coco')],1.1)
        self.assertEqual(risk['level'],3);self.assertEqual(len(events),1)
        self.assertEqual(e.evaluate([d,obj('person',model='coco')],1.2)[1],[])
        self.assertEqual(len(e.evaluate([d],12)[1]),1)

    def test_urgent_without_id_or_votes(self):
        e=RiskEngine();d=obj(track=None);person=obj('person',model='coco')
        self.assertEqual(len(e.evaluate([d,person],1)[1]),1)
        self.assertEqual(e.evaluate([d,person],1.1)[1],[])
        self.assertEqual(e.evaluate([obj(track=9),person],1.2)[1],[])
        self.assertEqual(RiskEngine().evaluate([obj(confidence=.4),person],1)[0]['level'],1)

    def test_model_scoping(self):
        e=RiskEngine()
        for n in range(10):_,events=e.evaluate([obj(),obj('battery',model='other')],n*.1)
        self.assertEqual(len(events),2)

    def test_class_correction(self):
        e=RiskEngine()
        for n in range(10):e.evaluate([obj()],n*.1)
        events=[]
        for n in range(10):events+=e.evaluate([obj('battery')],1+n*.1)[1]
        self.assertTrue(any(d['label']=='battery' for d in events))

    def test_latest_mailbox_never_waits(self):
        m=LatestFrame(mp.get_context('spawn'),64)
        m.publish(b'first',1,1,1);m.publish(b'last',20,2,2)
        self.assertEqual(m.take(0)[:2],(b'last',20));self.assertIsNone(m.take(20))
        m.lock.acquire()
        try:self.assertFalse(m.publish(b'new',21,3,3));self.assertIsNone(m.take(0))
        finally:m.lock.release()

    def test_delayed_process_and_watchdogs(self):
        ctx=mp.get_context('spawn');m=LatestFrame(ctx,64);q=ctx.Queue();stop=ctx.Event()
        p=ctx.Process(target=delayed_worker,args=(m,q,stop));p.start()
        try:
            m.publish(b'first',1,time.monotonic(),time.time());self.assertEqual(q.get(timeout=3),1)
            for seq in range(2,31):m.publish(b'new',seq,time.monotonic(),time.time())
            f=CameraFeed();c=Controls(f)
            with patch('pc_dashboard.time.monotonic',return_value=100):
                f.publish(b'\xff\xd8\xff\xd9');c.ready=True;c.claim('owner',1);c.update('owner',1,'F',1)
                self.assertEqual(c.command(),'F')
            with patch('pc_dashboard.time.monotonic',return_value=100+INPUT_TTL+.01):
                self.assertEqual(c.command(),'S');self.assertIsNone(c.owner)
            with patch('pc_dashboard.time.monotonic',return_value=200):
                f.publish(b'\xff\xd8\xff\xd9');c.claim('owner',2)
            with patch('pc_dashboard.time.monotonic',return_value=200+VIDEO_TTL+.01):
                c.updated=200+VIDEO_TTL
                self.assertEqual(c.command(),'S')
            self.assertEqual(q.get(timeout=3),30)
        finally:stop.set();p.join(3);q.close()

    def test_stale_or_blurred_is_not_current(self):
        f=CameraFeed();f.publish(b'\xff\xd8\xff\xd9');s=DetectionService(f)
        s.latest=dict(status='ok',level=3,frame_stamp=time.monotonic()-C.RESULT_TTL-1)
        self.assertIsNone(s.state()['current_level'])
        s.latest=dict(status='blur',level=0,frame_stamp=time.monotonic())
        self.assertIsNone(s.state()['current_level'])

    def test_storage_and_http(self):
        with tempfile.TemporaryDirectory() as tmp,patch.object(C,'DATA',Path(tmp)):
            store=EventStore();payload=dict(level=2,detections=[obj('battery')])
            first=store.save(b'\xff\xd8photo\xff\xd9',payload,10)
            store.save(b'next',payload,20);store.close()
            self.assertEqual(history()[0]['timestamp'],20)
            self.assertEqual(history(before=20)[0]['id'],first['id'])
            f=CameraFeed();c=Controls(f);s=DetectionService(f,False)
            server=Dashboard(0,f,c,s);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            url='http://127.0.0.1:'+str(server.server_port)
            try:
                with urllib.request.urlopen(url+'/detections/history') as r:self.assertEqual(len(json.load(r)['events']),2)
                with urllib.request.urlopen(url+first['photo']) as r:self.assertEqual(r.read(),b'\xff\xd8photo\xff\xd9')
                with self.assertRaises(urllib.error.HTTPError) as cm:urllib.request.urlopen(url+'/detections/snapshots/../../pc_dashboard.py')
                self.assertEqual(cm.exception.code,404)
                with urllib.request.urlopen(url+'/state') as r:self.assertFalse(json.load(r)['enabled'])
            finally:server.shutdown();server.server_close();thread.join(1)


class ModeAndMotorTests(unittest.TestCase):
    def test_all_motor_commands_reversed_at_transport(self):
        from types import SimpleNamespace
        from pc_dashboard import motor_worker, MOTOR_COMMAND_MAP
        self.assertEqual(MOTOR_COMMAND_MAP,dict(F='B',B='F',L='R',R='L',S='S'))
        for command,expected in MOTOR_COMMAND_MAP.items():
            feed=CameraFeed();control=Controls(feed)
            control.command=lambda:command
            stop=threading.Event();sent=[]
            class Robot:
                def drive(self,cmd):sent.append(cmd);stop.set()
                def close(self):pass
            with patch('pc_dashboard.RobotClient',return_value=Robot()):
                motor_worker(SimpleNamespace(host='fake',token='fake',control_port=0),control,stop)
            self.assertEqual(sent,[expected]);self.assertEqual(control.ack,command)

    def test_mode_generation_and_late_result_rejection(self):
        import queue
        feed=CameraFeed();service=DetectionService(feed)
        service.results=queue.Queue()
        service.select_mode('hazard')
        generation=service.generation
        service.results.put(dict(generation=0,status='ok',mode='object',level=3))
        service.results.put(dict(generation=generation,status='ok',mode='hazard',level=1))
        thread=threading.Thread(target=service._collect);thread.start()
        deadline=time.monotonic()+1
        while service.results.qsize() and time.monotonic()<deadline:time.sleep(.005)
        service.local_stop.set();thread.join(1)
        self.assertEqual(service.latest['mode'],'hazard');self.assertEqual(service.latest['level'],1)
        service.select_mode('object');self.assertGreater(service.generation,generation)
        self.assertIsNone(service.state()['current_level'])
        with self.assertRaises(ValueError):service.select_mode('bad')
        self.assertEqual([s[0] for s in C.specs_for_mode('object')],['object'])
        self.assertEqual([s[0] for s in C.specs_for_mode('hazard')],['hazard'])

    def test_mode_endpoint_uses_session_and_does_not_arm(self):
        feed=CameraFeed();controls=Controls(feed);service=DetectionService(feed)
        server=Dashboard(0,feed,controls,service)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        url='http://127.0.0.1:'+str(server.server_port)
        try:
            def request(secret):
                data=json.dumps(dict(owner='mode-selector',secret=secret,mode='hazard')).encode()
                return urllib.request.Request(url+'/detections/mode',data=data,headers={'Origin':url,'Content-Type':'application/json'})
            with self.assertRaises(urllib.error.HTTPError) as error:urllib.request.urlopen(request('wrong'))
            self.assertEqual(error.exception.code,403)
            with urllib.request.urlopen(request(server.secret)) as r:self.assertTrue(json.load(r)['ok'])
            self.assertEqual(service.mode,'hazard');self.assertIsNone(controls.owner)
            self.assertEqual(controls.command(),'S')
        finally:server.shutdown();server.server_close();thread.join(1)


class SessionDiagnosticsTests(unittest.TestCase):
    def test_ack_latency_not_added_to_send_period(self):
        from types import SimpleNamespace
        from pc_dashboard import motor_worker
        clock=[100.0];waits=[]
        class Stop:
            done=False
            def is_set(self):return self.done
            def wait(self,delay):waits.append(delay);self.done=True
        class Robot:
            def drive(self,cmd):clock[0]+=.3
            def close(self):pass
        c=Controls(CameraFeed());c.command=lambda:'S'
        with patch('pc_dashboard.RobotClient',return_value=Robot()),patch('pc_dashboard.time.monotonic',side_effect=lambda:clock[0]):
            motor_worker(SimpleNamespace(host='fake',token='fake',control_port=0),c,Stop())
        self.assertEqual(waits,[0]);self.assertEqual(c.ack_ms,300)

    def test_reason_survives_stop_and_rearm(self):
        f=CameraFeed();c=Controls(f);c.ready=True
        with patch('pc_dashboard.time.monotonic',return_value=100):
            f.publish(b'\xff\xd8\xff\xd9');c.claim('owner',1)
        with patch('pc_dashboard.time.monotonic',return_value=100.5):
            self.assertEqual(c.command(),'S')
            reason=c.state()['last_stop'];self.assertIn('heartbeat',reason)
            c.stop_owner('owner','generic stop')
            self.assertEqual(c.state()['last_stop'],reason)
            f.publish(b'\xff\xd8\xff\xd9');c.claim('next',2)
            self.assertEqual(c.state()['last_stop'],reason)

    def test_setup_uses_only_local_custom_models(self):
        import sys
        from types import SimpleNamespace
        import setup_detection
        calls=[]
        class Model:
            task='detect';names={0:'test'}
            def __init__(self,path):calls.append(Path(path).name)
            def predict(self,*a,**kw):pass
        with tempfile.TemporaryDirectory() as temp:
            weights=Path(temp)
            for n in ('object','hazard'):(weights/(n+'.pt')).touch()
            specs=[(n,weights/(n+'.pt'),None) for n in ('object','hazard')]
            with patch.dict(sys.modules,{'ultralytics':SimpleNamespace(YOLO=Model)}),patch.object(C,'WEIGHTS',weights),patch.object(C,'MODEL_SPECS',specs):
                setup_detection.main()
            self.assertEqual(calls,['object.pt','hazard.pt'])
            self.assertFalse((weights/'yolo26n.pt').exists())

if __name__=='__main__':unittest.main()
