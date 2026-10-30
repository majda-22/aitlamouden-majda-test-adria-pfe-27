# Lancement local — partie D

Versions conservées : Java 17, Spring Boot 4.1.2, Spring Cloud 2025.1.4,
Spring AI 2.0.2. Le parent importe les BOM ; les modules ne forcent pas
les versions de Gateway, Eureka, Config, OpenFeign ou Resilience4J.

Références officielles :

- [Compatibilité Spring Cloud / Boot](https://spring.io/projects/spring-cloud/)
- [Config Server natif](https://docs.spring.io/spring-cloud-config/reference/server/environment-repository/file-system-backend.html)
- [Import Config Client](https://docs.spring.io/spring-cloud-config/reference/client.html)

## Responsabilités

- `discovery-service` (8761) : registre Eureka ; aucune donnée bancaire.
- `config-service` (8888) : dépôt natif embarqué `config-repo/application.properties`,
  qui partage `bank.transaction.max-amount=1000`.
- `gateway-service` (8080) : routage des seuls chemins publics vers les noms Eureka.
  Aucun fallback bancaire, aucun retry de mutation.
- Profil `cloud` des services métier : import Config Server obligatoire,
  inscription Eureka ; Feign utilise la découverte au lieu d'une URL fixe.
- Sans profil `cloud`, les deux services métier conservent leur lancement autonome.

## Commandes Ubuntu

Depuis la racine, arrêter toute instance utilisant un JAR avant de le reconstruire.
Pour identifier un port occupé :

```bash
ss -ltnp '( sport = :8080 or sport = :8081 or sport = :8082 or sport = :8761 or sport = :8888 )'
./mvnw clean package
```

Dans cinq terminaux distincts, depuis la même racine, lancer d'abord les deux serveurs :

```bash
java -jar discovery-service/target/discovery-service-0.0.1-SNAPSHOT.jar
java -jar config-service/target/config-service-0.0.1-SNAPSHOT.jar
```

Puis les clients :

```bash
java -jar account-service/target/account-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=cloud
java -jar transaction-service/target/transaction-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=cloud
java -jar gateway-service/target/gateway-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=cloud
```

Les adresses peuvent être remplacées par `EUREKA_URL` et `CONFIG_SERVER_URL`.
Le registre se met à jour de façon asynchrone ; attendre l'apparition des instances UP.

## URLs

| Interface | URL via Gateway | URL directe conservée |
|---|---|---|
| Comptes REST | `http://localhost:8080/api/accounts` | `http://localhost:8081/api/accounts` |
| Transactions REST | `http://localhost:8080/api/transactions` | `http://localhost:8082/api/transactions` |
| Virements REST | `http://localhost:8080/api/transfers` | `http://localhost:8082/api/transfers` |
| Comptes GraphQL | `http://localhost:8080/accounts/graphql` | `http://localhost:8081/graphql` |
| Transactions GraphQL | `http://localhost:8080/transactions/graphql` | `http://localhost:8082/graphql` |
| Comptes MCP Streamable HTTP | `http://localhost:8080/accounts/mcp` | `http://localhost:8081/mcp` |
| Transactions MCP Streamable HTTP | `http://localhost:8080/transactions/mcp` | `http://localhost:8082/mcp` |

```bash
curl -sS http://localhost:8888/transaction-service/cloud
curl -sS -H 'Accept: application/json' http://localhost:8761/eureka/apps
curl -i http://localhost:8080/api/accounts
curl -i -X POST http://localhost:8080/accounts/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query":"{ accounts { totalElements } }"}'
curl -i -X POST http://localhost:8080/internal/debits \
  -H 'Content-Type: application/json' -d '{}'
```

Le dernier chemin doit être refusé. Le routage automatique des services Eureka
est désactivé ; les chemins internes ne figurent pas dans les routes publiques.
Cela ne constitue pas une authentification interservice : Keycloak reste à intégrer.

## Vérification isolée reproductible

```bash
python3 scripts/demo-cloud.py
```

Ce script teste tous les modules et compile dans une copie temporaire. Il utilise
18761, 18888, 18080, 18081 et 18082, des bases temporaires et le client SDK MCP officiel.
Il vérifie le registre, l'import de configuration, Feign via découverte, les routes,
les chemins internes bloqués et une panne/reprise. Il n'arrête que ses propres processus.
Il ne doit annoncer le succès qu'après vérification effective des réponses.

En cas de panne après persistance, `PENDING` n'est pas une réussite du mouvement.
Les reprises conservent la clé et l'operationId ; aucune donnée bancaire n'est simulée.
Les anciennes lignes `LEGACY_UNDEBITED` restent exclues des reprises.
