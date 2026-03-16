# Context

Riff-Raff is the Guardian's Scala-based deployment system, providing a Play Framework web application for automating and recording deploys. Built with sbt and Scala 2.13.

# Bash commands
- `sbt compile`: Compile all modules
- `sbt test`: Run all tests (requires Docker for database)
- `sbt scalafmtCheckAll scalafmtSbtCheck`: Check formatting of Scala code and sbt files
- `sbt scalafmtAll scalafmtSbt`: Auto-format Scala code and sbt files
- `docker compose up -d`: Start required services (PostgreSQL)

# Workflow
- Start Docker services with `docker compose up -d` before running tests
- Format code with `sbt scalafmtAll scalafmtSbt`
- Check formatting passes with `sbt scalafmtCheckAll scalafmtSbtCheck`
- Compile with `sbt compile`
- Run tests with `sbt test`
- For faster iteration, run single module tests: `sbt lib/test` or `sbt riffraff/test`

# Verification

## Running locally

Riff-Raff is a Play Framework web app that runs on http://localhost:9000. Running locally connects to real AWS services (S3, DynamoDB, SSM) via the `deployTools` AWS profile, so it can read real deployment data. The local PostgreSQL database (via Docker) is isolated, so deploys will not affect production.

### Pre requisites

- **`deployTools` AWS credentials** (with "Deploy Tools - Developer" permissions)
  - Verify: `aws sts get-caller-identity --profile deployTools`
  - If missing: obtain credentials from [Janus](https://janus.gutools.co.uk/) — request the "Deploy Tools - Developer" role
- **Docker** (for PostgreSQL)
  - Verify: `docker info`
  - If missing: install Docker Desktop
- **Java 11**
  - Verify: `java -version`
  - If missing: install via `mise` using the `.tool-versions` file
- **Private configuration file** at `~/.gu/riff-raff.conf`
  - Verify: `test -f ~/.gu/riff-raff.conf && echo "OK"`
  - If missing: run `./script/setup` (requires `deployTools` credentials)

### Configuration options

- `~/.gu/riff-raff.conf` — private config merged at startup (downloaded by `./script/setup` from S3)
- `--debug` flag on `./script/start` enables remote debugging on port 9999

### Expected Side Effects

- Connects to AWS services (S3, DynamoDB, SSM Parameter Store) in `eu-west-1` to read deployment metadata
- Starts a local PostgreSQL database in Docker on port 7432
- Polls S3 for new builds at a regular interval
- If continuous deployment is enabled in config, it may trigger real deployments — the default local config has this disabled

### Execution

Ask the user to start the dev server:

> Please run `./script/start` in a terminal to start Riff-Raff locally. This will start Docker services and the Play app. Let me know once you see "Server started" or the app is available at http://localhost:9000.

### Expected output

- SBT compiles the project and Play starts listening on port 9000
- The terminal shows `(Server started, use Enter to stop and go back to the console...)`
- Navigating to http://localhost:9000 shows the Riff-Raff home page (after Google OAuth login)

### Browser verification with agent-browser

Riff-Raff uses Google OAuth 2.0, so browser-based verification requires a persisted session. Use `agent-browser` with `--session-name riff-raff` for all browser interactions.

#### First-time setup (requires user interaction)

If no saved session exists, open the browser in headed mode so the user can sign in:

```bash
npx agent-browser --session-name riff-raff --headed open http://localhost:9000
```

Ask the user to complete Google sign-in in the browser window. Once they confirm they're on the Riff-Raff home page, close to save the session:

```bash
npx agent-browser close
```

#### Subsequent visits (no login needed)

Once a session is saved, you can browse Riff-Raff headlessly without user interaction:

```bash
npx agent-browser --session-name riff-raff open http://localhost:9000 && npx agent-browser wait --load networkidle && npx agent-browser snapshot -i
```

If the session has expired (you get redirected to Google sign-in), repeat the first-time setup.

#### Key pages to verify

- **Home**: `http://localhost:9000/`
- **Deploy history**: `http://localhost:9000/deployment/history`
- **Deploy form**: `http://localhost:9000/deployment/request`
- **Documentation**: `http://localhost:9000/docs`

## Testing in CODE

Riff-Raff has a CODE environment that mirrors production. Deploy your branch to CODE first to verify changes before merging.

### Pre requisites

- **`deployTools` AWS credentials**
  - Verify: `aws sts get-caller-identity --profile deployTools`
  - If missing: obtain credentials from [Janus](https://janus.gutools.co.uk/) — request the "Deploy Tools - Developer" role
- **CI build must pass** before deploying

### Deployment

#### 1. Check CI status
```bash
gh pr checks <PR_NUMBER>
```

#### 2. Deploy to CODE via Riff-Raff
For every deployment request, run the following command:
```bash
riff-raff-links
```
`riff-raff-links` is a tool available in the environment that prints out links the user can use to deploy the application.

### Execution

Once deployed to CODE, verify the application is running:

```bash
# Check the CODE instance is healthy
curl -s https://riff-raff.code.dev-gutools.co.uk/healthcheck
```

### Expected output

- The healthcheck endpoint returns a 200 status with a JSON body indicating the app is healthy
- The CODE instance is accessible at `https://riff-raff.code.dev-gutools.co.uk/`
- Check CloudWatch logs for errors:
  ```bash
  aws logs tail /deploy/CODE/riff-raff --profile deployTools --region eu-west-1 --since 10m
  ```
