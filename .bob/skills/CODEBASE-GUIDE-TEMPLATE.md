# CODEBASE-GUIDE Template: `<skill-name>`

> **Purpose**: This guide gives Bob deep, codebase-grounded knowledge of `<skill-name>`'s implementation domain. It complements the user-facing SKILL.md by documenting *why* the design exists, *how* components interact, and *where* to look in the code—enabling critical reasoning about new features, spec changes, and edge cases.
>
> **Conciseness target**: 400–600 lines. Focus on foundational architecture that rarely changes.
> **No exhaustive API reference**: readers should understand enough to navigate and reason; not memorize every method.
> **Verified code paths**: every `dev/` path cited must exist in the repository.

---

## 1. Domain Overview

> **What to write here**: 3–5 sentences. What problem does this domain solve in the Liberty runtime? Why does it exist as a distinct component/feature?

*Concisely state the design mandate. Example: "The Feature Manager solves the problem of composable, pay-for-use runtime assembly. Without it, every bundle would always be loaded, making startup slow and footprint large."*

**Key bundles** (paths relative to `dev/`):

| Bundle | Role |
|--------|------|
| `com.ibm.example.bundle` | Primary implementation |
| `com.ibm.example.api` | Public interfaces / SPIs |
| `com.ibm.example.bundle_fat` | Functional acceptance tests |

---

## 2. Core Architecture & Design Patterns

> **What to write here**: Explain the 2–3 dominant patterns in this domain. Use sub-sections. For each pattern, state: *what it is*, *why it was chosen*, and *where to see it* (class + method or line range hint).

### 2.1 Pattern Name (e.g., OSGi Declarative Services Lifecycle)

*Why this pattern*: …

*Key entry point*:
- `dev/<bundle>/src/<package>/ClassName.java` — describe what to look at and why

### 2.2 Pattern Name (e.g., Config Admin Integration)

*Why this pattern*: …

*Key entry points*:
- `dev/<bundle>/src/<package>/ClassName.java:~line` — describe briefly

---

## 3. Configuration Model

> **What to write here**: Trace the path from `server.xml` element → metatype → DS component injection. Use this domain's config elements as the example. Readers will use this to understand how to add new config attributes.

**Flow**:
```
server.xml <element> → ConfigAdmin parses → Configuration object (PID = "com.ibm.x.y")
  → DS @Modified or @Activate called on matching component
  → component re-configures itself live
```

**Metatype location**: `dev/<bundle>/resources/OSGI-INF/metatype/metatype.xml`

**Key PID**: `com.ibm.example.pid`

**Why this design** (config-by-exception): bundles ship defaults in metatype; server.xml only overrides what's needed. This minimizes required config and supports zero-migration (new attributes are additive with defaults).

---

## 4. Key Entry Points

> **What to write here**: List the classes a developer should read to understand this domain. This section may contain up to 30 classes—the domain covered by each skill is broad, and providing comprehensive pointers is more valuable than being terse. One sentence per class explaining what it does and what to look for. Group related classes into logical sub-sections where helpful.

### 4.1 Sub-section (e.g., Core Services)

| Class | Path | What to look for |
|-------|------|------------------|
| `ClassName` | `dev/<bundle>/src/<package>/ClassName.java` | Entry point; see `activate()` for lifecycle start |
| `InterfaceName` | `dev/<bundle>/src/<package>/InterfaceName.java` | SPI contract; all implementations must satisfy this |

### 4.2 Sub-section (e.g., Configuration & Metatype)

| Class | Path | What to look for |
|-------|------|------------------|
| `ImplClass` | `dev/<bundle>/src/<package>/ImplClass.java` | Core algorithm; see `doSomething()` for main logic |

---

## 5. Extension Points & SPIs

> **What to write here**: Where can external code (product extensions, user features) hook into this domain? For each extension point, state: the interface, the manifest header used, and a worked example.

### 5.1 Extension Point Name

**Interface**: `com.ibm.wsspi.example.ExtensionPoint`  
**Location**: `dev/<bundle>/src/com/ibm/wsspi/example/ExtensionPoint.java`  
**How to register**: DS `@Component(service = ExtensionPoint.class)` in a product extension bundle.  
**Manifest header**: `IBM-SPI-Package: com.ibm.wsspi.example`

*Why pluggable*: …

---

## 6. Design Decisions & Gotchas

> **What to write here**: Q&A pairs covering the non-obvious design choices a developer is likely to ask about. Focus on "why" questions, not "what" questions.

**Q: Why does `<Component>` use `configurationPolicy = REQUIRE` instead of OPTIONAL?**  
A: …

**Q: Why is singleton enforcement done at resolution time rather than at bundle install time?**  
A: …

**Q: What happens when a `@Modified` method is not declared on a DS component?**  
A: DS deactivates and re-activates the component, causing a brief service outage. Always declare `@Modified` for components that handle config changes dynamically.

**Q: What is the most common cause of CWWKX0000E in this domain?**  
A: …

---

## 7. How to Update This Guide

> **What to write here**: Maintenance instructions for future editors.

- **Class moves**: If a class is renamed or moved to a new bundle, update §4 (entry points) and any §2 code pointers.
- **New config attributes**: When a new `<AD>` is added to metatype.xml, add it to §3 if it changes the configuration flow.
- **New extension points**: When a new SPI interface is created, add it to §5.
- **Bundle renames**: Update §1 (bundle table) and verify all `dev/` paths are still correct.
- **Verification**: After updating, grep for each cited class path to confirm it still exists:
  ```bash
  find dev -name "ClassName.java" -path "*/src/*"
  ```

---

## 8. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-architecture` | OSGi runtime, DS framework, Config Admin fundamentals |
| `liberty-extending-spi` | How to write product extensions that use these SPIs |

---

*Guide last verified against codebase: see git log for this file.*
