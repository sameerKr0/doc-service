# Document Service on AWS

Spring Boot service that stores document **metadata** in a database and hands clients **pre-signed S3 URLs** to upload/download the files directly. An S3-triggered Lambda marks each document `PROCESSED` once its upload lands.

```
 client ──POST /api/documents──▶ Document Service ──▶ DB (JPA)
   │                                  │ pre-signed PUT URL
   ├──PUT file (pre-signed URL)──────▶ S3 bucket  (uploads/<id>/<name>)
   │                                  │ s3:ObjectCreated
   │                                  ▼
   │                             Lambda (Java 21) ──POST /internal/documents/processed──▶ Document Service
   └──GET /api/documents/{id}/download-url  (only when PROCESSED) ──▶ pre-signed GET URL
```

| Part | Where |
|---|---|
| Spring Boot 3 / Java 21 service | `src/` |
| Processor Lambda (Java 21, separate Maven project) + tests | `lambda-java/` |
| CloudFormation: bucket, Lambda, IAM, alarms | `infra/template.yaml` |
| CloudFormation: one-time OIDC/ECR/roles bootstrap | `infra/bootstrap.yaml` |
| GitHub Actions pipeline | `.github/workflows/ci-cd.yml` |

## API

| Method & path | Purpose |
|---|---|
| `POST /api/documents` `{fileName, contentType}` | Create metadata (`PENDING`), return `uploadUrl` + `requiredHeaders` |
| `GET /api/documents/{id}` | Metadata |
| `GET /api/documents?size=50` | Latest documents (size clamped to 1-100) |
| `GET /api/documents/{id}/download-url` | Pre-signed GET URL; `409` unless `PROCESSED` |
| `DELETE /api/documents/{id}` | Delete S3 object and metadata |
| `POST /internal/documents/processed` `{s3Key}` | Lambda callback, needs `X-Internal-Token` |

Errors are RFC 7807 problem responses (400 validation, 404 unknown id, 409 wrong state, 403 bad token).

Upload example:

```bash
curl -s localhost:8080/api/documents -H 'Content-Type: application/json' \
     -d '{"fileName":"report.pdf","contentType":"application/pdf"}'
# then PUT the file to uploadUrl with the Content-Type header from requiredHeaders
curl -X PUT -H 'Content-Type: application/pdf' --data-binary @report.pdf "<uploadUrl>"
```

## Run locally

Requires Java 21 and Maven. Uses in-memory H2 by default.

```bash
mvn verify                          # service unit tests
mvn -f lambda-java/pom.xml verify   # Lambda unit tests + target/processor.jar
export DOCS_BUCKET=<a bucket you own>  AWS_REGION=ap-south-1   # credentials via the normal AWS chain (SSO profile, env vars)
export INTERNAL_API_TOKEN=dev-token
mvn spring-boot:run
```

LocalStack instead of real AWS: `S3_ENDPOINT=http://localhost:4566` (path-style addressing is enabled automatically).

Docker:

```bash
docker build -t document-service .
docker run -p 8080:8080 -e DOCS_BUCKET=... -e AWS_REGION=ap-south-1 document-service
```

Configuration (env vars): `DB_URL`, `DB_USER`, `DB_PASSWORD` (use PostgreSQL when deployed), `DOCS_BUCKET`, `AWS_REGION`, `S3_ENDPOINT`, `PRESIGN_TTL` (default `PT15M`), `INTERNAL_API_TOKEN`, `DDL_AUTO`.

## Tests

- `DocumentServiceTest` - JUnit 5 + Mockito. Repository and `S3Client` are mocks; the presigner is real with dummy static credentials (signing is local), so assertions check the real URL (bucket host, signature, 900 s expiry, signed content-type, content-disposition).
- `DocumentControllerTest` - `@WebMvcTest` with a mocked service: status codes, validation, token check.
- `DocumentRepositoryTest` - `@DataJpaTest` against H2.
- `lambda-java/` (`S3EventHandlerTest`, `DocServiceClientTest`) - JUnit 5 + Mockito for the handler (notifier and token supplier mocked, real S3-notification JSON parsed); the HTTP client is tested against an in-process `HttpServer`. Run with `mvn -f lambda-java/pom.xml verify`, which also builds the deployable `target/processor.jar`.

## Deploying

One-time, with admin credentials:

```bash
aws cloudformation deploy --template-file infra/bootstrap.yaml --stack-name docsvc-bootstrap \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides GitHubOrg=<org> GitHubRepo=<repo>
aws ssm put-parameter --name /docsvc/prod/internal-api-token --type SecureString --value "$(openssl rand -hex 32)"
```

Then set these **repository variables** (Settings → Secrets and variables → Actions → Variables). They are not secrets; there are no AWS secrets in this repo:

`AWS_REGION`, `AWS_DEPLOY_ROLE_ARN`, `CFN_EXECUTION_ROLE_ARN`, `ECR_REPOSITORY`, `ARTIFACT_BUCKET` (all from the bootstrap stack outputs), `DOC_SERVICE_URL`, optional `ALARM_EMAIL`. Create a GitHub environment named `production` (add required reviewers if you want a manual gate).

Pipeline (`ci-cd.yml`): on every PR and push it runs `mvn verify` for the service and for the Lambda, `cfn-lint`, and a Docker build. On push to `main` it additionally assumes the deploy role through **GitHub OIDC**, pushes the image to ECR tagged with the commit SHA, builds and packages the Lambda jar, and deploys `infra/template.yaml` through CloudFormation using a separate execution role.

### Authentication: OIDC, no stored keys
The job requests a short-lived GitHub OIDC token (`permissions: id-token: write`) and exchanges it with STS via `AssumeRoleWithWebIdentity`. The role's trust policy accepts only `aud = sts.amazonaws.com` and `sub = repo:<org>/<repo>:ref:refs/heads/main`, so pull requests and forks cannot deploy. The deploy role itself cannot create resources; it can only push to one ECR repo, upload to the artifact bucket, drive `docsvc-app-*` stacks, and pass the CloudFormation execution role.

### IAM least privilege for the Lambda
The processor role can: write to **its own** log group, and `ssm:GetParameter` on **one** parameter. It has **no S3 permissions** because the S3 event already carries the key and size, so the function never reads the object. The invoke permission is restricted with `SourceArn` + `SourceAccount`.

### CloudWatch alarms
`docsvc-<env>-processor-errors` (Lambda `Errors` ≥ 1 in 5 min) and `…-processor-throttles`, both notifying an SNS topic (optionally subscribed by e-mail). S3 invokes Lambda asynchronously, so failures retry twice and are dropped after one hour; the Errors alarm is what surfaces a persistent failure.

## Design decisions

- **Direct-to-S3 transfers.** File bytes never pass through the service: cheaper, no size limits in the app, no memory pressure.
- **Metadata row first, URL second, in one transaction.** A presign failure leaves no orphan row.
- **Idempotent callback.** S3 notifications are at-least-once; marking an already `PROCESSED` document is a no-op.
- **Download gated on `PROCESSED`**, so clients cannot fetch files before processing.
- **Filename sanitising** (`[A-Za-z0-9._-]`, no path segments) keeps object keys inside `uploads/<uuid>/`.
- **Content-Type is part of the signature**, so a URL issued for a PDF cannot be used to upload something else.
- **Bucket hygiene:** Block Public Access, SSE-S3, TLS-only bucket policy, `BucketOwnerEnforced`, `DeletionPolicy: Retain`.

## Assumptions

1. The Lambda reaches the service over HTTPS through a URL supplied as `DocServiceUrl` (e.g. an ALB or API Gateway). It is not placed in a VPC and does not talk to the database directly, which keeps its IAM policy tiny and avoids VPC networking for the exercise.
2. `/internal/**` is protected by a shared secret in SSM Parameter Store. In production I would also keep that path off the public listener (private ALB rule / security group) and rotate the secret.
3. End-user authentication/authorization for `/api/**` is out of scope (no Spring Security). It would sit in front as OAuth2/JWT or an API Gateway authorizer, and documents would gain an owner field.
4. `ddl-auto=update` and H2 keep the exercise runnable with zero setup. A real deployment would use PostgreSQL (driver already included) with Flyway migrations.
5. The pipeline builds and pushes the image to ECR and deploys the serverless stack. Where the container *runs* (ECS Fargate / App Runner / EKS) and its RDS database are deliberately left out of the templates; the image and ECR repo are ready for any of them.
6. Single environment (`prod`); the templates are parameterised by `Environment` if more are needed.
7. Region defaults to `ap-south-1`; change `AWS_REGION` as needed.
8. Browser-based direct uploads would additionally need a CORS configuration on the bucket; not added since no front-end is in scope.

## Shared Responsibility Model

AWS secures **the cloud** (the facilities, hardware, network and the software of the managed services). I secure **what I put in the cloud** (configuration, identities, code and data). Mapped to this project:

| Component | AWS is responsible for | I am responsible for |
|---|---|---|
| **S3** | Physical security, durability and availability of the service, host/hypervisor patching, encryption machinery | Block Public Access, bucket policy (TLS-only), encryption setting, object key design, retention/lifecycle, who may presign (IAM) |
| **Lambda** | Servers, OS, patching of the managed Java runtime, scaling, isolation between tenants | Function code and its dependencies, execution-role permissions, environment variables (no secrets in them), timeouts/retries, alarms |
| **IAM / STS / OIDC** | The identity service itself, token signing and validation | Trust-policy conditions (`aud`, `sub`), least-privilege policies, role scoping, reviewing access |
| **ECR** | Registry availability and storage security | Base-image choice and patching, running as non-root, reacting to scan findings (scan-on-push is on), immutable tags |
| **CloudFormation / CloudWatch / SNS** | Service operation and availability | Template content, alarm thresholds and notification routing, log retention |
| **SSM Parameter Store** | Storage and KMS integration | What is stored, who can read it, rotation |
| **CI/CD (GitHub Actions)** | *(GitHub, a third party, secures its runners)* | No long-lived keys, branch-restricted role, pinned/trusted actions, `production` environment protection |
| **Application** | Nothing | Input validation, filename sanitising, authN/authZ, dependency updates, logging without secrets, data classification |

In short: because Lambda and S3 are managed, AWS's share is larger there than it would be on EC2; but access control, configuration and code stay mine in every row.

## AI tools used

- **Claude (Anthropic)** was used to generate the first drafts of **all** artefacts in this repository: the Spring Boot code, unit tests, Lambda function and its tests, both CloudFormation templates, the GitHub Actions workflow, the Dockerfile, and this README (including the Shared Responsibility section).
- Verified in the authoring environment: the YAML files parse and template cross-references resolve; the Java sources were syntax-checked.
- **Not run in that environment:** `mvn verify` for either Maven project (Maven Central was unreachable), Docker build, `cfn-lint`, and any real AWS deployment.
- Author's own review/run status: _to be completed by the author before submission_.
