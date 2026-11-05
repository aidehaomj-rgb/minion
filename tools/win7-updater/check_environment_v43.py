import importlib, sys, tempfile, pathlib, traceback, sqlite3
from contextlib import closing
print(sys.version)
failed=[]
for m in ['ssl','sqlite3','numpy','pandas','openpyxl','xlrd','matplotlib','PyPDF2','pdfminer','flask','requests','docx','pptx','cryptography','cffi','colorama','importlib_metadata','zipp']:
    try:
        x=importlib.import_module(m); print('OK import',m,getattr(x,'__version__',''))
    except Exception:
        failed.append(m); traceback.print_exc()
if not failed:
    try:
        import pandas as pd
        from docx import Document
        from pptx import Presentation
        from PyPDF2 import PdfReader
        from pdfminer.high_level import extract_text
        import matplotlib
        matplotlib.use('Agg')
        from matplotlib import pyplot as plt
        from flask import Flask
        from cryptography.fernet import Fernet
        key=Fernet.generate_key(); f=Fernet(key); assert f.decrypt(f.encrypt(b'check'))==b'check'
        with tempfile.TemporaryDirectory() as d:
            d=pathlib.Path(d)
            pd.DataFrame({'name':['test'],'value':[2]}).to_excel(str(d/'check.xlsx'),index=False)
            assert pd.read_excel(str(d/'check.xlsx')).iloc[0]['value']==2
            doc=Document();doc.add_paragraph('Chinese: \u4e2d\u6587');doc.save(str(d/'check.docx'))
            assert '\u4e2d\u6587' in Document(str(d/'check.docx')).paragraphs[0].text
            slides=Presentation();slide=slides.slides.add_slide(slides.slide_layouts[1]);slide.shapes.title.text='Check';slides.save(str(d/'check.pptx'))
            assert len(Presentation(str(d/'check.pptx')).slides)==1
            plt.figure();plt.plot([1,2],[3,4]);plt.title('Minion check');plt.savefig(str(d/'check.pdf'));plt.savefig(str(d/'check.png'));plt.close()
            assert len(PdfReader(str(d/'check.pdf')).pages)==1
            assert 'Minion' in extract_text(str(d/'check.pdf'))
            # sqlite3's connection context commits/rolls back but does not close.
            # Explicit closure is required before Windows deletes the temp folder.
            with closing(sqlite3.connect(str(d/'check.db'))) as db:
                db.execute('create table t(n integer)');db.execute('insert into t values(7)');assert db.execute('select n from t').fetchone()[0]==7
        app=Flask('minion_check')
        app.add_url_rule('/check','check',lambda:'OK')
        assert app.test_client().get('/check').data==b'OK'
        print('PASS: Excel, Word, PPT, PDF, chart, SQLite, Flask and encryption smoke checks')
    except Exception:
        failed.append('functional smoke checks');traceback.print_exc()
else:
    print('Functional checks skipped because imports failed.')
if failed:print('FAIL:', ', '.join(failed))
sys.exit(1 if failed else 0)
