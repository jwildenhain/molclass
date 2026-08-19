# Installation & Deployment Guide

## Prerequisites

- **Java 8+** (JDK) installed and `java`/`javac` available on `$PATH`.
- **Gradle** workflow is managed via the project wrapper (`./gradlew`).
- **MySQL server** reachable from the host where the application will run.
- Internet access (to download required JARs and Gradle distribution if not cached).

## 1. Clone the repository

```bash
git clone <repository-url> /home/jw/repos/wdc_gitlab/molclass
cd /home/jw/repos/wdc_gitlab/molclass
```

## 2. Resolve library dependencies

The project depends on the following external JARs (placed in the `lib/` directory):

| JAR | Version | Purpose |
|-----|---------|---------|
| `HikariCP-5.0.1.jar` | 5.0.1 | High‑performance JDBC connection pool |
| `mysql-connector-java-8.0.33.jar` | 8.0.33 | MySQL JDBC driver |
| `h2-2.2.224.jar` | 2.2.224 | In‑memory DB for unit tests |
| `junit-4.13.2.jar` | 4.13.2 | Unit‑testing framework |
| `hamcrest-core-1.3.jar` | 1.3 | Assertion library used by JUnit |

If any of these JARs are missing, run the provided script (step 3) – it will download them automatically.

## 3. Run the **setup script**

A helper script `setup.sh` will:
1. Download missing JARs from Maven Central.
2. Verify the presence of `lib/`.
3. Create a convenience `classpath.sh` file that exports the full classpath.

```bash
chmod +x setup.sh
./setup.sh
```

After successful execution you will see a message like:
```
[setup] All required JARs are present.
[setup] Classpath file created at ./classpath.sh
```

## 4. Build the project

```bash
./gradlew clean build
```

Artifacts are built with Gradle and the jar is produced under `build/libs/`.

## 5. MySQL configuration

Edit **`DatabaseUtils.props`** (located at the project root) with your MySQL connection details:

```properties
jdbcURL=jdbc:mysql://<HOST>/<DATABASE>
# Example:
# jdbcURL=jdbc:mysql://localhost/molclass

# Credentials (default values are shown – replace with your own)
# Username and password are read by the application via XML configuration files.
# If you use the default XML (`molclass.conf.xml`), set the corresponding tags:
#   <hostname>localhost</hostname>
#   <database>molclass</database>
#   <rw_user>your_user</rw_user>
#   <rw_password>your_password</rw_password>
```

> **Tip:** The `molclass.conf.xml` file already contains placeholders. Updating the XML tags will make the Java code pick up the new values automatically.

## 6. Deploy / Run the application

A deployment script `deploy.sh` is provided. It:
- Uses the Gradle wrapper (`./gradlew`) as the canonical execution path.
- Runs a connectivity check using `DBConnectionTest`.
- Starts the desired Java class (e.g., `Predictor`, `SdfImporter`, etc.).

```bash
chmod +x deploy.sh
./deploy.sh Predictor   # runs the Predictor main class via molclass.Main
```

You can replace `Predictor` with any mapped launcher name (for example `SdfImporter`) or a fully-qualified class name.

### Deploy script details
The script performs the following steps:
1. Validates launcher prerequisites and local dependencies.
2. Validates MySQL connectivity via `DBConnectionTest`.
3. Executes the Java program using Gradle (`./gradlew run --args="...")`.
4. Logs are printed directly to STDOUT/ERR.

## 7. Running unit tests

```bash
./gradlew test
```
The test suite uses the in‑memory H2 database, so no MySQL server is required for tests.

---
### Quick one‑liner to bootstrap everything
```bash
./setup.sh && ./gradlew clean build && ./deploy.sh Predictor
```

