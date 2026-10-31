"""Build the native Java APK with official Android build-tools; no Gradle or pip dependencies."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def run(args):
    subprocess.run([str(value) for value in args], check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--android-jar', type=Path, required=True)
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--key', type=Path, default=None)
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    exe = '.exe' if os.name == 'nt' else ''
    java = args.java_home.resolve() / 'bin' / ('java' + exe)
    javac = java.with_name('javac' + exe)
    keytool = java.with_name('keytool' + exe)
    tools = args.build_tools.resolve()
    android = args.android_jar.resolve()
    for value in (java, javac, keytool, tools / ('aapt2' + exe), tools / ('zipalign' + exe),
                  tools / 'lib/d8.jar', tools / 'lib/apksigner.jar', android):
        if not value.is_file():
            raise SystemExit(f'Missing tool: {value}')
    stage = Path(tempfile.mkdtemp(prefix='build-', dir=work))
    classes, dex, generated = (stage / name for name in ('classes', 'dex', 'generated'))
    for folder in (classes, dex, generated):
        folder.mkdir(exist_ok=True)
    resources = stage / 'resources.zip'
    run([tools / ('aapt2' + exe), 'compile', '--dir', source / 'res', '-o', resources])
    base = stage / 'base.apk'
    run([tools / ('aapt2' + exe), 'link', '-I', android, '--manifest', source / 'AndroidManifest.xml',
         '-o', base, '--java', generated, '--min-sdk-version', '26', '--target-sdk-version', '35', resources])
    sources = sorted((source / 'src').rglob('*.java')) + sorted(generated.rglob('*.java'))
    bootclasspath = str(android) + os.pathsep + str(tools / 'core-lambda-stubs.jar')
    run([javac, '-J-Duser.language=en', '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-bootclasspath', bootclasspath,
         '-d', classes, *sources])
    classfiles = sorted(classes.rglob('*.class'))
    run([java, '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--lib', android,
         '--min-api', '26', '--output', dex, *classfiles])
    unsigned = stage / 'unsigned.apk'
    shutil.copyfile(base, unsigned)
    with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(dex.glob('*.dex')):
            archive.write(path, path.name)
    aligned = stage / 'aligned.apk'
    run([tools / ('zipalign' + exe), '-p', '-f', '4', unsigned, aligned])
    key = (args.key or work / 'local-test-signing.jks').resolve()
    if args.key and not key.exists():
        raise SystemExit(f'Missing key: {key}')
    if not key.exists():
        run([keytool, '-genkeypair', '-keystore', key, '-storetype', 'JKS', '-alias', 'localtest',
             '-storepass', 'local-test-only', '-keypass', 'local-test-only', '-dname',
             'CN=Local Profile Fill Test,OU=Local Development,O=Local,C=US',
             '-keyalg', 'RSA', '-keysize', '3072', '-validity', '3650', '-noprompt'])
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    run([java, '-jar', tools / 'lib/apksigner.jar', 'sign', '--ks', key, '--ks-key-alias', 'localtest',
         '--ks-pass', 'pass:local-test-only', '--key-pass', 'pass:local-test-only',
         '--v4-signing-enabled', 'false', '--out', output, aligned])
    run([java, '-jar', tools / 'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', output])
    run([tools / ('zipalign' + exe), '-c', '4', output])
    checksum = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix('.apk.sha256').write_text(f'{checksum}  {output.name}\n', encoding='utf-8')
    print(json.dumps({'apk': str(output), 'bytes': output.stat().st_size, 'sha256': checksum}, indent=2))


if __name__ == '__main__':
    main()
