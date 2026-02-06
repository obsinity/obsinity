# Quick Start Guide - Choosing Your Setup

## TL;DR

```
Are you developing code?
├─ YES → Use: cd obsinity-reference-service && ./build.sh && ./run.sh
└─ NO  → Use: ./start-grafana-demo.sh
```

## Visual Comparison

```
┌─────────────────────────────────────────────────────────────────┐
│                    EXISTING SCRIPTS (Dev)                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Location: obsinity-reference-service/                         │
│  Command:  ./build.sh && ./run.sh                              │
│                                                                 │
│  ┌───────────┐         ┌─────────────────────┐                │
│  │ PostgreSQL│◄────────┤ Obsinity Server     │                │
│  │  :5432    │         │      :8086          │                │
│  └───────────┘         └─────────────────────┘                │
│                               │                                 │
│                               ▼                                 │
│                        [API Testing via                         │
│                         curl/Insomnia]                          │
│                                                                 │
│  Use for: Development, Testing, Debugging                      │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                DEMO STACK WITH GRAFANA (Demos)                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Location: Repository root                                     │
│  Command:  ./start-grafana-demo.sh                             │
│                                                                 │
│  ┌───────────┐         ┌─────────────────────┐                │
│  │ PostgreSQL│◄────────┤ Obsinity Server     │                │
│  │  :5432    │         │      :8086          │                │
│  └───────────┘         └─────────────────────┘                │
│                               │                                 │
│                               ├──────────────────┐              │
│                               │                  │              │
│                               ▼                  ▼              │
│                        ┌──────────┐      ┌─────────┐           │
│                        │ Grafana  │      │  Demo   │           │
│                        │  :3086   │      │ Client  │           │
│                        └──────────┘      │  :8080  │           │
│                               │          └─────────┘           │
│                               ▼                                 │
│                        [Visual Dashboards                       │
│                         with Live Metrics]                      │
│                                                                 │
│  Use for: Demos, Presentations, Visualization                  │
└─────────────────────────────────────────────────────────────────┘
```

## Decision Tree

```
START: What do you want to do?
│
├─ Write/modify Java code?
│  └─ YES → Use existing scripts (build.sh/run.sh)
│           - Fast rebuild cycle
│           - Maven integration
│           - Hot reload possible
│
├─ Show metrics visually?
│  └─ YES → Use demo stack (start-grafana-demo.sh)
│           - Pre-built dashboards
│           - Real-time visualization
│           - No rebuild needed
│
├─ Test API endpoints?
│  └─ EITHER works, but consider:
│     ├─ Need visual feedback? → Demo stack (has Grafana)
│     └─ Just curl/Postman? → Existing scripts (simpler)
│
└─ Present to stakeholders?
   └─ YES → Use demo stack (start-grafana-demo.sh)
            - Impressive visuals
            - Easy to understand
            - Professional demo
```

## Commands Cheat Sheet

### Existing Scripts (Development)

```bash
# Initial setup
cd obsinity-reference-service
./build.sh

# Start
./run.sh

# Clean start (wipe data)
./run.sh --clean

# Stop
# Press Ctrl+C, then:
docker compose down

# Rebuild after code changes
./build.sh && ./run.sh
```

### Demo Stack (Grafana)

```bash
# Start (from repo root)
./start-grafana-demo.sh

# Or manually
docker-compose -f docker-compose.demo.yml up -d

# Generate demo data
curl -X POST http://localhost:8086/internal/demo/generate-unified-events \
  -H "Content-Type: application/json" \
  -d '{"events": 1000, "recentWindowSeconds": 3600}'

# Stop
docker-compose -f docker-compose.demo.yml down

# Stop and wipe data
docker-compose -f docker-compose.demo.yml down -v
```

## Ports Reference

| Service | Existing Scripts | Demo Stack | Purpose |
|---------|------------------|------------|---------|
| PostgreSQL | ✅ 5432 | ✅ 5432 | Database |
| Obsinity Server | ✅ 8086 | ✅ 8086 | REST API |
| Demo Client | ❌ N/A | ✅ 8080 | Sample app |
| Grafana | ❌ N/A | ✅ 3086 | Dashboards |

## When to Use What

### Use Existing Scripts (`build.sh` / `run.sh`) When:

✅ Actively developing features  
✅ Need to rebuild frequently  
✅ Running unit/integration tests  
✅ Debugging code  
✅ Working on API implementation  
✅ Testing locally without UI  

### Use Demo Stack (`start-grafana-demo.sh`) When:

✅ Demonstrating to stakeholders  
✅ Showing metrics visually  
✅ Testing query APIs with visual feedback  
✅ Creating/editing dashboards  
✅ Running acceptance tests  
✅ Recording demo videos  
✅ Training new team members  

## Pro Tips

### Tip 1: Both Use Same Port
You **cannot** run both simultaneously. Stop one before starting the other:
```bash
# If existing scripts are running:
cd obsinity-reference-service && docker compose down
cd .. && ./start-grafana-demo.sh

# If demo stack is running:
docker-compose -f docker-compose.demo.yml down
cd obsinity-reference-service && ./run.sh
```

### Tip 2: Demo Data Works with Both
The demo data generator is just an API endpoint, so it works regardless of which setup you're using:
```bash
# Works with both!
curl -X POST http://localhost:8086/internal/demo/generate-unified-events \
  -H "Content-Type: application/json" \
  -d '{"events": 100}'
```

### Tip 3: Hybrid Workflow
Some developers prefer this workflow:
1. Develop with existing scripts (fast iteration)
2. Once feature is done, switch to demo stack (visual verification)
3. Use Grafana to verify metrics look correct
4. Switch back to development

### Tip 4: Environment Variables
Both setups respect environment variables:
```bash
# Existing scripts
PROFILE=local ./run.sh
SPRING_PROFILES_ACTIVE=prod ./run.sh

# Demo stack
SPRING_PROFILES_ACTIVE=demo docker-compose -f docker-compose.demo.yml up -d
```

### Tip 5: Logs
```bash
# Existing scripts (follows logs automatically)
./run.sh   # Ctrl+C to exit

# Demo stack
docker-compose -f docker-compose.demo.yml logs -f
docker logs obsinity-reference-server -f
docker logs obsinity-grafana -f
```

## Still Confused?

**Just remember:**

- 📝 **Coding?** → `cd obsinity-reference-service && ./run.sh`
- 🎨 **Demoing?** → `./start-grafana-demo.sh`

That's it! 🎉
