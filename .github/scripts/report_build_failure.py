"""Make compiler/lint failures visible in the public Actions summary."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

def escape(value):
    return str(value).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')

count = 0
for filename in ('wrapper.log', 'build.log'):
    path = Path(filename)
    if not path.exists():
        continue
    lines = path.read_text(errors='replace').splitlines()
    errors = [line for line in lines if re.search(r'(^e: |^FAILURE:|^\* What went wrong:|^> |^Execution failed|^Could not |^Error:)', line)]
    for line in errors[:30]:
        print('::error title=Gradle diagnostic::' + escape(line[:3000]))
        count += 1
    if not errors:
        print('::error title=Build log tail::' + escape('\n'.join(lines[-25:])[:6000]))
        count += 1
report = Path('app/build/reports/lint-results-debug.xml')
if report.exists():
    for issue in ET.parse(report).getroot().findall('issue'):
        if issue.get('severity') in ('Error', 'Fatal'):
            print('::error title=Android lint::' + escape(issue.get('id','') + ': ' + issue.get('message','')))
            count += 1
if not count:
    print('::error::Build stopped before Gradle logs were produced; inspect the failed setup step.')
