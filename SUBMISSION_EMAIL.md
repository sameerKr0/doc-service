Subject: Re: Sr Java Developer - take-home submission (Sameer)

Hi Vedha,

Thank you for the opportunity. Please find my submission below.

**Repository / ZIP:** <GitHub repo link or attached document-service.zip>

**What is included**
- Document Service (Spring Boot 3, Java 21): S3 integration via AWS SDK v2, Spring Data JPA metadata persistence, pre-signed upload/download URLs, JUnit 5 + Mockito tests, Dockerfile, concise README.
- Serverless step (Java 21 Lambda): triggered by S3 uploads, sets document status to PROCESSED through the service's internal endpoint; least-privilege IAM role (no S3 access, own logs + one SSM parameter) and CloudWatch alarms (Errors, Throttles) defined in CloudFormation.
- CI/CD (GitHub Actions): build, test, Docker image build, ECR push and CloudFormation deployment; AWS authentication through GitHub OIDC and an IAM role restricted to the main branch (no stored keys).
- Shared Responsibility Model explanation: README, section "Shared Responsibility Model".

**AI tools used**
- Claude (Anthropic) generated first drafts of all parts: the service code and tests, the Lambda and its tests, the CloudFormation templates, the GitHub Actions workflow, the Dockerfile and the README.
- <Add what you personally reviewed, changed and ran, e.g. "I reviewed every file, ran mvn verify and the Lambda tests locally, and changed X.">

**Assumptions** (full list in the README)
1. The Lambda calls the service over HTTPS (URL passed as a parameter) instead of connecting to the database, keeping its IAM policy minimal and avoiding VPC networking.
2. The internal endpoint is protected by a shared secret in SSM Parameter Store; end-user authentication for the public API is out of scope.
3. H2 with ddl-auto=update keeps the exercise runnable with no setup; a real deployment would use PostgreSQL with Flyway.
4. The pipeline pushes the image to ECR and deploys the serverless stack; the container runtime (ECS/App Runner) and database are not provisioned.

I am happy to walk through the implementation and design decisions whenever suits you.

Best regards,
Sameer
