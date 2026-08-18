# Local Backend Setup

This guide runs the ICDC backend with standard local tooling. It does not use Nix, nix-darwin, or Home Manager.

## Prerequisites

- JDK 17 (`java -version` must report 17)
- Maven 3.9+
- Neo4j Desktop 1.6.3 (build 1.6.3.156) with Neo4j DBMS 4.4.5
- Docker, or a native OpenSearch 2.19.4 installation
- Python 3.9 for `icdc-dataloader`
- Sibling clones of `bento-icdc-backend`, `icdc-dataloader`, `icdc-model-tool`, and `bento-icdc-static-content`

Install Java and Maven with your system package manager and set `JAVA_HOME` to JDK 17. Both commands below must report Java 17; if Maven reports another version, correct `JAVA_HOME` before building.

```sh
java -version
mvn -version
```

## 1. Start Neo4j

Install Neo4j Desktop **1.6.3** and create a local DBMS using Neo4j **4.4.5**. Do not accept a newer default DBMS version. Create or restore the current ICDC database, then start it with Bolt enabled on port `7687`. Keep the username and password for the application and index-loader configuration below.

## 2. Start OpenSearch 2.19.4

For a local-only Docker instance:

```sh
docker run --name icdc-opensearch --detach \
  --publish 127.0.0.1:9200:9200 \
  --env "discovery.type=single-node" \
  --env "DISABLE_INSTALL_DEMO_CONFIG=true" \
  --env "DISABLE_SECURITY_PLUGIN=true" \
  --env "OPENSEARCH_JAVA_OPTS=-Xms1g -Xmx1g" \
  opensearchproject/opensearch:2.19.4

curl http://localhost:9200
```

Security is disabled only because this instance is bound to localhost.

## 3. Load the OpenSearch indexes

Initialize the loader and its submodules:

```sh
cd /path/to/icdc-dataloader
git submodule update --init --recursive
python3.9 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

Create `es_loader_icdc.yml` at the backend repository root and do not commit it:

```yaml
Config:
  neo4j_uri: bolt://localhost:7687
  neo4j_user: neo4j
  neo4j_password: YOUR_LOCAL_PASSWORD
  es_host: localhost:9200
  about_file: /absolute/path/to/bento-icdc-static-content/aboutPagesContent.yaml

  model_files:
    - /absolute/path/to/icdc-model-tool/model-desc/icdc-model.yml
    - /absolute/path/to/icdc-model-tool/model-desc/icdc-model-props.yml

  prop_file: /absolute/path/to/icdc-dataloader/config/props-icdc-pmvp.yml

  indices_list: [cases, samples, files, programs, studies, about_page,
                 model_nodes, model_properties, model_values]
```

Run the loader:

```sh
cd /path/to/icdc-dataloader
BENTO_NO_LOG=1 .venv/bin/python es_loader.py \
  /path/to/bento-icdc-backend/src/main/resources/yaml/es_indices_icdc.yml \
  /path/to/bento-icdc-backend/es_loader_icdc.yml
```

`external_data` is populated separately. Create an empty local index so related GraphQL queries still work:

```sh
curl -X PUT http://localhost:9200/external_data \
  -H 'Content-Type: application/json' \
  -d '{"settings":{"number_of_replicas":0},"mappings":{"properties":{"clinical_study_designation":{"type":"keyword"}}}}'
```

## 4. Configure and run the backend

```sh
cd /path/to/bento-icdc-backend
git submodule update --init --recursive
```

Create the local configuration, then set `neo4j.password` in `application.properties` to your local password:

```sh
cp src/main/resources/application_local.properties \
  src/main/resources/application.properties
```

The template uses local Neo4j/OpenSearch, disables Redis and AWS request signing, and enables OpenSearch filtering. Do not commit local credentials. Build and start:

```sh
mvn -DskipTests package
mvn spring-boot:run
```

## 5. Verify

```sh
curl http://localhost:8080/ping

curl -X POST http://localhost:8080/v1/graphql/ \
  -H 'Content-Type: application/json' \
  -d '{"query":"{ searchCases { numberOfCases numberOfFiles } }","variables":{}}'
```

The ping should return `pong`, and the GraphQL response should contain nonzero case and file counts.

If Maven reports `PKIX path building failed` on a managed network, add your organization's root and intermediate certificates to a Java 17 truststore. Do not disable TLS certificate verification.
