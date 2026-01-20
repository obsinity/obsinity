## 🧩 Maven Deploy Script

### Overview

This `deploy` script automates **snapshot** and **release** deployments for Maven multi-module projects.

It determines the deploy type (`snapshot` vs `release`) automatically from your project’s `pom.xml`,
loads deployment configuration from `~/.m2/<config>-deploy.xml`, and supports automatic module exclusion via `.excludes` files.

This script is designed for **safe, repeatable, CI-friendly deployments** — with optional rich logging (`--debug`) and full shell tracing (`--trace`).

---

### ✨ Features

* 🔍 **Automatic target detection**
  Determines whether to deploy using the `snapshot` or `release` profile by parsing the `<version>` tag in `pom.xml`.

* ⚙️ **Config-based profiles**
  Uses Maven profiles in the form `<config>-snapshot` and `<config>-release`, with corresponding settings in:

  ```
  ~/.m2/<config>-deploy.xml
  ```

* 🚫 **Module exclusion support**
  Skip certain modules (e.g., reference clients, test servers) using:

  * A `.excludes` file located next to the script
  * `--exclude` or `--exclude-file` options

* 🧱 **Safe by default**
  Uses `set -euo pipefail` — the script exits immediately on any failure.

* 🪶 **Debug & Trace modes**

  * `--debug`: Structured, emoji-enhanced step logging
  * `--trace`: Enables Bash’s native `set -x` to print every command

---

### 📦 Configuration

Each deployment configuration must have a corresponding Maven settings file in `~/.m2/`.

Example:

```
~/.m2/acme-corp-deploy.xml
~/.m2/acme-deploy.xml
```

Each file typically defines two profiles:

* `<config>-snapshot` — deploys to your snapshot repository
* `<config>-release` — deploys to your release repository

Example:

```xml
<settings>
  <profiles>
    <profile>
      <id>acme-corp-release</id>
      <properties>
        <altDeploymentRepository>central::default::https://repo.example.com/libs-release-local</altDeploymentRepository>
      </properties>
    </profile>
    <profile>
      <id>acme-corp-snapshot</id>
      <properties>
        <altDeploymentRepository>snapshots::default::https://repo.example.com/libs-snapshot-local</altDeploymentRepository>
      </properties>
    </profile>
  </profiles>
  <servers>
    <!-- credentials here -->
  </servers>
</settings>
```

---

### 🗂️ `.excludes` File

To exclude modules from deployment (e.g., reference or demo projects), create a file named `.excludes`
in the same directory as `deploy`.

**Example `.excludes`**

```
# Lines starting with # are ignored
obsinity-reference-client
obsinity-reference-service
examples/demo
```

Entries can be separated by commas or newlines.
Each module will be translated into a Maven `-pl !:moduleName` exclusion.

---

### ⚙️ Usage

```bash
./deploy [options] <config> [-- <extra mvn args>]
```

#### Options

| Option                | Description                                                           |
| --------------------- | --------------------------------------------------------------------- |
| `--debug`             | Print structured debug output                                         |
| `--trace`             | Enable raw Bash tracing (`set -x`)                                    |
| `--exclude CSV`       | Comma-separated list of modules to exclude                            |
| `--exclude-file FILE` | Load excludes from a custom file (default: `.excludes` in script dir) |
| `-h`, `--help`        | Show usage information                                                |

---

### 💡 Examples

#### Basic deployment

```bash
./deploy acme-corp
```

Automatically detects from `pom.xml` whether to use `acme-corp-snapshot` or `acme-corp-release`.

#### Verbose debug mode

```bash
./deploy --debug acme-corp
```

#### Full shell trace

```bash
./deploy --trace acme-corp
```

#### Exclude specific modules

```bash
./deploy acme-corp --exclude :obsinity-reference-client,:obsinity-reference-service
```

#### Use a custom excludes file

```bash
./deploy acme-corp --exclude-file /path/to/deploy.excludes
```

#### Forward extra Maven arguments

```bash
./deploy acme-corp -- -DskipTests=false -DperformRelease=true
```

---

### 🧠 Detection Logic

1. The script reads the first `<version>` tag from `pom.xml`.

2. If it ends with `-SNAPSHOT`, the deploy target = **snapshot**; otherwise, **release**.

3. The script builds the Maven command:

   ```
   mvn -B -s ~/.m2/<config>-deploy.xml -DskipTests -P <config>-<target> \
       [ -pl !:excludedModule,... -am ] clean deploy
   ```

4. The full command is always printed before execution, even without `--debug`.

---

### 🔒 Safety & Error Handling

* Uses `set -euo pipefail` — stops immediately on error or undefined variable.
* Safe internal functions: `debug()`, `info()`, `step()` always return 0.
* If any `mvn` step fails, the script exits non-zero (CI/CD safe).

---

### 🧩 Typical Layout

```
project-root/
├── pom.xml
├── deployment/
│   ├── deploy              ← this script
│   ├── .excludes           ← optional excludes list
│   └── deploy-template.xml ← optional template for provisioning
```

---

### 🧾 Exit Codes

| Code | Meaning                                  |
| ---- | ---------------------------------------- |
| 0    | Success                                  |
| 1    | Missing version tag in `pom.xml`         |
| 2    | Maven not installed                      |
| 3    | No `pom.xml` in current directory        |
| 4    | Missing `~/.m2/<config>-deploy.xml` file |

---

### 🧰 Debugging Tips

| Flag              | What You’ll See                                       |
| ----------------- | ----------------------------------------------------- |
| `--debug`         | Step-by-step flow with emoji labels (no raw Bash)     |
| `--trace`         | Every shell command (`set -x` output)                 |
| `--debug --trace` | Both structured and raw logs — ideal for CI debugging |

---

### ✅ Example Output

```
⚙️  Checking prerequisites...
⚙️  Detecting project version from pom.xml...
⚙️  Determining deploy target...
🚀 Deploy Summary
🔧 Config:        acme-corp
🧩 Settings:      ~/.m2/acme-corp-deploy.xml
📄 Project ver:   1.2.3-SNAPSHOT
🎯 Target:        snapshot
📦 Maven profile: acme-corp-snapshot
🚫 Excluding:     obsinity-reference-client,obsinity-reference-service
▶ mvn -B -s ~/.m2/acme-corp-deploy.xml -DskipTests -P acme-corp-snapshot \
     -pl !:obsinity-reference-client,!:obsinity-reference-service -am clean deploy
✅ Deployment complete for acme-corp (snapshot)
```

---

### 🏁 Summary

| Command                                            | Purpose                      |
| -------------------------------------------------- | ---------------------------- |
| `./deploy acme-corp`                                | Auto snapshot/release deploy |
| `./deploy --debug acme-corp`                        | Show internal debug steps    |
| `./deploy --trace acme-corp`                        | Shell trace for CI logs      |
| `./deploy --exclude :module1,:module2 acme-corp`    | Skip modules                 |
| `./deploy --exclude-file custom.excludes acme-corp` | Load excludes file           |
| `./deploy --debug --trace acme-corp`                | Full transparency            |

---
