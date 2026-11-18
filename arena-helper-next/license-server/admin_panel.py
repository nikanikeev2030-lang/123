"""Owner-only license dashboard. Credentials and encryption key stay in the data volume."""
import hashlib
import json
import os
import secrets
import tempfile
import time
from pathlib import Path
from datetime import datetime, timezone
from server import TERMS
from cryptography.fernet import Fernet
from werkzeug.security import generate_password_hash, check_password_hash

COOKIE = '__Host-arena-admin'


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def private_file(path, content, replace=False):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent,delete=False) as f:
        tmp=f.name;os.chmod(tmp,0o600);f.write(content);f.flush();os.fsync(f.fileno())
    try:
        if replace: os.replace(tmp,path)
        else:
            try: os.link(tmp,path)
            except FileExistsError: pass
    finally:
        if os.path.exists(tmp): os.unlink(tmp)


class Panel:
    def __init__(self, store):
        self.store=store;self.root=Path(store.path).parent
        self.config=self.root/'admin.json'
        private_file(self.root/'admin.key',Fernet.generate_key())
        self.cipher=Fernet((self.root/'admin.key').read_bytes())
        with store.connect() as db:
            db.executescript('''
              CREATE TABLE IF NOT EXISTS admin_sessions(token TEXT PRIMARY KEY, csrf TEXT, version TEXT, expires INTEGER);
              CREATE TABLE IF NOT EXISTS admin_attempts(ip TEXT, created INTEGER);
              CREATE TABLE IF NOT EXISTS admin_keys(hash TEXT PRIMARY KEY, secret TEXT, created INTEGER);
              CREATE TABLE IF NOT EXISTS admin_transfers(old_hash TEXT PRIMARY KEY, new_hash TEXT);
              CREATE TABLE IF NOT EXISTS admin_audit(created INTEGER, action TEXT, license_hash TEXT);
            ''')

    def configure(self,password):
        if not 12<=len(password)<=1024: raise ValueError('Use at least 12 characters')
        config={'password':generate_password_hash(password),'version':secrets.token_hex(16)}
        private_file(self.config,json.dumps(config).encode(),replace=True)
        with self.store.connect() as db: db.execute('DELETE FROM admin_sessions')

    def settings(self):
        if not self.config.exists(): return None
        return json.loads(self.config.read_text())

    def new_session(self, version=''):
        token=secrets.token_urlsafe(32);csrf=secrets.token_urlsafe(32)
        with self.store.connect() as db:
            db.execute('DELETE FROM admin_sessions WHERE expires<?',(int(time.time()),))
            db.execute('INSERT INTO admin_sessions VALUES(?,?,?,?)',(digest(token),csrf,version,int(time.time())+(28800 if version else 900)))
        return token

    def session(self,token):
        with self.store.connect() as db:
            return db.execute('SELECT csrf,version FROM admin_sessions WHERE token=? AND expires>?',(digest(token),int(time.time()))).fetchone()

    def login(self,ip,password,config):
        now=int(time.time())
        # Count attempts before hashing, shared across all gunicorn processes.
        with self.store.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            db.execute('DELETE FROM admin_attempts WHERE created<?',(now-600,))
            count=db.execute('SELECT count(*) FROM admin_attempts WHERE ip=?',(ip,)).fetchone()[0]
            if count>=10: return 'Слишком много попыток. Повторите через 10 минут.'
            db.execute('INSERT INTO admin_attempts VALUES(?,?)',(ip,now))
        if not check_password_hash(config['password'],password): return 'Неверный пароль.'
        with self.store.connect() as db: db.execute('DELETE FROM admin_attempts WHERE ip=?',(ip,))
        return None

    def create(self,db,label,term='1m',activated_at=None,expires_at=None):
        key='AH-'+secrets.token_hex(24).upper();ident=digest(key)
        db.execute('INSERT INTO licenses(hash,label) VALUES(?,?)',(ident,label))
        if term is not None:
            if term not in TERMS: raise ValueError('Выберите срок лицензии.')
            db.execute('INSERT INTO license_terms VALUES(?,?,?,?)',(ident,term,activated_at,expires_at))
        db.execute('INSERT INTO admin_keys VALUES(?,?,?)',(ident,self.cipher.encrypt(key.encode()).decode(),int(time.time())))
        db.execute('INSERT INTO admin_audit VALUES(?,?,?)',(int(time.time()),'issue',ident))
        return key

    def action(self,action,ident,label,term='1m'):
        with self.store.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if action=='issue':
                if not label or len(label)>120: raise ValueError('Введите метку покупателя: от 1 до 120 символов.')
                if term not in TERMS: raise ValueError('Выберите срок лицензии.')
                return self.create(db,label,term),'Новый ключ готов. Отправьте его только этому покупателю.'
            row=db.execute('SELECT label,revoked,lease_until FROM licenses WHERE hash=?',(ident,)).fetchone()
            if not row: raise ValueError('Лицензия не найдена.')
            if action=='show':
                secret=db.execute('SELECT secret FROM admin_keys WHERE hash=?',(ident,)).fetchone()
                if not secret: raise ValueError('Этот ключ создан через консоль. Его исходный код на сервере не сохранялся.')
                return self.cipher.decrypt(secret[0].encode()).decode(),'Код лицензии для выбранного покупателя.'
            if action=='revoke':
                db.execute('UPDATE licenses SET revoked=1 WHERE hash=?',(ident,))
                db.execute('INSERT INTO admin_audit VALUES(?,?,?)',(int(time.time()),'revoke',ident))
                return None,'Лицензия отозвана. Новые запросы приложения будут отклонены.'
            if action=='transfer':
                if db.execute('SELECT 1 FROM admin_transfers WHERE old_hash=?',(ident,)).fetchone():
                    raise ValueError('Замена уже создана. Найдите новую лицензию в списке.')
                duration=db.execute('SELECT term,activated_at,expires_at FROM license_terms WHERE hash=?',(ident,)).fetchone()
                if duration and duration[2] is not None and duration[2]<=int(time.time()):
                    raise ValueError('Срок лицензии истёк. Создайте новую лицензию.')
                db.execute('UPDATE licenses SET revoked=1 WHERE hash=?',(ident,))
                wait=max(0,row[2]+60-int(time.time()))
                if wait:
                    return None,f'Старый ключ отключён. Через {(wait+59)//60} мин. нажмите «Перенести» ещё раз: получите новый ключ для другого телефона.'
                key=self.create(db,row[0],*(duration if duration else (None,None,None)))
                db.execute('INSERT INTO admin_transfers VALUES(?,?)',(ident,digest(key)))
                db.execute('INSERT INTO admin_audit VALUES(?,?,?)',(int(time.time()),'transfer',ident))
                return key,'Перенос готов: передайте новый ключ покупателю. Старый ключ больше не работает.'
            raise ValueError('Неизвестное действие.')


def register_admin(app,store):
    from flask import request,render_template_string,make_response,redirect,abort
    from werkzeug.middleware.proxy_fix import ProxyFix
    panel=Panel(store)
    # The API port is private; Caddy is the single trusted public reverse proxy.
    app.wsgi_app=ProxyFix(app.wsgi_app,x_for=1,x_proto=1)
    app.extensions['arena_admin']=panel

    @app.after_request
    def headers(response):
        if request.path.startswith('/admin'):
            response.headers['Cache-Control']='no-store'
            response.headers['X-Frame-Options']='DENY'
            response.headers['X-Content-Type-Options']='nosniff'
            response.headers['Referrer-Policy']='no-referrer'
            response.headers['X-Robots-Tag']='noindex, nofollow'
            response.headers['Content-Security-Policy']="default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"
        return response

    @app.get('/admin/ui.js')
    def script():
        return """document.addEventListener('click', async e=>{if(e.target.id==='copy'){const field=document.getElementById('key');try{await navigator.clipboard.writeText(field.value);e.target.textContent='Скопировано';}catch(_){field.select();e.target.textContent='Выделено — нажмите Ctrl+C';}}});document.addEventListener('submit',e=>{const b=e.submitter;if(b&&b.dataset.confirm&&!confirm(b.dataset.confirm))e.preventDefault();});""",200,{'Content-Type':'application/javascript; charset=utf-8'}

    @app.route('/admin',methods=['GET','POST'])
    def admin():
        config=panel.settings()
        if not config:
            return 'Панель ещё не настроена. На VPS выполните: docker compose -p arena-android exec api python admin_panel.py setup',503
        token=request.cookies.get(COOKIE,'');session=panel.session(token)
        if not session:
            if request.method=='POST': abort(403)
            token=panel.new_session();session=panel.session(token)
        logged=bool(session[1]) and secrets.compare_digest(session[1],config['version'])
        message='';key=None;status=200
        if request.method=='POST':
            if not secrets.compare_digest(session[0],request.form.get('csrf','')): abort(403)
            action=request.form.get('action','')
            # Consume token before the mutation: refresh/double submit cannot issue twice.
            with store.connect() as db:
                changed=db.execute('UPDATE admin_sessions SET csrf=? WHERE token=? AND csrf=?',(secrets.token_urlsafe(32),digest(token),session[0])).rowcount
            if changed!=1: abort(403)
            if action=='login' and not logged:
                message=panel.login(request.remote_addr or '',request.form.get('password','')[:1024],config) or ''
                if not message:
                    with store.connect() as db: db.execute('DELETE FROM admin_sessions WHERE token=?',(digest(token),))
                    token=panel.new_session(config['version']);logged=True
                else: status=429 if 'попыток' in message else 401
            elif not logged: abort(403)
            elif action=='logout':
                with store.connect() as db: db.execute('DELETE FROM admin_sessions WHERE token=?',(digest(token),))
                response=redirect('/admin');response.delete_cookie(COOKIE,path='/',secure=True,httponly=True,samesite='Strict');return response
            else:
                try: key,message=panel.action(action,request.form.get('id',''),request.form.get('label','').strip(),request.form.get('term',''))
                except ValueError as error: message=str(error);status=400
        session=panel.session(token)
        rows=[];counts=(0,0,0);more=False
        query=request.args.get('q','')[:120]
        try: page=max(0,min(100000,int(request.args.get('page','0'))))
        except ValueError: page=0
        if logged:
            with store.connect() as db:
                counts=db.execute('SELECT count(*),coalesce(sum(device IS NOT NULL AND revoked=0 AND (d.expires_at IS NULL OR d.expires_at>?)),0),coalesce(sum(revoked=1),0) FROM licenses l LEFT JOIN license_terms d ON d.hash=l.hash',(int(time.time()),)).fetchone()
                rows=db.execute('SELECT l.hash,l.label,l.device,l.revoked,l.lease_until,k.hash IS NOT NULL,t.old_hash IS NOT NULL,d.term,d.activated_at,d.expires_at FROM licenses l LEFT JOIN admin_keys k ON k.hash=l.hash LEFT JOIN admin_transfers t ON t.old_hash=l.hash LEFT JOIN license_terms d ON d.hash=l.hash WHERE instr(lower(l.label),lower(?))>0 ORDER BY l.rowid DESC LIMIT 51 OFFSET ?',(query,page*50)).fetchall()
                more=len(rows)>50;rows=rows[:50]
        response=make_response(render_template_string(TEMPLATE,logged=logged,csrf=session[0],message=message,key=key,rows=rows,counts=counts,query=query,page=page,more=more,terms=TERMS,now=int(time.time()),format_date=lambda stamp: datetime.fromtimestamp(stamp,timezone.utc).strftime('%d.%m.%Y %H:%M UTC')),status)
        response.set_cookie(COOKIE,token,secure=True,httponly=True,samesite='Strict',path='/')
        return response


TEMPLATE='''<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Android · Лицензии</title>
<style>body{margin:0;background:#0d1724;color:#eaf0f6;font:16px system-ui,sans-serif}main{max-width:1100px;margin:auto;padding:28px 18px}h1{font-size:28px;margin-bottom:8px}p{line-height:1.5}.muted{color:#a5b7ca}section{background:#18283a;border:1px solid #30445a;padding:20px;border-radius:14px;margin:20px 0}input,button,select{font:inherit;border-radius:8px;padding:12px;border:1px solid #46627f;box-sizing:border-box}input,select{background:#0d1724;color:#fff;max-width:100%}button{background:#176d65;color:white;cursor:pointer}button.secondary{background:#293f56}button.danger{background:#733b49}.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center}.row input{flex:1;min-width:180px}.notice{border-left:4px solid #52c6ad;padding:14px;background:#1e3440;overflow-wrap:anywhere}table{border-collapse:collapse;width:100%;min-width:700px}td,th{text-align:left;padding:14px 8px;border-bottom:1px solid #30445a;vertical-align:top}td form{display:flex;gap:6px;flex-wrap:wrap}td button{font-size:13px;padding:8px}small{color:#a5b7ca}a{color:#8ae1ce}.scroll{overflow:auto}.badge{white-space:nowrap}header{display:flex;justify-content:space-between;gap:12px;align-items:center}.login{max-width:460px;margin:50px auto}.login input,.login button{width:100%;margin:8px 0}#key{width:100%;font-family:monospace;margin-bottom:12px}.stats{display:flex;gap:25px;flex-wrap:wrap}.stats b{font-size:26px;display:block}</style>
<main><header><div><h1>Лицензии Android</h1><div class="muted">Помощник на Арене Hearthstone</div></div>{% if logged %}<form method="post"><input type="hidden" name="csrf" value="{{csrf}}"><button class="secondary" name="action" value="logout">Выйти</button></form>{% endif %}</header>
{% if message %}<p class="notice" role="status">{{message}}</p>{% endif %}
{% if not logged %}<section class="login"><h2>Вход владельца</h2><form method="post"><input type="hidden" name="csrf" value="{{csrf}}"><label for="password">Пароль панели</label><input id="password" type="password" name="password" required maxlength="1024" autocomplete="current-password"><button name="action" value="login">Войти</button></form></section>
{% else %}
{% if key %}<section><h2>Ключ покупателя</h2><input id="key" readonly value="{{key}}" aria-label="Код лицензии"><button id="copy" type="button">Скопировать ключ</button><p class="muted">При первой активации ключ привяжется к одному телефону.</p></section>{% endif %}
<section class="stats"><div><b>{{counts[0]}}</b>Всего</div><div><b>{{counts[1]}}</b>Привязаны к телефону</div><div><b>{{counts[2]}}</b>Отозваны</div></section>
<section><h2>Новая лицензия</h2><form method="post" class="row"><input type="hidden" name="csrf" value="{{csrf}}"><input name="label" required maxlength="120" placeholder="Покупатель или номер заказа" aria-label="Покупатель или номер заказа"><select name="term" aria-label="Срок лицензии" required>{% for code,title in terms.items() %}<option value="{{code}}" {% if code=='1m' %}selected{% endif %}>{{title}}</option>{% endfor %}</select><button name="action" value="issue">Создать ключ</button></form><p class="muted">Срок начинается с первой активации. Месяцы — календарные.</p></section>
<section><h2>Покупатели</h2><form method="get" class="row"><input name="q" value="{{query}}" placeholder="Поиск по метке" aria-label="Поиск по метке"><button class="secondary">Найти</button></form><div class="scroll"><table><thead><tr><th>Покупатель</th><th>Статус</th><th>Срок</th><th>Действия</th></tr></thead><tbody>
{% for r in rows %}<tr><td>{{r[1]}}<br><small>ID {{r[0][:10]}}</small></td><td class="badge">{% if r[6] %}Заменена новым ключом{% elif r[3] %}Отозвана{% elif r[9] is not none and r[9]<=now %}Срок истёк{% elif r[2] %}Активирована{% else %}Не активирована{% endif %}</td><td>{{terms.get(r[7], "Бессрочная (ранее)")}}{% if r[9] is not none %}<br><small>До {{format_date(r[9])}}</small>{% elif r[7] %}<br><small>Ожидает первой активации</small>{% endif %}</td><td><form method="post"><input type="hidden" name="csrf" value="{{csrf}}"><input type="hidden" name="id" value="{{r[0]}}">{% if r[5] %}<button class="secondary" name="action" value="show">Показать ключ</button>{% else %}<small>Код создан ранее в консоли</small>{% endif %}{% if not r[3] %}<button class="danger" name="action" value="revoke" data-confirm="Отозвать лицензию? Приложение покупателя потеряет доступ.">Отозвать</button>{% endif %}{% if not r[6] %}<button class="secondary" name="action" value="transfer" data-confirm="Отключить старый ключ и подготовить новый для переноса? Ожидание — до 16 минут.">Перенести</button>{% endif %}</form></td></tr>{% else %}<tr><td colspan="4">Лицензий пока нет или ничего не найдено.</td></tr>{% endfor %}
</tbody></table></div><p>{% if page %}<a href="{{url_for('admin',q=query,page=page-1)}}">← Назад</a>{% endif %} · Страница {{page+1}} · {% if more %}<a href="{{url_for('admin',q=query,page=page+1)}}">Далее →</a>{% endif %}</p></section><p class="muted">Перенос отключает старый код и выдаёт новый после завершения прежнего разрешения, максимум через 16 минут. Перенос сохраняет первоначальную дату окончания. Для новых ключей доступен повторный просмотр.</p>
{% endif %}</main><script src="/admin/ui.js" defer></script></html>'''


if __name__=='__main__':
    import argparse,getpass
    from server import Store
    parser=argparse.ArgumentParser();parser.add_argument('command',choices=['setup'])
    parser.parse_args();os.umask(0o077)
    first=getpass.getpass('New admin password (12+ characters): ')
    second=getpass.getpass('Repeat password: ')
    if first!=second: raise SystemExit('Passwords do not match. Nothing changed.')
    if not 12<=len(first)<=1024: raise SystemExit('Use at least 12 characters. Nothing changed.')
    path=Path(os.environ.get('LICENSE_DB','/data/licenses.sqlite'));path.parent.mkdir(parents=True,exist_ok=True)
    Panel(Store(path)).configure(first)
    print('Admin password saved. Open https://45-91-53-108.sslip.io/admin')
