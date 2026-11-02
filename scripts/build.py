"""Reproducible SDK build without a Gradle daemon. Requires Python 3.9+ and JDK 21+."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
os.chdir(ROOT)

def run(command):
    subprocess.run([str(p) for p in command], check=True)

def archive(target, files):
    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as out:
        for name, path in sorted(files):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            out.writestr(info, path.read_bytes())

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
    args = parser.parse_args()
    suffix = '.exe' if os.name == 'nt' else ''
    javac = Path(args.java_home) / 'bin' / ('javac' + suffix) if args.java_home else shutil.which('javac')
    java = Path(args.java_home) / 'bin' / ('java' + suffix) if args.java_home else shutil.which('java')
    if not javac or not java:
        parser.error('Set JAVA_HOME or pass --java-home with a JDK 21+ installation.')
    dependencies = json.loads(Path('scripts/dependencies.lock.json').read_text())
    for dependency in dependencies:
        dest = Path(dependency['path'])
        if not dest.exists() or hashlib.sha256(dest.read_bytes()).hexdigest() != dependency['sha256']:
            dest.parent.mkdir(parents=True, exist_ok=True)
            print('Downloading', dest.name, flush=True)
            with urllib.request.urlopen(dependency['url'], timeout=180) as response:
                data = response.read()
            if hashlib.sha256(data).hexdigest() != dependency['sha256']:
                raise RuntimeError('Checksum mismatch: ' + dest.name)
            dest.write_bytes(data)
    # Clean only this script's generated class directories, after resolving against the workspace.
    for folder in ['build/classes/main', 'build/classes/test']:
        target = (ROOT / folder).resolve()
        if not target.is_relative_to(ROOT / 'build'):
            raise RuntimeError('Unsafe output path')
        if target.exists(): shutil.rmtree(target)
        target.mkdir(parents=True)
    compile_path = os.pathsep.join(d['path'] for d in dependencies)
    main_sources = sorted(Path('src/main/java').rglob('*.java'))
    run([javac, '-encoding', 'UTF-8', '--release', '17', '-cp', compile_path, '-d', 'build/classes/main', *main_sources])
    test_sources = sorted(Path('src/test/java').rglob('*.java'))
    run([javac, '-encoding', 'UTF-8', '--release', '17', '-cp', compile_path + os.pathsep + 'build/classes/main', '-d', 'build/classes/test', *test_sources])
    runtime = os.pathsep.join(['build/classes/main', 'build/classes/test', 'src/main/resources', '.cache/rhino-1.8.0.jar'])
    run([java, '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-cp', runtime, 'io.github.logcat2kt.EngineTest'])
    run([java, '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-cp', runtime, 'io.github.logcat2kt.PanelTest'])
    files = []
    for base in [Path('build/classes/main'), Path('src/main/resources')]:
        files.extend((p.relative_to(base).as_posix(), p) for p in base.rglob('*') if p.is_file())
    files.extend([('META-INF/LICENSE', Path('LICENSE')), ('META-INF/THIRD_PARTY_NOTICES.md', Path('THIRD_PARTY_NOTICES.md'))])
    version = ET.parse('src/main/resources/META-INF/plugin.xml').getroot().findtext('version')
    jar = Path(f'build/libs/logcat2kt-{version}.jar')
    archive(jar, files)
    output = Path(f'build/distributions/logcat2kt-{version}.zip')
    archive(output, [('logcat2kt/lib/' + jar.name, jar), ('logcat2kt/lib/rhino-1.8.0.jar', Path('.cache/rhino-1.8.0.jar'))])
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix('.zip.sha256').write_text(digest + '  ' + output.name + '\n', encoding='utf-8')
    print('Installable plugin:', output.resolve())
    print('SHA-256:', digest)

if __name__ == '__main__': main()
