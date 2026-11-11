#!/usr/bin/env python3
"""Host-only core integration: no provider secret, subscription or model inference."""
import argparse,json,os,secrets,socket,subprocess,tempfile,threading,time,urllib.request,urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1',0)); return s.getsockname()[1]

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): return None

http=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
def request(base,path,method='GET',body=None,headers=None):
    data=None if body is None else json.dumps(body).encode()
    h={'Accept':'application/json'};h.update(headers or {})
    if data is not None: h['Content-Type']='application/json'
    req=urllib.request.Request(base+path,data=data,headers=h,method=method)
    try:
        with http.open(req,timeout=5) as r: return r.status,r.read(65537)
    except urllib.error.HTTPError as e: return e.code,e.read(65537)

def is_listening(p):
    try:
        with socket.create_connection(('127.0.0.1',p),timeout=.2): return True
    except OSError: return False

args=argparse.ArgumentParser();args.add_argument('--exe',required=True);opts=args.parse_args()
exe=Path(opts.exe).resolve();assert exe.is_file()
with tempfile.TemporaryDirectory(prefix='magpie-mobile-smoke-') as temporary:
    root=Path(temporary)
    for directory in ['config','cache','tmp']: (root/directory).mkdir()
    vendor_calls={'get':0,'post':0}
    vendor_key='synthetic-provider-key-never-real'
    class Vendor(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_GET(self):
            vendor_calls['get']+=1
            if self.headers.get('Authorization')!='Bearer '+vendor_key:
                self.send_response(401);self.end_headers();return
            if self.path not in ['/models','/v1/models']:
                self.send_response(404);self.end_headers();return
            body=json.dumps({'data':[{'id':'probe-model','name':'Probe model'}]}).encode()
            self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        def do_POST(self):
            vendor_calls['post']+=1
            length=int(self.headers.get('Content-Length') or 0)
            raw=self.rfile.read(length)
            try: parsed=json.loads(raw)
            except ValueError: parsed={}
            vendor_calls.setdefault('chat_paths',[]).append(self.path)
            vendor_calls.setdefault('chat_models',[]).append(parsed.get('model'))
            body=json.dumps({'id':'chatcmpl-test','object':'chat.completion','model':parsed.get('model'),
                'choices':[{'index':0,'message':{'role':'assistant','content':'OK'},'finish_reason':'stop'}]}).encode()
            self.send_response(200);self.send_header('Content-Type','application/json')
            self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    vendor=ThreadingHTTPServer(('127.0.0.1',0),Vendor)
    threading.Thread(target=vendor.serve_forever,daemon=True).start()
    vendor_base='http://127.0.0.1:'+str(vendor.server_port)
    admin_port,gateway_port=port(),port()
    while admin_port==gateway_port: gateway_port=port()
    admin_key=secrets.token_hex(32)
    env={'PATH':os.environ['PATH'],'HOME':str(root),'XDG_CONFIG_HOME':str(root/'config'),
         'XDG_CACHE_HOME':str(root/'cache'),'TMPDIR':str(root/'tmp'),
         'MAGPIE_WEB_KEY':admin_key,'MAGPIE_ADDR':'127.0.0.1:'+str(gateway_port),
         'MAGPIE_BUN':'/usr/bin/false','HTTP_PROXY':'http://127.0.0.1:9',
         'HTTPS_PROXY':'http://127.0.0.1:9','NO_PROXY':'127.0.0.1,localhost'}
    p=subprocess.Popen([str(exe),'serve','--web=127.0.0.1:'+str(admin_port)],env=env,
                       cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    tail=[]
    def drain():
        for line in iter(p.stdout.readline,b''):
            tail.append(line.decode(errors='replace'));tail[:]=tail[-30:]
    t=threading.Thread(target=drain,daemon=True);t.start()
    admin='http://127.0.0.1:'+str(admin_port);gateway='http://127.0.0.1:'+str(gateway_port)
    cookie={'Cookie':'magpie_web_'+str(admin_port)+'='+admin_key}
    passed=False
    try:
        deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            if p.poll() is not None: raise AssertionError('core exited during startup with code '+str(p.returncode))
            try:
                code,body=request(admin,'/api/mobile/capabilities',headers=cookie)
                if code==200: break
            except (OSError,urllib.error.URLError): pass
            time.sleep(.1)
        else: raise AssertionError('authenticated management never became ready')
        cap=json.loads(body)
        assert cap=={'schemaVersion':1,'coreVersion':'0.1.3781-mobile.1','mobile':True,
                     'loopbackKeys':True,'debugEndpoints':False,'adminLoopbackOnly':True,'nativeAutoUpdate':False,
                     'providerSchemaVersion':1,'providerManagement':True,'providerModelDiscovery':True}
        assert request(admin,'/api/mobile/capabilities')[0]==401
        assert request(gateway,'/api/hello')[0]==401
        assert request(gateway,'/v1/models',headers={'Authorization':'Bearer magpie'})[0]==401
        before_code,before_body=request(admin,'/api/caller-keys',headers=cookie)
        assert before_code==200
        before_ids={key['id'] for key in json.loads(before_body)['keys']}
        code,body=request(admin,'/api/caller-keys/add-key','POST',{'name':'mobile-smoke-key'},cookie)
        assert code==200
        created=json.loads(body);secret=created['secret']
        assert secret.startswith('sk-magpie-key-') and len(secret.removeprefix('sk-magpie-key-'))==48
        own=[key for key in created['keys'] if key['name']=='mobile-smoke-key' and key['id'] not in before_ids]
        assert len(own)==1 and all('secret' not in key for key in created['keys'])
        own_id=own[0]['id']
        auth={'Authorization':'Bearer '+secret}
        code,body=request(gateway,'/api/hello',headers=auth)
        assert code==200 and json.loads(body)=={'name':'magpie','version':'0.1.3781-mobile.1'}
        assert request(gateway,'/v1/magpie/goroutines',headers=auth)[0]==404
        assert request(gateway,'/v1/magpie/heap',headers=auth)[0]==404
        assert request(admin,'/api/window/restart','POST',{},cookie)[0]==404
        code,body=request(admin,'/api/caller-keys',headers=cookie)
        listed=json.loads(body)['keys'];assert code==200 and all('secret' not in key for key in listed)
        assert len([key for key in listed if key['id']==own_id])==1
        code,body=request(admin,'/api/caller-keys/copy-key','POST',{'key':own_id},cookie)
        assert code==200 and json.loads(body)['secret']==secret
        # Safe provider persistence using synthetic credentials and a reserved local URL.
        # No provider/test/detect or authenticated chat is called here.
        code,body=request(admin,'/api/mobile/providers/models','POST',{'chat':vendor_base+'/v1','key':vendor_key},cookie)
        assert code==200 and json.loads(body)['models'][0]['id']=='probe-model'
        assert request(admin,'/api/mobile/providers/models','POST',{'chat':vendor_base+'/v1'},cookie)[0]==401
        draft={'new':True,'id':'host-provider','name':'Host provider','chat':vendor_base+'/v1',
               'key':vendor_key,'models':['probe-model'],'noModels':False}
        assert request(admin,'/api/mobile/providers/create','POST',draft)[0]==401
        code,body=request(admin,'/api/mobile/providers/create','POST',draft,cookie)
        assert code==200,'safe provider creation failed'
        provider=json.loads(body)
        assert provider['id']=='host-provider' and provider['key']['set'] and vendor_key not in body.decode()
        original_revision=provider['revision']
        code,body=request(admin,'/api/mobile/providers/models','POST',{'chat':vendor_base+'/v1/chat/completions','id':'host-provider','revision':original_revision},cookie)
        assert code==200 and json.loads(body)['models'][0]['id']=='probe-model'
        code,body=request(admin,'/api/mobile/providers/editable/host-provider',headers=cookie)
        assert code==200 and json.loads(body)['revision']==original_revision, 'discovery must not save or modify revision'
        assert vendor_calls['post']==0, 'metadata discovery cannot call inference'
        code,body=request(admin,'/api/mobile/providers/editable/host-provider',headers=cookie)
        assert code==200 and json.loads(body)['revision']==original_revision
        code,body=request(admin,'/api/mobile/providers/edit','POST',
                          {'id':'host-provider','revision':original_revision,'name':'Host edited'},cookie)
        assert code==200
        edited=json.loads(body)
        assert edited['name']=='Host edited' and edited['models']==['probe-model'] and edited['key']['set']
        assert request(admin,'/api/mobile/providers/edit','POST',
                       {'id':'host-provider','revision':original_revision,'name':'stale write'},cookie)[0]==409
        # Reproduce the real chat path end to end with a fake upstream: the gateway must
        # forward base+/chat/completions once and strip only the provider prefix.
        code,body=request(gateway,'/v1/models',headers={'Authorization':'Bearer '+secret})
        assert code==200,'gateway model catalog failed'
        catalog=[m['id'] for m in json.loads(body)['data']]
        target=next((i for i in catalog if 'probe-model' in i),'')
        assert target,'provider model is missing from the gateway catalog'
        code,body=request(gateway,'/v1/chat/completions','POST',
                          {'model':target,'messages':[{'role':'user','content':'hi'}],'max_tokens':8,'stream':False},
                          {'Authorization':'Bearer '+secret})
        assert code==200,'gateway chat failed with HTTP '+str(code)
        assert vendor_calls['chat_paths']==['/v1/chat/completions'],'unexpected upstream chat path'
        assert vendor_calls['chat_models']==['probe-model'],'unexpected upstream model name'
        assert request(admin,'/api/provider/test','POST',{'id':'host-provider'},cookie)[0]==404
        assert request(admin,'/api/provider/detect','POST',{'id':'host-provider'},cookie)[0]==404
        code,body=request(admin,'/api/mobile/providers/edit','POST',
                          {'id':'host-provider','revision':edited['revision'],'models':[],'noModels':True},cookie)
        assert code==200 and json.loads(body)['noModels'] is True and json.loads(body)['models']==[]
        code,body=request(admin,'/api/providers',headers=cookie)
        assert code==200 and vendor_key not in body.decode()
        time.sleep(.3)
        assert p.poll() is None,'core must survive the CLI printWeb path'
        assert admin_key not in ''.join(tail) and secret not in ''.join(tail),'credential leaked to startup output'
        p.terminate();p.wait(timeout=5);t.join(timeout=1)
        assert not is_listening(admin_port) and not is_listening(gateway_port),'orphan listener after stop'
        passed=True
        print('PASS: host serve startup, strict key/cookie auth, core-issued key/copy, safe provider create/edit/revision, debug denial, safe CLI output and stop; no inference')
    finally:
        vendor.shutdown();vendor.server_close()
        if p.poll() is None:
            p.kill();p.wait(timeout=5)
        if not passed:
            # Do not print raw startup output or response bodies which may contain credentials.
            print('Host smoke failed; diagnostics withheld to keep all credentials out of CI logs')
