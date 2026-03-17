# PixelWarp — Complete Plugin Guide

> Advanced Warp System for Paper 1.21.1 SMP Servers

---

## Table of Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [Configuration](#configuration)
- [Database Setup](#database-setup)
- [Commands](#commands)
- [GUI System](#gui-system)
- [Warp Categories](#warp-categories)
- [Public & Private Warps](#public--private-warps)
- [Warp Access Sharing](#warp-access-sharing)
- [Teleport System](#teleport-system)
- [Safe Teleport](#safe-teleport)
- [Preview System](#preview-system)
- [Warp Analytics](#warp-analytics)
- [Backup & Export](#backup--export)
- [Particle Effects](#particle-effects)
- [Build from Source](#build-from-source)
- [Project Structure](#project-structure)
- [Troubleshooting](#troubleshooting)

---

## Requirements

| Requirement | Version |
|---|---|
| Paper Server | 1.21.1+ |
| Java | 21+ |
| MySQL | 5.7+ / 8.0+ |

---

## Installation

1. Build the plugin (see [Build from Source](#build-from-source)) ya pre-built JAR use karo
2. `PixelWarp-1.0.0.jar` ko apne server ke `plugins/` folder mein daalo
3. Server start karo — plugin automatically `config.yml` generate karega
4. Server band karo, `plugins/PixelWarp/config.yml` edit karo (MySQL details + owner UUIDs)
5. Server dobara start karo

---

## Configuration

File: `plugins/PixelWarp/config.yml`

```yaml
# Config version — change mat karo
config-version: 1

# MySQL Database Settings
database:
  host: localhost
  port: 3306
  database: pixelwarp
  username: root
  password: "your_password_here"
  pool-size: 5

# Server owners jo kisi bhi warp ko delete kar sakte hain
# Apna Minecraft UUID yahan daalo
server-owners:
  - "your-uuid-here"
  - "another-owner-uuid"

# Teleport countdown (seconds)
teleport:
  countdown-seconds: 2
  safe-check: true         # Unsafe location (lava/void) pe teleport block karo

# Preview mode duration (seconds)
preview:
  duration-seconds: 10

# Warp location particle effects
particles:
  warp-marker:
    enabled: true
    radius: 10           # Kitne blocks door tak particles dikhein
    particle-count: 4     # Particles per warp (max 5)
    interval-ticks: 20    # Har kitne ticks mein spawn karein (20 = 1 second)
```

### Apna UUID kaise pata karein?

1. Minecraft mein login karo
2. [mcuuid.net](https://mcuuid.net/) pe apna username daalo
3. UUID copy karo aur config mein paste karo

---

## Database Setup

### Step 1: MySQL Database banao

```sql
CREATE DATABASE pixelwarp;
CREATE USER 'pixelwarp'@'localhost' IDENTIFIED BY 'strong_password';
GRANT ALL PRIVILEGES ON pixelwarp.* TO 'pixelwarp'@'localhost';
FLUSH PRIVILEGES;
```

### Step 2: Config mein details daalo

```yaml
database:
  host: localhost
  port: 3306
  database: pixelwarp
  username: pixelwarp
  password: "strong_password"
  pool-size: 5
```

> **Note:** Plugin automatically tables create karega jab pehli baar start hoga. Table manually banane ki zarurat nahi hai.

### Auto-Created Table Schemas

**Warps Table:**
```sql
CREATE TABLE IF NOT EXISTS warps (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    owner_uuid  VARCHAR(36)  NOT NULL,
    world       VARCHAR(64)  NOT NULL,
    x           DOUBLE       NOT NULL,
    y           DOUBLE       NOT NULL,
    z           DOUBLE       NOT NULL,
    yaw         FLOAT        NOT NULL,
    pitch       FLOAT        NOT NULL,
    is_public   TINYINT(1)   NOT NULL DEFAULT 1,
    icon_material VARCHAR(64) NOT NULL DEFAULT 'ENDER_PEARL',
    category    VARCHAR(32)  NOT NULL DEFAULT 'PLAYER_WARPS',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    usage_count INT          NOT NULL DEFAULT 0,
    last_used   TIMESTAMP    NULL DEFAULT NULL,
    UNIQUE KEY uk_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**Warp Access Table (Private Warp Sharing):**
```sql
CREATE TABLE IF NOT EXISTS warp_access (
    warp_name   VARCHAR(64) NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    warp_id     INT         NULL,
    PRIMARY KEY (warp_name, player_uuid),
    INDEX idx_warp_access_warp_id (warp_id, player_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> **Migration Note:** Agar pehle se warp_access table hai toh plugin startup pe automatically `warp_id` column add karega aur existing rows ko backfill karega. Koi manual migration zaruri nahi hai.

---

## Commands

### `/setwarp <name> [category] [public|private]`

Naya warp banao apni current location pe.

| Parameter | Required | Description |
|---|---|---|
| `name` | ✅ | Warp ka naam (letters, numbers, underscore — max 64 chars) |
| `category` | ❌ | Category: `SPAWN`, `SHOPS`, `BASES`, `EVENTS`, `PLAYER_WARPS` (default) |
| `public/private` | ❌ | Visibility: `public` (default) ya `private` |

**Examples:**
```
/setwarp spawn SPAWN public
/setwarp mybase BASES private
/setwarp shop1 SHOPS
/setwarp farm
```

**Rules:**
- Naam case-insensitive hai (`MyWarp` aur `mywarp` same hain)
- Spaces allowed nahi hain
- Duplicate naam nahi ho sakte

---

### `/warp <name>`

Kisi warp pe teleport karo.

```
/warp spawn
/warp mybase
```

- 2 second countdown hoga (BossBar progress ke saath)
- Agar move karoge toh teleport cancel ho jayega
- Teleport ke baad sound bajega aur particles dikhenge

---

### `/warps`

Warp GUI menu kholega (chest inventory).

- Saare visible warps dikhenge
- Categories filter kar sakte ho
- Search kar sakte ho
- Pagination support

---

### `/warp preview <name>`

Preview mode mein warp dekho bina actually jaaye.

```
/warp preview spawn
```

- Spectator mode mein switch ho jaoge
- Warp location pe teleport hoge
- 10 seconds baad automatic wapas aa jaoge
- Original location aur gamemode restore ho jayega

---

### `/warp stats <name>`

Warp ki detailed information dekho.

```
/warp stats spawn
```

**Output:**
```
══ Warp: spawn ══
Owner: PlayerName
Category: Spawn
Location: 100, 64, -200
Public: Yes
Created: 2026-03-03
Times Used: 42
Last Used: 2026-03-03 14:30
```

---

### `/warp top`

Top 5 sabse zyada use hone wale warps dekho.

```
/warp top
```

**Output:**
```
══ Top Warps ══
 1. spawn - 142 uses
 2. shop - 87 uses
 3. farm - 56 uses
 4. endportal - 34 uses
 5. nether_hub - 21 uses
```

---

### `/warp edit <name> <location|category|public|private>` *(NEW)*

Existing warp ki properties edit karo.

```
/warp edit spawn location          → Warp location update (apni current position pe)
/warp edit shop1 category SHOPS    → Category change karo
/warp edit mybase public           → Private warp ko public banao
/warp edit farm private             → Public warp ko private banao
```

**Rules:**
- Sirf warp owner ya server owner edit kar sakta hai

---

### `/warp rename <old> <new>` *(NEW)*

Warp ka naam badlo.

```
/warp rename oldshop newshop
```

**Rules:**
- Naya naam unique hona chahiye
- Sirf letters, numbers, underscore (max 64 chars)
- Sirf warp owner ya server owner rename kar sakta hai
- Access entries bhi automatically rename ho jaate hain (single database transaction mein)

---

### `/warp access <add|remove|list> <warp> [player]` *(NEW)*

Private warp ko dusre players ke saath share karo.

```
/warp access add mybase Steve      → Steve ko access do
/warp access remove mybase Steve   → Steve ka access hatao
/warp access list mybase            → Dekho kisko access hai
```

**Rules:**
- Sirf warp owner ya server owner access manage kar sakta hai
- Owner ko access dene ki zarurat nahi — unke paas already hai
- Target player online hona chahiye (add/remove ke liye)

---

### `/warp export` *(NEW)*

Saare warps ko JSON file mein export karo (backup).

```
/warp export
```

- File save hoti hai: `plugins/PixelWarp/backups/warps_2026-03-04_14-30-00.json`
- **Sirf server owners** use kar sakte hain

---

### `/warp import [filename]` *(NEW)*

JSON backup se warps import karo.

```
/warp import                                → Available backups ki list dekho
/warp import warps_2026-03-04_14-30-00.json → File se warps import karo
```

- Sirf woh warps import hote hain jo already exist nahi karte (safe merge)
- **Sirf server owners** use kar sakte hain

---

### `/delwarp <name> [confirm]`

Warp delete karo — **confirmation required!**

```
/delwarp mybase              → Confirmation prompt aayega
/delwarp mybase confirm      → Actually delete karega
```

**Confirmation Flow:**
1. `/delwarp mybase` → Chat mein clickable `[CONFIRM DELETE]` aur `[CANCEL]` buttons dikhte hain
2. 30 seconds ke andar click karo ya `/delwarp mybase confirm` type karo
3. 30 seconds baad confirmation expire ho jaata hai

**Rules:**
- **Server Owners** (config mein defined) → koi bhi warp delete kar sakte hain
- **Normal Players** → sirf apne warps delete kar sakte hain (public + private dono)

---

## GUI System

### Main Warp Menu (`/warps`)

```
┌──────────────────────────────────────────────────┐
│  [Warp1] [Warp2] [Warp3] [Warp4] [Warp5] ...    │
│  [Warp6] [Warp7] [Warp8] [Warp9] ...            │
│  ...                                              │
│  ... (45 slots for warps)                         │
│                                                    │
│──────────────────────────────────────────────────│
│  [◄Prev] [■] [Category] [■] [Search] [Page] [Close] [■] [Next►]  │
└──────────────────────────────────────────────────┘
```

**Bottom Row Controls (Slots 45-53):**

| Slot | Icon | Action |
|---|---|---|
| 45 | Arrow | Previous Page |
| 47 | Hopper | Category Filter |
| 48 | Name Tag / XP Bottle / Clock | **Sort Mode** (click to cycle) |
| 49 | Compass | Search (Anvil GUI) |
| 50 | Paper | Page indicator |
| 51 | Barrier | Close menu |
| 53 | Arrow | Next Page |

### Sorting *(NEW)*

Slot 48 pe sort button hai — click karne pe cycle hota hai:

| Sort Mode | Description | Icon |
|---|---|---|
| **A-Z** (default) | Alphabetical order | Name Tag |
| **Most Used** | Sabse zyada use hone wale pehle | XP Bottle |
| **Newest** | Naye warps pehle | Clock |

> Current mode green mein highlight hota hai button ke lore mein

### Click Actions

| Click Type | Action |
|---|---|
| **Left Click** | Warp pe teleport karo |
| **Right Click** | Preview mode mein dekho |
| **Shift Click** | Warp info chat mein dikhao |

### Warp Item Display

Har warp item ke lore mein yeh dikhta hai:
- Category naam
- Owner naam
- Coordinates (x, y, z)
- Created date
- Usage count
- `PRIVATE` label (agar private hai)
- Click instructions

### Category Menu

Jab "Category" button pe click karoge:

```
┌────────────────────────────┐
│                            │
│  [★All] [⊕Spawn] [♦Shops] │
│  [⌂Bases] [✦Events] [●Player Warps] │
│           [←Back]          │
└────────────────────────────┘
```

### Search System

1. "Search" (Compass) pe click karo
2. Anvil GUI khulega
3. Search term type karo rename field mein
4. Result item (Compass) pe click karo
5. Filtered warp menu khulega

> Search warp naam ke andar kahi bhi match karta hai (contains search)

---

## Warp Categories

| Category | Display Name | Default Icon |
|---|---|---|
| `SPAWN` | Spawn | Compass |
| `SHOPS` | Shops | Emerald |
| `BASES` | Bases | Oak Door |
| `EVENTS` | Events | Firework Rocket |
| `PLAYER_WARPS` | Player Warps | Ender Pearl |

---

## Public & Private Warps

| Feature | Public Warp | Private Warp |
|---|---|---|
| GUI mein dikhta hai | ✅ Sabko | ✅ Owner + shared players ko |
| `/warp` se teleport | ✅ Sabko | ✅ Owner + shared players ko |
| Naam ka rang (GUI) | 🟢 Green | 🟡 Gold |
| Delete kon kar sakta | Owner + Server owners | Owner + Server owners |
| Particle markers | ✅ | ❌ |
| Access share | N/A (already public) | ✅ `/warp access add` se |

### Examples

```
/setwarp publicfarm PLAYER_WARPS public    → Sabko dikhega
/setwarp secretbase BASES private          → Sirf tumko dikhega (ya jisko access doge)
```

---

## Warp Access Sharing

Private warps ko specific players ke saath share kar sakte ho bina public kiye.

### Flow:

```
1. /setwarp mybase BASES private     → Private warp banao
   ↓
2. /warp access add mybase Steve     → Steve ko access do (offline bhi chalega!)
   ↓
3. Steve ab /warp mybase use kar sakta hai
   Steve ke GUI mein bhi dikhega
   ↓
4. /warp access remove mybase Steve  → Access hatao
   ↓
5. /warp access list mybase          → Dekho kisko access hai
```

### Key Points:
- Access internally `warp_id` (integer) se track hota hai — warp rename karne pe access safe rehta hai
- `warp_name` column backward compatibility ke liye DB mein rakha hai (future mein remove hoga)
- Memory mein cache hota hai for fast lookups
- Warp delete karne pe saare access entries bhi delete ho jaate hain (single transaction)
- **Offline players** ko bhi access de sakte ho — player online hona zaruri nahi hai
  - Player ka naam dalo, server usका UUID resolve kar lega
  - Sirf woh players jinhone kabhi server join kiya hai

### GUI Shared Indicator

Private warps ke GUI item mein ab sharing info dikhta hai:

- **Owner dekhega:** `Shared with: Steve, Alex` (ya count agar >3 players)
- **Shared player dekhega:** `Shared with: 2 players`
- Agar kisi ko share nahi kiya toh sirf `PRIVATE` dikhega

---

## Teleport System

Jab `/warp <name>` ya GUI se teleport karte ho:

### Flow:

```
1. Command enter kiya
   ↓
2. Safe Teleport Check (agar enabled hai)
   → Unsafe location? → CANCEL with error message
   ↓
3. BossBar dikhta hai: "Teleporting... 2s"
   ↓
4. Har second countdown hota hai
   Particles: PORTAL (circle, 6 particles/tick)
   ↓
5. Agar player move kare → CANCEL
   ↓
6. Countdown complete → Teleport!
   Sound: ENTITY_ENDERMAN_TELEPORT
   Particles: END_ROD (12 particles, burst)
```

### Cancellation

- Agar countdown ke dauraan move karoge → teleport cancel
- Detection bahut precise hai (~0.1 block movement se cancel hoga)
- Message dikhega: "Teleport cancelled — you moved!"

---

## Safe Teleport

> **NEW** — Plugin check karta hai ki destination safe hai ya nahi before teleporting.

Config mein enable/disable:
```yaml
teleport:
  safe-check: true   # true = check karo, false = skip karo
```

### Kya check hota hai?

| Check | Description |
|---|---|
| Block neeche solid hai? | Player girega nahi (void protection) |
| Feet level pe solid nahi? | Player block mein stuck nahi hoga |
| Head level pe solid nahi? | Suffocation se bachao |
| Lava/Fire nahi hai? | Damage se bachao |

Agar unsafe hai toh message aayega: **"Destination is not safe!"** aur teleport cancel ho jayega.

### World Validation

Plugin startup pe bhi check karta hai — agar kisi warp ki world load/exist nahi karti toh:
- Warp skip ho jaata hai (load nahi hota)
- Console mein warning dikhta hai: `Warp 'xyz' skipped because world 'abc' does not exist`
- Database se delete nahi hota — jab world wapas load hogi toh warp bhi aa jayega

---

## Preview System

### Flow:

```
1. /warp preview spawn
   ↓
2. Current location save hoti hai
   ↓
3. Gamemode → SPECTATOR
   ↓
4. Warp location pe teleport
   ↓
5. Action bar: "Preview: 9s remaining"
   ↓
6. 10 seconds baad automatic restore
   ↓
7. Original location pe wapas
8. Original gamemode restore (Survival/Creative etc.)
```

### Auto-Cancel Conditions:
- Timer expire hone pe
- Player disconnect karne pe (location + gamemode restore hota hai reconnect pe)
- Server shutdown pe (sabke preview restore hote hain)

---

## Warp Analytics

Plugin automatically track karta hai:

| Metric | Description |
|---|---|
| `usage_count` | Kitni baar warp use hua |
| `last_used` | Aakhri baar kab use hua |

### View analytics:

```
/warp stats <name>    → Individual warp stats
/warp top             → Top 5 most-used warps
```

---

## Backup & Export

> **NEW** — Warps ko JSON format mein backup aur restore karo.

### Export

```
/warp export
```

- Saare warps (public + private) ek JSON file mein save hote hain
- File location: `plugins/PixelWarp/backups/warps_YYYY-MM-DD_HH-MM-SS.json`
- Sirf **server owners** use kar sakte hain

### Import

```
/warp import                              → Available backups ki list dekho
/warp import warps_2026-03-04_14-30-00.json  → Import karo
```

- Sirf naye warps import hote hain (existing warps skip hote hain → safe merge)
- Import ke baad message aayega: "Imported X new warp(s)"
- Agar koi warps skip hue toh unke naam bhi dikhenge (e.g. "Skipped 3 warp(s): spawn, shop, farm")
- Access sharing data bhi import/export hoti hai (`shared_with` field)
- Sirf **server owners** use kar sakte hain

### JSON Format (Reference)

```json
[
  {
    "name": "spawn",
    "owner_uuid": "12345678-1234-1234-1234-123456789abc",
    "world": "world",
    "x": 100.5, "y": 64.0, "z": -200.5,
    "yaw": 90.0, "pitch": 0.0,
    "is_public": true,
    "icon_material": "COMPASS",
    "category": "SPAWN",
    "usage_count": 42,
    "created_at": "2026-03-03T10:30:00Z",
    "shared_with": [
      "abcdef12-3456-7890-abcd-ef1234567890"
    ]
  }
]
```

### Use Cases:
- Server migration ke liye warps transfer karo
- Backup before major changes
- Multiple servers ke beech warps share karo

---

## Particle Effects

### Warp Location Markers

Jab player kisi public warp ke paas hota hai (default: 10 blocks ke andar):

- **Particle:** END_ROD
- **Count:** 4 particles (small ring)
- **Interval:** Every 20 ticks (1 second)
- **Shape:** Small circle (0.4 block radius) at warp location + 1.5 blocks height

### Performance

- **Chunk-based spatial indexing** — har player ke liye sirf 9 nearby chunks check hote hain
- Kabhi bhi saare warps loop nahi karte har player ke liye
- Maximum 5 particles per warp per tick

### Disable karna hai?

```yaml
particles:
  warp-marker:
    enabled: false
```

---

## Build from Source

### Prerequisites

- Java 21+ installed
- Internet connection (dependencies download hone ke liye)

### Build Steps

```bash
# Clone / navigate to project
cd PixelWarp

# Build (Windows)
.\gradlew.bat build

# Build (Linux/Mac)
./gradlew build

# Output JAR location:
# build/libs/PixelWarp-1.0.0.jar
```

### Dependencies (auto-managed by Gradle)

| Dependency | Purpose |
|---|---|
| Paper API 1.21.1 | Minecraft server API |
| HikariCP 5.1.0 | MySQL connection pooling (shaded into JAR) |
| Gson 2.11.0 | JSON export/import for backup system (shaded into JAR) |

> HikariCP automatically relocate hota hai `com.pixelwarp.lib.hikari` mein — dusre plugins se conflict nahi karega.

---

## Project Structure

```
src/main/java/com/pixelwarp/
├── WarpPlugin.java                 # Main plugin class — lifecycle, config, init
│
├── warp/
│   ├── Warp.java                   # Warp data model
│   ├── WarpCategory.java           # Category enum (Spawn/Shops/Bases/Events/PlayerWarps)
│   ├── WarpManager.java            # In-memory cache + business logic (access-aware)
│   └── WarpStorage.java            # Async MySQL CRUD (+ update/rename operations)
│
├── database/
│   └── MySQL.java                  # HikariCP connection pool + table creation
│
├── commands/
│   ├── WarpCommand.java            # /warp, /warps, preview, stats, top, edit, rename, access, export, import
│   └── SetWarpCommand.java         # /setwarp, /delwarp (with confirmation system)
│
├── gui/
│   ├── WarpMenu.java               # Main paginated warp chest GUI (with sorting)
│   ├── CategoryMenu.java           # Category selection GUI
│   ├── SearchMenu.java             # Anvil-based search input
│   └── MenuListener.java           # All inventory event handling (+ sort button)
│
├── teleport/
│   └── TeleportAnimation.java      # Countdown, BossBar, particles, safe-check, cancel-on-move
│
├── preview/
│   └── PreviewManager.java         # Spectator preview with auto-return
│
├── particles/
│   └── WarpParticleTask.java       # Chunk-indexed proximity particle markers
│
├── access/
│   └── WarpAccessManager.java      # Private warp sharing (CRUD + cache)
│
├── backup/
│   └── WarpBackupManager.java      # JSON export/import system
│
└── util/
    ├── MessageUtil.java            # Adventure API message helpers
    └── SafeTeleportCheck.java      # Block safety validation utility

src/main/resources/
├── plugin.yml                      # Plugin metadata + command registration
└── config.yml                      # Default configuration
```

---

## Troubleshooting

### Plugin load nahi ho raha

**Check:**
1. Paper 1.21.1+ use kar rahe ho? Spigot/CraftBukkit kaam nahi karega
2. Java 21+ installed hai?
3. Console mein error message dekho

### MySQL connection fail

**Check:**
1. MySQL server running hai?
2. Database exist karta hai? (`CREATE DATABASE pixelwarp;`)
3. Username/password sahi hai?
4. Host/port sahi hai?
5. MySQL user ko privileges hain? (`GRANT ALL PRIVILEGES...`)

```
# MySQL status check karo
mysql -u root -p -e "SHOW DATABASES;"
```

### Warps load nahi ho rahe

- Server console mein `"Loaded X warps from database"` message check karo
- Agar 0 dikhta hai toh database mein data check karo:
```sql
SELECT * FROM warps;
```

### GUI kaam nahi kar raha

- Sirf Paper server pe kaam karega (Spigot pe `AnvilView` nahi milega)
- Check karo ki koi aur plugin inventory events cancel toh nahi kar raha

### Teleport cancel ho jata hai

- Countdown ke dauran hilna mat — ekdum still raho
- Check karo ki koi anti-cheat plugin teleport block toh nahi kar raha

### Particles nahi dikh rahe

- Config mein `enabled: true` hai?
- Sirf **public** warps ke paas dikhte hain
- **10 blocks** ke andar hona chahiye (default)
- Particle settings check karo: Video Settings → Particles → All

### "Destination is not safe!" error

- Warp location pe lava, fire, ya void hai
- Ya warp location ke neeche solid block nahi hai
- Fix: Warp owner `/warp edit <name> location` use karke safe jagah pe update kare
- Ya config mein `teleport.safe-check: false` kardo (not recommended)

### Warp load nahi ho raha — "world does not exist"

- Warp ki world server pe load nahi hai
- World folder check karo server directory mein
- Multiverse/world management plugin se world load karo
- Warp database se delete nahi hota — jab world wapas aayegi toh automatic load hoga

### Delete confirmation expire ho gaya

- 30 seconds ke andar confirm karo
- Dobara `/delwarp <name>` run karo
- Ya chat mein `[CONFIRM DELETE]` button pe click karo

---

## Quick Start Checklist

- [ ] MySQL database banao (`pixelwarp`)
- [ ] JAR ko `plugins/` mein daalo
- [ ] Server start karo (config generate hone do)
- [ ] `config.yml` mein MySQL credentials daalo
- [ ] `config.yml` mein apna UUID daalo (server-owners)
- [ ] Server restart karo
- [ ] `/setwarp spawn SPAWN public` — pehla warp banao!
- [ ] `/warps` — GUI check karo
- [ ] Enjoy! 🎮

---

*PixelWarp v1.0.0 — Made for private SMP servers*

### What's New (Phase 2)

- ✅ Delete confirmation system (30s expiry + clickable buttons)
- ✅ Warp edit (`/warp edit` — location, category, visibility)
- ✅ Warp rename (`/warp rename`)
- ✅ Safe teleport check (lava, fire, void, suffocation protection)
- ✅ World validation on startup (missing worlds skip with warning)
- ✅ Private warp sharing (`/warp access add|remove|list`)
- ✅ GUI sorting (Alphabetical / Most Used / Newest)
- ✅ Warp backup/export to JSON (`/warp export`)
- ✅ Warp import from JSON (`/warp import`)
- ✅ Owners can now delete their own warps (public + private)

### What's New (Phase 3 — Reliability & Data Safety)

- ✅ Transactional warp rename — warps + access entries update in single DB transaction (no orphaned data)
- ✅ Transactional warp delete — warps + access entries delete in single DB transaction
- ✅ Import feedback — skipped warp names now listed individually in chat
- ✅ Warp name length increased from 32 → 64 characters (matches DB column)
- ✅ Precise move detection during teleport countdown (~0.1 block threshold instead of 0.5)
- ✅ Export now includes `shared_with` access data per warp
- ✅ Import restores `shared_with` access entries automatically
- ✅ Access cache verified as cache-first pattern (instant reads, async DB writes)

### What's New (Phase 4 — Architecture & Performance)

- ✅ **Warp ID based access** — `warp_access` table now uses `warp_id` (INT) instead of `warp_name` for lookups
  - Automatic migration on startup: adds column, backfills existing data
  - `warp_name` kept for backward compatibility (not removed)
  - Rename no longer needs to update access entries — warp_id is stable
- ✅ **Offline player access** — `/warp access add/remove` now works with offline players
  - No longer requires target player to be online
  - Resolves UUID from server's player cache
  - Validates player has joined the server before
- ✅ **GUI shared indicator** — Private warp items now show sharing info in lore
  - Owner sees: `Shared with: Steve, Alex` (names for ≤3, count for >3)
  - Shared player sees: `Shared with: N players`
- ✅ **Category index performance** — Warps pre-indexed by category
  - `Map<WarpCategory, List<Warp>>` maintained automatically
  - GUI fetches from index instead of scanning full warp list
  - O(category_size) instead of O(total_warps) per GUI open
  - Index updated on create/delete/category-edit
