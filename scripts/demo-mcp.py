#!/usr/bin/env python3
"""Teste, compile et démontre MCP avec le SDK client Java officiel sur des instances isolées."""
import argparse
import json
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--maven-repo', help='Cache Maven facultatif ; aucune purge')
options = parser.parse_args()


def main():
    for port in [18081, 18082]:
        with socket.socket() as probe:
            try:
                probe.bind(('127.0.0.1', port))
            except OSError:
                subprocess.run(['ss', '-ltnp', f'sport = :{port}'], check=False)
                raise RuntimeError(f'Port {port} occupé : aucun processus arrêté')
    with tempfile.TemporaryDirectory(prefix='adria-mcp-') as temp:
        folder = Path(temp)
        source = folder / 'source'
        source.mkdir()
        for name in ['pom.xml', 'mvnw', 'mvnw.cmd', '.mvn/wrapper/maven-wrapper.properties']:
            target = source / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / name, target)
        for module in ['account-service', 'transaction-service', 'discovery-service', 'config-service', 'gateway-service']:
            (source / module).mkdir()
            shutil.copy2(ROOT / module / 'pom.xml', source / module / 'pom.xml')
            shutil.copytree(ROOT / module / 'src', source / module / 'src')
        command = [str(source / 'mvnw'), '-B', '-ntp']
        if options.maven_repo:
            command.append('-Dmaven.repo.local=' + str(Path(options.maven_repo).resolve()))
        command += ['clean', 'package']
        print('Tests et compilation dans une copie temporaire ; JAR et données existants préservés.', flush=True)
        with (folder / 'build.log').open('w') as output:
            result = subprocess.run(command, cwd=source, stdout=output, stderr=subprocess.STDOUT)
        build_log = (folder / 'build.log').read_text()
        for line in build_log.splitlines():
            if 'Tests run:' in line or 'BUILD SUCCESS' in line:
                print(line, flush=True)
        if result.returncode:
            print(build_log)
            raise RuntimeError('Compilation ou tests échoués')
        # Le SDK client et son mapper proviennent des dépendances embarquées gérées par les BOM.
        libraries = folder / 'client-libs'
        libraries.mkdir()
        with zipfile.ZipFile(source / 'transaction-service/target/transaction-service-0.0.1-SNAPSHOT.jar') as archive:
            for name in archive.namelist():
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    (libraries / Path(name).name).write_bytes(archive.read(name))
        processes = []

        def start(module, port, extra=()):
            output = (folder / (module + '.log')).open('w')
            jar = source / module / 'target' / (module + '-0.0.1-SNAPSHOT.jar')
            process = subprocess.Popen(['java', '-jar', str(jar), '--server.address=127.0.0.1',
                f'--server.port={port}', f'--spring.datasource.url=jdbc:h2:file:{folder / module};DB_CLOSE_ON_EXIT=FALSE',
                *extra], cwd=source, stdout=output, stderr=subprocess.STDOUT)
            processes.append((process, output))
            for _ in range(200):
                if process.poll() is not None:
                    raise RuntimeError((folder / (module + '.log')).read_text())
                try:
                    with urllib.request.urlopen(f'http://localhost:{port}/actuator/health', timeout=1) as response:
                        if response.status == 200 and json.load(response)['status'] == 'UP':
                            return process
                except OSError:
                    pass
                time.sleep(.2)
            raise RuntimeError('Démarrage trop lent : ' + module)

        def stop(process):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()

        try:
            account = start('account-service', 18081)
            start('transaction-service', 18082, ('--bank.account-service.url=http://localhost:18081',
                '--bank.transaction.recovery.enabled=false'))
            command = ['java', '-cp', str(libraries / '*'), str(ROOT / 'scripts/McpDemo.java'),
                'http://localhost:18081', 'http://localhost:18082']
            demo = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            print(demo.stdout, flush=True)
            if demo.returncode:
                raise RuntimeError('Échec de la démonstration MCP')
            source_id = next(line.split('=', 1)[1] for line in demo.stdout.splitlines() if line.startswith('DEMO_SOURCE_ID='))
            stop(account)
            print('Panne réelle : seule instance account-service de démonstration arrêtée.', flush=True)
            outage = subprocess.run(command + ['outage', source_id], text=True,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            print(outage.stdout, flush=True)
            if outage.returncode:
                raise RuntimeError('Échec de la vérification MCP pendant la panne')
        finally:
            for process, output in reversed(processes):
                stop(process)
                output.close()
        print('Instances arrêtées, bases temporaires supprimées. Aucun ancien compte modifié.', flush=True)


if __name__ == '__main__':
    main()
