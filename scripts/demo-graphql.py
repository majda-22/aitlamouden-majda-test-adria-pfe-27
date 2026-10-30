#!/usr/bin/env python3
"""Exécute et affiche les requêtes curl GraphQL sur deux instances de démonstration."""
import argparse
import json
import shlex
import subprocess
import uuid
from decimal import Decimal

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--account-url", default="http://localhost:8081")
parser.add_argument("--transaction-url", default="http://localhost:8082")
args = parser.parse_args()


def graph(base, query, variables=None, expected_error=None):
    request = json.dumps({"query": query, "variables": variables or {}}, separators=(",", ":"))
    command = ["curl", "--silent", "--show-error", "--max-time", "10", "--write-out", "\n%{http_code}",
               "--request", "POST", base + "/graphql", "--header", "Content-Type: application/json",
               "--header", "Accept: application/json", "--data", request]
    print("$ " + shlex.join(command), flush=True)
    result = subprocess.run(command, check=True, text=True, capture_output=True)
    body, status = result.stdout.rsplit("\n", 1)
    print("HTTP " + status + " " + body, flush=True)
    if status != "200":
        raise RuntimeError("Statut HTTP inattendu")
    response = json.loads(body)
    if expected_error:
        if not any(error.get("extensions", {}).get("code") == expected_error for error in response.get("errors", [])):
            raise RuntimeError("Code GraphQL attendu : " + expected_error)
    elif response.get("errors"):
        raise RuntimeError("Erreurs GraphQL dans une requête attendue réussie")
    return response.get("data")


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


token = str(uuid.uuid4())
create_account = "mutation($input: CreateAccountInput!) { createAccount(input: $input) { id status currency balance } }"
source = graph(args.account_url, create_account, {"input": {
    "customerReference": "GRAPHQL-SOURCE-" + token, "currency": "MAD", "balance": "1500.000000000000000001"}})["createAccount"]
destination = graph(args.account_url, create_account, {"input": {
    "customerReference": "GRAPHQL-DESTINATION-" + token, "currency": "MAD", "balance": "200"}})["createAccount"]
check(source["status"] == destination["status"] == "ACTIVE", "Statuts initiaux incorrects")

create_transaction = "mutation($input: CreateTransactionInput!, $key: String!) { createTransaction(input: $input, idempotencyKey: $key) { id amount createdAt status } }"
debit_variables = {"key": "graphql-debit-" + token, "input": {
    "accountId": source["id"], "amount": "250.00", "currency": "MAD", "merchantCategory": "SHOP", "country": "MA"}}
debit = graph(args.transaction_url, create_transaction, debit_variables)["createTransaction"]
check(debit["status"] == "COMPLETED" and debit["createdAt"].endswith("Z"), "Débit non confirmé ou Instant incorrect")
replay = graph(args.transaction_url, create_transaction, debit_variables)["createTransaction"]
check(replay["id"] == debit["id"], "Identifiant différent au rejeu du débit")

create_transfer = "mutation($input: CreateTransferInput!, $key: String!) { createTransfer(input: $input, idempotencyKey: $key) { id sourceAccountId destinationAccountId amount status } }"
transfer_variables = {"key": "graphql-transfer-" + token, "input": {
    "sourceAccountId": source["id"], "destinationAccountId": destination["id"], "amount": "250", "currency": "MAD"}}
transfer = graph(args.transaction_url, create_transfer, transfer_variables)["createTransfer"]
check(transfer["status"] == "COMPLETED", "Virement non confirmé")
check(graph(args.transaction_url, create_transfer, transfer_variables)["createTransfer"]["id"] == transfer["id"], "Double virement")

graph(args.transaction_url, "query($id: ID!) { transaction(id: $id) { id amount createdAt status } }", {"id": debit["id"]})
graph(args.transaction_url, "query($id: ID!) { transfer(id: $id) { id sourceAccountId destinationAccountId status } }", {"id": transfer["id"]})
page = graph(args.transaction_url, "query($id: ID!) { transactions(accountId: $id) { page size totalElements content { id } } }", {"id": source["id"]})["transactions"]
check(page["size"] == 10 and page["totalElements"] == 1, "Pagination des débits incorrecte")
graph(args.account_url, "{ accounts(page: 0, size: 10) { page size totalElements content { id } } }")

for mutation, variables in ((create_transaction, debit_variables), (create_transfer, transfer_variables)):
    conflict = {**variables, "input": {**variables["input"], "amount": "251"}}
    graph(args.transaction_url, mutation, conflict, expected_error="IDEMPOTENCY_CONFLICT")
graph(args.account_url, "query($id: ID!) { account(id: $id) { id } }", {"id": str(uuid.uuid4())}, expected_error="ACCOUNT_NOT_FOUND")
graph(args.account_url, create_account, {"input": {"customerReference": "INVALID", "currency": "MAD", "balance": 1.5}}, expected_error="INVALID_INPUT")
graph(args.account_url, "mutation($id: ID!) { updateAccountStatus(id: $id, status: BLOCKED) { id status } }", {"id": destination["id"]})
graph(args.transaction_url, create_transfer, {**transfer_variables, "key": "refused-" + token}, expected_error="ACCOUNT_BLOCKED")
# Le rejeu du virement committé reste valide après blocage de sa destination.
check(graph(args.transaction_url, create_transfer, transfer_variables)["createTransfer"]["id"] == transfer["id"], "Rejeu du résultat antérieur refusé")
query = "query($id: ID!) { account(id: $id) { id status balance } }"
source_after = graph(args.account_url, query, {"id": source["id"]})["account"]
destination_after = graph(args.account_url, query, {"id": destination["id"]})["account"]
check(Decimal(source_after["balance"]) == Decimal("1000.000000000000000001"), "Précision perdue ou double débit")
check(Decimal(destination_after["balance"]) == Decimal("450"), "Double crédit")
print("SUCCÈS : GraphQL, erreurs structurées, pagination, précision décimale et idempotence vérifiées.", flush=True)
