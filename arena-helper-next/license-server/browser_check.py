import sys,tempfile,threading
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from server import Store,create_app
from werkzeug.serving import make_server
from playwright.sync_api import sync_playwright
with tempfile.TemporaryDirectory() as d:
 app=create_app(Store(Path(d)/'licenses.sqlite'));app.extensions['arena_admin'].configure('temporary-test-password')
 server=make_server('127.0.0.1',8899,app,ssl_context='adhoc');threading.Thread(target=server.serve_forever,daemon=True).start()
 try:
  with sync_playwright() as p:
   browser=p.chromium.launch(headless=True)
   context=browser.new_context(ignore_https_errors=True,viewport={'width':1280,'height':900},permissions=['clipboard-read','clipboard-write'])
   page=context.new_page();page.goto('https://127.0.0.1:8899/admin')
   page.get_by_label('Пароль панели').fill('temporary-test-password');page.get_by_role('button',name='Войти',exact=True).click()
   assert page.get_by_label('Срок лицензии').locator('option').count()==4
   page.get_by_label('Срок лицензии').select_option('7d')
   page.get_by_label('Покупатель или номер заказа').fill('Заказ 1001');page.get_by_role('button',name='Создать ключ').click()
   assert '7 дней' in page.locator('tbody').inner_text()
   assert 'Ожидает первой активации' in page.locator('tbody').inner_text()
   assert page.locator('#key').input_value().startswith('AH-')
   page.get_by_role('button',name='Скопировать ключ').click()
   page.get_by_role('button',name='Скопировано',exact=True).wait_for()
   assert page.evaluate('navigator.clipboard.readText()')==page.locator('#key').input_value()
   page.screenshot(path='admin-desktop.png',full_page=True)
   page.set_viewport_size({'width':390,'height':844});page.screenshot(path='admin-mobile.png',full_page=True)
   assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
   page.get_by_role('button',name='Выйти',exact=True).click();page.get_by_label('Пароль панели').wait_for()
   print('Browser login, issue, copy button, mobile rendering and logout passed')
   browser.close()
 finally:server.shutdown()
