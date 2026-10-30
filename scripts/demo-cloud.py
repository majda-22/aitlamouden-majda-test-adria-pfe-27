#!/usr/bin/env python3
"""Partie D : tests Maven complets puis découverte, configuration, routage et panne sur instances isolées."""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODULES = ['account-service', 'transaction-service', 'discovery-service', 'config-service', 'gateway-service']
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--maven-repo', help='Cache Maven facultatif, sans purge')
options = parser.parse_args()


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def call(method, url, body=None, key=None):
    headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}
    if key:
        headers['Idempotency-Key'] = key
    request = urllib.request.Request(url, method=method, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            status, text = response.status, response.read().decode()
    except urllib.error.HTTPError as response:
        status, text = response.code, response.read().decode()
    try:
        result = json.loads(text)
    except ValueError:
        result = text
    print(method, url, 'HTTP', status, json.dumps(result, ensure_ascii=False), flush=True)
    return status, result


def main():
    for port in [18761, 18888, 18081, 18082, 18080]:
        with socket.socket() as probe:
            try:
                probe.bind(('127.0.0.1', port))
            except OSError as error:
                subprocess.run(['ss', '-ltnp', f'sport = :{port}'], check=False)
                raise RuntimeError(f'Port {port} occupé : aucun processus arrêté') from error
    with tempfile.TemporaryDirectory(prefix='adria-cloud-') as temp:
        folder = Path(temp)
        source = folder / 'source'
        source.mkdir()
        for name in ['pom.xml', 'mvnw', 'mvnw.cmd', '.mvn/wrapper/maven-wrapper.properties']:
            target = source / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / name, target)
        for module in MODULES:
            (source / module).mkdir()
            shutil.copy2(ROOT / module / 'pom.xml', source / module / 'pom.xml')
            shutil.copytree(ROOT / module / 'src', source / module / 'src')
        command = [str(source / 'mvnw'), '-B', '-ntp']
        if options.maven_repo:
            command.append('-Dmaven.repo.local=' + str(Path(options.maven_repo).resolve()))
        command += ['clean', 'package']
        print('Tests de tous les modules et compilation dans une copie temporaire.', flush=True)
        with (folder / 'build.log').open('w') as output:
            result = subprocess.run(command, cwd=source, stdout=output, stderr=subprocess.STDOUT)
        log = (folder / 'build.log').read_text()
        for line in log.splitlines():
            if 'Tests run:' in line or 'BUILD SUCCESS' in line:
                print(line, flush=True)
        if result.returncode:
            print(log)
            raise RuntimeError('Tests ou compilation échoués')
        processes = []
        env = {**os.environ, 'EUREKA_URL': 'http://localhost:18761/eureka/', 'CONFIG_SERVER_URL': 'http://localhost:18888'}

        def stop(process):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()

        def start(module, port, extra=()):
            output = (folder / (module + '.log')).open('a')
            args = ['java', '-jar', str(source / module / 'target' / (module + '-0.0.1-SNAPSHOT.jar')),
                f'--server.port={port}', '--server.address=127.0.0.1',
                '--eureka.instance.ip-address=127.0.0.1', '--eureka.instance.hostname=localhost',
                '--eureka.instance.initial-status=UP', '--eureka.client.registry-fetch-interval-seconds=1',
                '--eureka.client.initial-instance-info-replication-interval-seconds=1',
                '--eureka.instance.lease-renewal-interval-in-seconds=2', '--spring.cloud.loadbalancer.cache.ttl=1s', *extra]
            process = subprocess.Popen(args, cwd=source, env=env, stdout=output, stderr=subprocess.STDOUT)
            processes.append((process, output))
            for _ in range(250):
                check(process.poll() is None, (folder / (module + '.log')).read_text())
                try:
                    with urllib.request.urlopen(f'http://localhost:{port}/actuator/health', timeout=2) as response:
                        if response.status == 200 and json.load(response).get('status') == 'UP':
                            print(module, 'HTTP 200 / UP sur', port, flush=True)
                            return process
                except OSError:
                    pass
                time.sleep(.2)
            raise RuntimeError('Démarrage trop lent : ' + module)

        def registered(name):
            url = 'http://localhost:18761/eureka/apps/' + name.upper()
            for _ in range(90):
                try:
                    request = urllib.request.Request(url, headers={'Accept': 'application/json'})
                    with urllib.request.urlopen(request, timeout=2) as response:
                        body = json.load(response)
                        instances = body.get('application', {}).get('instance', [])
                        if isinstance(instances, dict):
                            instances = [instances]
                        if any(instance.get('status') == 'UP' for instance in instances):
                            print('Eureka', name, 'UP', flush=True)
                            return
                except OSError:
                    pass
                time.sleep(1)
            raise RuntimeError('Service absent du registre : ' + name)

        try:
            start('discovery-service', 18761, ('--eureka.server.use-read-only-response-cache=false',))
            start('config-service', 18888)
            status, config = call('GET', 'http://localhost:18888/transaction-service/cloud')
            check(status == 200 and any(str(p.get('source', {}).get('bank.transaction.max-amount')) == '1000'
                  for p in config['propertySources']), 'Configuration partagée absente')
            account = start('account-service', 18081, ('--spring.profiles.active=cloud',
                f'--spring.datasource.url=jdbc:h2:file:{folder / "accounts"};DB_CLOSE_ON_EXIT=FALSE'))
            start('transaction-service', 18082, ('--spring.profiles.active=cloud',
                f'--spring.datasource.url=jdbc:h2:file:{folder / "transactions"};DB_CLOSE_ON_EXIT=FALSE',
                '--bank.transaction.recovery.enabled=false'))
            registered('account-service')
            registered('transaction-service')
            gateway = 'http://localhost:18080'
            start('gateway-service', 18080, ('--spring.profiles.active=cloud',))
            registered('gateway-service')
            # Attendre le cache client Eureka de la Gateway, sans effectuer de mutation.
            for _ in range(60):
                status, _ = call('GET', gateway + '/api/accounts')
                if status == 200:
                    break
                time.sleep(1)
            check(status == 200, 'Routage Eureka non disponible')
            status, source_account = call('POST', gateway + '/api/accounts', {'customerReference': 'CLOUD-DEMO-SOURCE', 'currency': 'MAD', 'balance': 1500})
            check(status == 201, 'Création source refusée')
            status, destination = call('POST', gateway + '/api/accounts', {'customerReference': 'CLOUD-DEMO-DESTINATION', 'currency': 'MAD', 'balance': 200})
            check(status == 201, 'Création destination refusée')
            debit = {'accountId': source_account['id'], 'amount': 250, 'currency': 'MAD', 'merchantCategory': 'SHOP', 'country': 'MA'}
            status, transaction = call('POST', gateway + '/api/transactions', debit, 'cloud-debit')
            check(status == 201 and transaction.get('status') == 'COMPLETED', 'Débit via découverte Feign non confirmé')
            transfer = {'sourceAccountId': source_account['id'], 'destinationAccountId': destination['id'], 'amount': 250, 'currency': 'MAD'}
            status, movement = call('POST', gateway + '/api/transfers', transfer, 'cloud-transfer')
            check(status == 201 and movement.get('status') == 'COMPLETED', 'Virement via Gateway échoué')
            status, replay = call('POST', gateway + '/api/transactions', debit, 'cloud-debit')
            check(status == 201 and replay['id'] == transaction['id'], 'Rejeu incorrect')
            status, above = call('POST', gateway + '/api/transactions', {**debit, 'amount': 1001}, 'ceiling')
            check(status == 400 and above.get('code') == 'MAX_AMOUNT_EXCEEDED', 'Plafond non appliqué')
            for prefix, query in [('accounts', '{ accounts { content { id balance } } }'),
                    ('transactions', '{ transactions(accountId: "' + source_account['id'] + '") { totalElements } }')]:
                status, body = call('POST', gateway + '/' + prefix + '/graphql', {'query': query})
                check(status == 200 and not body.get('errors'), 'GraphQL non préservé')
            for path in ['/internal/debits', '/internal/transfers', '/accounts/internal/debits', '/account-service/internal/debits']:
                status, _ = call('POST', gateway + path, {})
                check(status == 404, 'Opération interne exposée')
            libs = folder / 'client-libs'
            libs.mkdir()
            with zipfile.ZipFile(source / 'transaction-service/target/transaction-service-0.0.1-SNAPSHOT.jar') as archive:
                for name in archive.namelist():
                    if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                        (libs / Path(name).name).write_bytes(archive.read(name))
            subprocess.run(['java', '-cp', str(libs / '*'), str(ROOT / 'scripts/CloudMcpProbe.java'),
                gateway, source_account['id'], transaction['id']], check=True)
            for identifier, expected in [(source_account['id'], 1000), (destination['id'], 450)]:
                status, body = call('GET', gateway + '/api/accounts/' + identifier)
                check(status == 200 and body['balance'] == expected, 'Double mouvement détecté')
            stop(account)
            status, pending = call('POST', gateway + '/api/transactions', {**debit, 'amount': 1}, 'outage')
            check(status == 202 and pending.get('status') == 'PENDING', 'La panne a annoncé une réussite bancaire')
            status, failure = call('POST', gateway + '/api/accounts', {'customerReference': 'UNREACHABLE', 'currency': 'MAD', 'balance': 0})
            check(status == 503, 'Le routage vers le service arrêté doit échouer')
            account = start('account-service', 18081, ('--spring.profiles.active=cloud',
                f'--spring.datasource.url=jdbc:h2:file:{folder / "accounts"};DB_CLOSE_ON_EXIT=FALSE'))
            registered('account-service')
            for _ in range(30):
                status, recovered = call('POST', gateway + '/api/transactions', {**debit, 'amount': 1}, 'outage')
                if status == 201:
                    break
                time.sleep(1)
            check(status == 201 and recovered['id'] == pending['id'] and recovered['status'] == 'COMPLETED', 'Reprise non confirmée')
            print('SUCCÈS PARTIE D : Config 1000, registre Eureka, Feign découvert, Gateway REST/GraphQL/MCP, blocage interne et panne/reprise vérifiés.', flush=True)
        finally:
            for process, output in reversed(processes):
                stop(process)
                output.close()
        print('Instances isolées arrêtées, bases temporaires supprimées ; services existants préservés.', flush=True)


if __name__ == '__main__':
    main()
