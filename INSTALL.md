# Installation & Deployment Guide

## Prerequisites

- **Java 8+** (JDK) installed and `java`/`javac` available on `$PATH`.
- **Apache Ant** (`ant`) for building the project.
- **MySQL server** reachable from the host where the application will run.
- Internet access (to download required JARs if they are missing).

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

If any of these JARs are missing, run the provided script (step 3) – it will download them automatically.

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
ant clean compile
```

Compiled classes are placed under `build/classes/`.

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
- Sources `classpath.sh` to set `$CLASSPATH`.
- Reads the MySQL configuration from `DatabaseUtils.props`.
- Starts the desired Java class (e.g., `nick.test.Predictor`).

```bash
chmod +x deploy.sh
./deploy.sh Predictor   # runs the Predictor main class
```

You can replace `Predictor` with any fully‑qualified class name that contains a `public static void main(String[] args)` method (e.g., `descriptors.CalculationHandler` for a test harness).

### Deploy script details
The script performs the following steps:
1. **Load classpath** – `source ./classpath.sh`.
2. **Validate MySQL connectivity** – attempts a simple `SELECT 1` using the HikariCP pool; aborts if the connection fails.
3. **Execute the Java program** – `java -cp "$CLASSPATH" "$MAIN_CLASS" "$@"`.
4. **Logs** – all output is written to `deploy.log`.

## 7. Running unit tests

```bash
ant test
```
The test suite uses the in‑memory H2 database, so no MySQL server is required for tests.

---
### Quick one‑liner to bootstrap everything
```bash
./setup.sh && ant clean compile && ./deploy.sh Predictor
```

Happy coding! 🚀
