# Setup Comparison: Existing Scripts vs Demo Stack

## Quick Reference

| Feature | Existing Scripts | Demo Stack with Grafana |
|---------|------------------|------------------------|
| **Location** | `obsinity-reference-service/` | Repository root |
| **Start Command** | `./build.sh && ./run.sh` | `./start-grafana-demo.sh` or<br>`docker-compose -f docker-compose.demo.yml up -d` |
| **Docker Compose File** | `obsinity-reference-service/docker-compose.yml` | `docker-compose.demo.yml` |
| **PostgreSQL** | ✅ Yes (port 5432) | ✅ Yes (port 5432) |
| **Obsinity Server** | ✅ Yes (port 8086) | ✅ Yes (port 8086) |
| **Demo Client** | ❌ No | ✅ Yes (port 8080) |
| **Grafana** | ❌ No | ✅ Yes (port 3086) |
| **Use Case** | Development & testing | Demos & visualization |
| **Build Process** | Maven build included | Uses pre-built image |
| **Clean Start** | `./run.sh --clean` | `docker-compose -f docker-compose.demo.yml down -v` |
| **Stop** | `docker compose down` | `docker-compose -f docker-compose.demo.yml down` |

## Detailed Comparison

### Existing Scripts (build.sh / run.sh)

**Directory:** `obsinity-reference-service/`

**What they do:**
1. `build.sh` - Builds the Maven project and Docker image
2. `run.sh` - Starts PostgreSQL + Obsinity Server

**Services Started:**
```
obsinity-db                  (PostgreSQL 16)
obsinity-reference-server    (Obsinity API)
```

**Access:**
- Obsinity API: http://localhost:8086
- PostgreSQL: localhost:5432 (obsinity/obsinity)

**When to use:**
- ✅ API development
- ✅ Testing new features
- ✅ Running unit/integration tests
- ✅ When you need to rebuild code frequently
- ❌ Not for demos (no visualization)

**Advantages:**
- Integrated Maven build
- Faster iteration during development
- Uses local docker-compose.yml
- Can customize via .env file

### Demo Stack with Grafana

**Directory:** Repository root

**What it does:**
Starts a complete demo environment with visualization

**Services Started:**
```
obsinity-demo-db              (PostgreSQL 16)
obsinity-reference-server     (Obsinity API)
obsinity-demo-client          (Demo client app)
obsinity-grafana              (Grafana + dashboards)
```

**Access:**
- Grafana: http://localhost:3086 (admin/admin)
- Obsinity API: http://localhost:8086
- Demo Client: http://localhost:8080
- PostgreSQL: localhost:5432 (obsinity/obsinity)

**When to use:**
- ✅ Demos and presentations
- ✅ Visualizing metrics
- ✅ Testing query APIs
- ✅ Showing state counters, histograms, transitions
- ❌ Not for active development (no auto-rebuild)

**Advantages:**
- Pre-configured Grafana dashboards
- Demo data generator
- Visual feedback on API queries
- Complete observability stack

## Switching Between Setups

### From Existing Scripts → Demo Stack

```bash
# Stop the reference service
cd obsinity-reference-service
docker compose down

# Start the demo stack with Grafana
cd ..
docker-compose -f docker-compose.demo.yml up -d
```

### From Demo Stack → Existing Scripts

```bash
# Stop the demo stack
docker-compose -f docker-compose.demo.yml down

# Start the reference service
cd obsinity-reference-service
./run.sh
```

## Common Scenarios

### Scenario 1: "I'm developing a new feature"

**Use:** Existing scripts (`build.sh` / `run.sh`)

```bash
cd obsinity-reference-service
./build.sh
./run.sh

# Make code changes
# Ctrl+C to stop
./build.sh && ./run.sh
```

### Scenario 2: "I want to demo Obsinity to stakeholders"

**Use:** Demo stack with Grafana

```bash
./start-grafana-demo.sh
# Opens browser, shows dashboards
```

### Scenario 3: "I want to test API queries manually"

**Use:** Either option works

With existing scripts:
```bash
cd obsinity-reference-service
./run.sh
# Test with curl or Insomnia
```

With demo stack (includes visual feedback):
```bash
./start-grafana-demo.sh
# Test with curl AND see results in Grafana
```

### Scenario 4: "I'm building custom dashboards"

**Use:** Demo stack with Grafana

```bash
docker-compose -f docker-compose.demo.yml up -d
# Edit dashboards in Grafana UI
# Export JSON and save to grafana/dashboards/
```

## FAQ

**Q: Can I run both at the same time?**

A: No - they both use port 8086 for Obsinity and 5432 for PostgreSQL. You must stop one before starting the other.

**Q: Do I need to rebuild with build.sh before using the demo stack?**

A: No - the demo stack uses its own Docker build context. However, if you've made code changes, you should rebuild the demo stack:
```bash
docker-compose -f docker-compose.demo.yml build
docker-compose -f docker-compose.demo.yml up -d
```

**Q: Can I add Grafana to the existing docker-compose.yml?**

A: Yes, but it's not recommended. The demo stack is designed to be separate so it doesn't interfere with development workflows.

**Q: Which one should I use for CI/CD?**

A: Use the existing scripts for testing, and the demo stack for staging/demo environments.

**Q: Can I use the demo data generator with existing scripts?**

A: Yes! The demo data generator API endpoint works with both setups:
```bash
# With either setup running on port 8086
curl -X POST http://localhost:8086/internal/demo/generate-unified-events \
  -H "Content-Type: application/json" \
  -d '{"events": 1000, "recentWindowSeconds": 3600}'
```

**Q: What if I want to rebuild the app in the demo stack?**

A: The demo stack doesn't include Maven. You have two options:
1. Rebuild locally first, then restart:
   ```bash
   cd obsinity-reference-service
   ./build.sh
   cd ..
   docker-compose -f docker-compose.demo.yml build
   docker-compose -f docker-compose.demo.yml up -d
   ```
2. Or just rebuild the demo stack image:
   ```bash
   docker-compose -f docker-compose.demo.yml build --no-cache
   docker-compose -f docker-compose.demo.yml up -d
   ```

## Summary

- **Development**: Use `build.sh` / `run.sh` in `obsinity-reference-service/`
- **Demos**: Use `docker-compose.demo.yml` or `./start-grafana-demo.sh`
- **Both are valid** - choose based on your needs
- **They're independent** - can't run simultaneously
- **Data generator works with both** - it's just an API endpoint
