#!/usr/bin/env python3
"""Vraie panne sur des instances isolées ; ne remplace jamais les JAR déjà utilisés."""
import argparse
import json
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--maven-repo", help="Cache Maven facultatif, sans purge")
options = parser.parse_args()


def call(method, url, body=None, key=None):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Idempotency-Key"] = key
    request = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(),
                                     headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as response:
        return response.code, json.load(response)


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    for port in (18081, 18082, 18083):
        with socket.socket() as probe:
            try:
                probe.bind(("127.0.0.1", port))
            except OSError as error:
                subprocess.run(["ss", "-ltnp", f"sport = :{port}"], check=False)
                raise RuntimeError(f"Port {port} occupé : aucun processus arrêté") from error

    with tempfile.TemporaryDirectory(prefix="adria-resilience-") as directory:
        folder = Path(directory)
        source = folder / "source"
        source.mkdir()
        for name in ("pom.xml", "mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties"):
            destination = source / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / name, destination)
        for module in ("account-service", "transaction-service", "discovery-service", "config-service", "gateway-service"):
            (source / module).mkdir()
            shutil.copy2(ROOT / module / "pom.xml", source / module / "pom.xml")
            shutil.copytree(ROOT / module / "src", source / module / "src")
        build = [str(source / "mvnw"), "-B", "-ntp"]
        if options.maven_repo:
            build.append("-Dmaven.repo.local=" + str(Path(options.maven_repo).resolve()))
        build += ["clean", "package"]
        print("Tests et compilation dans une copie temporaire ; services existants inchangés.", flush=True)
        with (folder / "build.log").open("w") as output:
            result = subprocess.run(build, cwd=source, stdout=output, stderr=subprocess.STDOUT)
        log = (folder / "build.log").read_text()
        for line in log.splitlines():
            if "Tests run:" in line or "BUILD SUCCESS" in line:
                print(line, flush=True)
        if result.returncode:
            print(log)
            raise RuntimeError("Compilation ou tests échoués : démonstration annulée")

        processes = []

        def stop(process):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()

        def start(module, port, extra=()):
            output = (folder / (module + ".log")).open("a")
            jar = source / module / "target" / (module + "-0.0.1-SNAPSHOT.jar")
            process = subprocess.Popen(["java", "-jar", str(jar), "--server.address=127.0.0.1",
                f"--server.port={port}", f"--spring.datasource.url=jdbc:h2:file:{folder / module};DB_CLOSE_ON_EXIT=FALSE",
                *extra], cwd=source, stdout=output, stderr=subprocess.STDOUT)
            processes.append((process, output))
            for _ in range(150):
                check(process.poll() is None, (folder / (module + ".log")).read_text())
                try:
                    code, body = call("GET", f"http://127.0.0.1:{port}/actuator/health")
                    if code == 200 and body.get("status") == "UP":
                        return process
                except OSError:
                    pass
                time.sleep(0.2)
            raise RuntimeError("Démarrage trop lent : " + module)

        state = {"drop_success": True, "calls": 0}

        class Proxy(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                state["calls"] += 1
                request = urllib.request.Request("http://127.0.0.1:18081" + self.path,
                    data=self.rfile.read(int(self.headers["Content-Length"])), method="POST",
                    headers={"Content-Type": "application/json"})
                try:
                    with urllib.request.urlopen(request, timeout=5) as response:
                        code, body = response.status, response.read()
                except urllib.error.HTTPError as response:
                    code, body = response.code, response.read()
                except OSError:
                    code, body = 503, b"{}"
                if code == 200 and state["drop_success"]:
                    # Mouvement réellement committé ; sa réponse de succès est perdue.
                    code, body = 503, b"{}"
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(body)

        proxy = ThreadingHTTPServer(("127.0.0.1", 18083), Proxy)
        threading.Thread(target=proxy.serve_forever, daemon=True).start()
        account_url, transaction_url = "http://127.0.0.1:18081", "http://127.0.0.1:18082"
        try:
            account = start("account-service", 18081)
            start("transaction-service", 18082, ("--bank.account-service.url=http://127.0.0.1:18083",
                "--bank.transaction.recovery.enabled=false"))
            code, source_account = call("POST", account_url + "/api/accounts",
                {"customerReference": "DEMO-CB-SOURCE", "currency": "MAD", "balance": 1500})
            check(code == 201, "Création source refusée")
            code, destination = call("POST", account_url + "/api/accounts",
                {"customerReference": "DEMO-CB-DESTINATION", "currency": "MAD", "balance": 200})
            check(code == 201, "Création destination refusée")
            debit = {"accountId": source_account["id"], "amount": 250, "currency": "MAD",
                     "merchantCategory": "SHOP", "country": "MA"}
            transfer = {"sourceAccountId": source_account["id"], "destinationAccountId": destination["id"],
                        "amount": 250, "currency": "MAD"}
            code, first_debit = call("POST", transaction_url + "/api/transactions", debit, "lost-debit")
            check(code == 202 and first_debit["status"] == "PENDING", "Débit ambigu attendu PENDING")
            code, first_transfer = call("POST", transaction_url + "/api/transfers", transfer, "lost-transfer")
            check(code == 202 and first_transfer["status"] == "PENDING", "Virement ambigu attendu PENDING")
            print("Deux réponses perdues après mouvements réels : débit et virement PENDING.", flush=True)
            for identifier in (source_account["id"], destination["id"]):
                code, _ = call("PATCH", account_url + "/api/accounts/" + identifier + "/status", {"status": "BLOCKED"})
                check(code == 200, "Blocage du compte de démonstration refusé")
            stop(account)
            print("Panne réelle : seule l'instance account-service de démonstration est arrêtée.", flush=True)
            for index in range(2):
                code, pending = call("POST", transaction_url + "/api/transactions", debit, "outage-" + str(index))
                check(code == 202 and pending["status"] == "PENDING", "Opération de panne attendue PENDING")
            check(state["calls"] == 4, "Nombre inattendu d'appels HTTP : retries ?")
            code, pending = call("POST", transaction_url + "/api/transactions", debit, "lost-debit")
            check(code == 202 and pending["id"] == first_debit["id"], "Reprise pendant ouverture incorrecte")
            check(state["calls"] == 4, "Un appel HTTP a traversé le circuit ouvert")
            print("Circuit OPEN : 4 échecs techniques, puis 0 appel HTTP supplémentaire.", flush=True)
            state["drop_success"] = False
            start("account-service", 18081)
            print("Service rétabli. Attente de 11 s (> 10 s configurées), puis 2 sondes HALF_OPEN.", flush=True)
            time.sleep(11)
            code, replay_debit = call("POST", transaction_url + "/api/transactions", debit, "lost-debit")
            check(code == 201 and replay_debit["id"] == first_debit["id"], "Reprise du débit incorrecte")
            code, replay_transfer = call("POST", transaction_url + "/api/transfers", transfer, "lost-transfer")
            check(code == 201 and replay_transfer["id"] == first_transfer["id"], "Reprise du virement incorrecte")
            check(state["calls"] == 6, "Les deux sondes doivent produire exactement deux appels HTTP")
            for index in range(6):
                code, error = call("POST", transaction_url + "/api/transactions", debit, "blocked-" + str(index))
                check(code == 400 and error.get("code") == "ACCOUNT_BLOCKED", "Refus métier incorrect")
            for identifier, expected in ((source_account["id"], "1000"), (destination["id"], "450")):
                code, data = call("GET", account_url + "/api/accounts/" + identifier)
                check(code == 200 and Decimal(str(data["balance"])) == Decimal(expected), "Double mouvement détecté")
                check(data["status"] == "BLOCKED", "Le rejeu ne doit pas changer le statut du compte")
            text = (folder / "transaction-service.log").read_text()
            for transition in ("from CLOSED to OPEN", "from OPEN to HALF_OPEN", "from HALF_OPEN to CLOSED"):
                check(transition in text, "Transition absente des logs : " + transition)
            check(text.count("from CLOSED to OPEN") == 1, "Les refus métier ont rouvert le circuit")
            for line in text.splitlines():
                if "Circuit accountService :" in line:
                    print(line, flush=True)
            print("SUCCÈS : CLOSED retrouvé ; 6 refus métier sans ouverture ; mêmes operationId ; soldes 1000 / 450.", flush=True)
        finally:
            for process, output in reversed(processes):
                stop(process)
                output.close()
            proxy.shutdown()
            proxy.server_close()
        print("Instances et bases temporaires supprimées ; services existants préservés.", flush=True)


if __name__ == "__main__":
    main()
