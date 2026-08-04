# Codebase Guide: `liberty-application-deployment`

> **Purpose**: Deep, codebase-grounded knowledge of Liberty's application deployment architecture — ApplicationManager, state machine lifecycle, configuration flow, classloading hierarchy, and app-type extensibility. Enables critical reasoning about deployment failures, classloader isolation, new app types, and app lifecycle integration.
>
> All paths are relative to `dev/` inside the Open Liberty repository root.

---

## 1. Domain Overview

Liberty's application deployment system is built around a pluggable, state-machine-driven model. The core principle is: **the application manager does not know what kind of application it is deploying**. Instead, each application type (WAR, EAR, Spring Boot JAR, RAR, etc.) registers an `ApplicationHandler` and a `DeployedAppInfoFactory` as OSGi services. The core framework drives the lifecycle; the type-specific handler provides the install/uninstall logic.

This design solves several problems: it avoids monolithic deployment code, allows new app types to be added as features, ensures deployment failures don't destabilize the runtime (the state machine handles errors), and enables incremental update (an app can be redeployed while the server continues serving other apps).

**Key bundles**:

| Bundle | Role |
|--------|------|
| `com.ibm.ws.app.manager` | Core framework: `ApplicationConfigurator`, `ApplicationManager`, state machine, `ApplicationHandler` SPI |
| `com.ibm.ws.app.manager.module` | Base classes for deployed apps: `DeployedAppInfoFactory`, `DeployedAppInfoBase`, module info types |
| `com.ibm.ws.app.manager.lifecycle` | Lifecycle SPIs: `ApplicationRecycleCoordinator`, `ApplicationPrereq`, `ApplicationStartBarrier` |
| `com.ibm.ws.app.manager.war` | WAR and EAR implementation: `WARApplicationHandlerImpl`, `EARApplicationHandlerImpl`, `WARDeployedAppInfoFactoryImpl`, `EARDeployedAppInfoFactoryImpl` |
| `com.ibm.ws.app.manager.springboot` | Spring Boot JAR deployer: `SpringBootHandler`, `SpringBootApplicationFactory` |
| `com.ibm.ws.app.manager.ejb` | EJB module deployment support |
| `com.ibm.ws.app.manager.rar` | RAR (resource adapter) deployment support |
| `com.ibm.ws.classloading` | Application classloader construction: `ClassLoadingService`, `ClassLoaderConfiguration`, `GatewayConfiguration` |
| `com.ibm.ws.classloading.bells` | BELL service registration (used by shared library classloading) |

---

## 2. Core Architecture & Design Patterns

### 2.1 ApplicationConfigurator — Config Admin ManagedServiceFactory

**What it is**: `ApplicationConfigurator` is a DS `@Component` that implements OSGi `ManagedServiceFactory` for the `application`, `webApplication`, `ejbApplication`, `enterpriseApplication`, and `springBootApplication` config PIDs. When Config Admin delivers a new or updated `Configuration` for any of these PIDs, `ApplicationConfigurator.updated()` is called. It creates or updates an `ApplicationStateMachine` instance for each deployed application.

**Why this design**: A `ManagedServiceFactory` (as opposed to `ManagedService`) handles *multiple instances* of the same PID — one per `<application id="...">` element in `server.xml`. This is how Liberty supports deploying multiple applications from a single server.xml without any enumeration logic.

**PID key format**: Factory instance PIDs take the form `<basePID>~<instanceId>`, e.g. `com.ibm.ws.webApplication~myApp`. Config Admin creates one `Configuration` object per unique `~id` suffix. `ApplicationConfigurator` maps each such PID to a separate `ApplicationStateMachineImpl`, keyed by the `id` portion. This means adding a second `<webApplication id="myApp2"/>` to `server.xml` triggers a second `updated()` call with a new PID, creating a new state machine — with zero change to the framework code.

**Key entry point**: `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/ApplicationConfigurator.java`
See `updated(String pid, Dictionary<String, ?> properties)` for the path from config delivery to state machine creation.

### 2.2 Application State Machine

**What it is**: Each deployed application has an `ApplicationStateMachineImpl` that governs its lifecycle. The state machine transitions through defined states in response to actions. State transitions execute `Action` objects (e.g., `StartAction`, `StopAction`, `DownloadFileAction`) asynchronously on a shared executor thread pool.

**States**:
```
INITIAL → STARTING → STARTED
                     → FAILED
          STOPPING   → STOPPED
                     → REMOVED
```

**Actions** that drive transitions:
- `CONFIGURE` — config delivered; triggers resolution and start
- `START` — explicit start (e.g., via JMX MBean)
- `STOP` — explicit stop
- `RESTART` — stop then start
- `REMOVE` — app removed from config; deregister and clean up

**Why a state machine**: Application lifecycle involves asynchronous operations (file download, archive expansion, CDI scanning). A state machine provides atomicity guarantees — no matter what order external events arrive (config update, file change, JMX stop), the app always ends up in a consistent state. The `ApplicationStateMachineImpl` uses a `ConcurrentLinkedQueue` of pending actions and processes them serially.

**`DownloadFileAction` — the location resolution step**: Before any deployment can happen, the application's `location` attribute must be resolved to an actual file path. `DownloadFileAction` handles this: it calls `WsLocationAdmin` to resolve relative paths (relative to `${server.config.dir}/apps/` by default), and supports remote URLs (http/https) for lazy download. If the file does not exist, the state machine stays in `STARTING` state and periodically retries — this is how Liberty handles dropins that haven't arrived yet.

**Key entry points**:
- `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/statemachine/ApplicationStateMachineImpl.java` — see the `InternalState` enum and `run()` for the action dispatch loop
- `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/statemachine/StartAction.java` — action that invokes `ApplicationHandler.install()`
- `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/statemachine/StopAction.java` — action that invokes `ApplicationHandler.uninstall()`
- `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/statemachine/DownloadFileAction.java` — location resolution; `WsLocationAdmin` path expansion and remote fetch

### 2.3 ApplicationHandler — Pluggable App Type Pattern

**What it is**: `ApplicationHandler<T>` is the SPI that each app type implements. The generic parameter `T` is the type of `DeployedAppInfo` the handler creates. `ApplicationStateMachineImpl` calls `handler.install(applicationInformation)` and `handler.uninstall(applicationInformation)` at the appropriate state transitions. The handler returns `Future<Boolean>` — deployment is asynchronous.

**Why pluggable**: The app manager framework knows nothing about WAR, EAR, Spring Boot, or RAR file formats. Type-specific handlers are registered as DS components. When a new application type feature is loaded (e.g., `springBoot-3.0`), its handler registers automatically; when removed, it deregisters. The framework selects the right handler by matching the `ApplicationTypeSupported` service property on each handler against the type declared in the app config.

**Handler selection precedence**: `ApplicationConfigurator` resolves the handler using the `type` attribute on the `<application>` element (e.g., `type="war"`). If `type` is not specified, it is inferred from the archive's file extension (`.war` → WAR handler; `.ear` → EAR handler; `.jar` → Spring Boot handler if `springBoot` feature is present). The handler matching uses OSGi filter expressions on the `ApplicationTypeSupported` service property.

**Key entry points**:
- `com.ibm.ws.app.manager/src/com/ibm/wsspi/application/handler/ApplicationHandler.java` — SPI interface; `install()`, `uninstall()`, `setUpApplicationMonitoring()`
- `com.ibm.ws.app.manager/src/com/ibm/wsspi/application/handler/ApplicationInformation.java` — carries the app's config, archive container, and type-specific data object
- `com.ibm.ws.app.manager.war/src/com/ibm/ws/app/manager/war/internal/WARApplicationHandlerImpl.java` — WAR handler; see `install()` for the WAR-specific deployment path
- `com.ibm.ws.app.manager.war/src/com/ibm/ws/app/manager/ear/internal/EARApplicationHandlerImpl.java` — EAR handler; coordinates multiple module deployments

### 2.4 Classloading Architecture — Gateway Between OSGi and Applications

**What it is**: Application classloaders exist outside the OSGi bundle classloader graph. An application's classes are loaded by a Liberty `AppClassLoader` that delegates to its parent (usually the JVM bootstrap classloader or a shared library classloader) and can also "reach back" into the OSGi bundle space for Liberty API packages. The gateway between OSGi space and application space is managed by `GatewayConfiguration`.

**Classloader hierarchy**:
```
Bootstrap ClassLoader (JDK)
  └─ JDK Extension ClassLoader
       └─ Liberty runtime (OSGi Equinox)
            ├─ Feature bundle classloaders (isolated OSGi bundles)
            │    └─ API gateway: packages listed in IBM-API-Package only
            └─ Application ClassLoader (per WAR or EAR root)
                 ├─ apiTypeVisibility governs which IBM-API-Package types are visible
                 └─ Module ClassLoaders (per EAR module — web, EJB, client)
                      └─ share EAR parent; see each other via EAR classloader
```

**`apiTypeVisibility`** controls which Liberty package types are accessible from application code. Default is `api,ibm-api,spec,third-party`. Adding `stable` exposes stable-but-not-spec packages. Adding `internal` exposes Liberty internals — strongly discouraged as it breaks zero-migration guarantees.

**`parentType`** controls delegation order:
- `PARENT_FIRST` (default): delegate to parent classloader first, then search the application archive. Standard Java parent-first delegation.
- `PARENT_LAST`: search the application archive first, then delegate to parent. Used when the application must override a library version that Liberty provides — a common pattern with older applications that bundle their own copy of a spec API JAR.

**Shared libraries**: `<library id="myLib">` creates a classloader shared across all applications that reference it via `<classloader commonLibraryRef="myLib"/>`. The shared library classloader is created once and reused, saving memory. When the `<library>` config changes (e.g., a JAR is updated), the `LibraryChangeListener` SPI notifies applications that hold a reference, triggering `ApplicationRecycleCoordinator` to recycle affected apps.

**Key entry points**:
- `com.ibm.ws.classloading/src/com/ibm/wsspi/classloading/ClassLoadingService.java` — creates application classloaders; `createTopLevelClassLoader()` is the entry point for each app
- `com.ibm.ws.classloading/src/com/ibm/wsspi/classloading/ClassLoaderConfiguration.java` — builder for classloader settings; `setParentType()`, `setApiTypeVisibility()`, `setProtectionDomain()`
- `com.ibm.ws.classloading/src/com/ibm/wsspi/classloading/GatewayConfiguration.java` — controls which packages cross the OSGi→app classloader boundary

### 2.5 Dropins and Application Monitoring

**What it is**: The `dropins/` directory provides a zero-config deployment mechanism: drop a WAR/EAR/JAR file into `${server.config.dir}/dropins/` and Liberty deploys it automatically. `DropinMonitor` implements this by synthesizing Config Admin `Configuration` objects from filesystem events — making dropins deployments indistinguishable from `server.xml`-declared deployments from the state machine's perspective.

**`DropinMonitor` mechanics**: `DropinMonitor` is a `FileMonitor` that watches the `dropins/` directory for create/modify/delete events. On file creation, it calls `ApplicationConfigurator` with a synthesized `<webApplication>` configuration. On deletion, it calls `ApplicationConfigurator.deleted()`. The application then flows through the normal state machine lifecycle. This means all the same timeout, classloading, and lifecycle behavior applies equally to dropins and server.xml-declared apps.

**Application monitoring** (`<applicationMonitor>`): For deployed apps, `ApplicationMonitor` watches the app archive for changes. When a JAR inside the expanded WAR changes, it triggers `ApplicationHandler.setUpApplicationMonitoring()` and then schedules a `RESTART` action via the state machine. The `updateTrigger` attribute on `<applicationMonitor>` controls the trigger: `polled` (file system polling, default), `mbean` (explicit JMX trigger), or `disabled`.

**Key entry points**:
- `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/monitor/DropinMonitor.java` — watches `dropins/`; synthesizes Config Admin entries on file events
- `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/monitor/ApplicationMonitor.java` — watches individual app archives; triggers reload on change
- `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/monitor/AppMonitorConfigurator.java` — DS component for `<applicationMonitor>` config element

---

## 3. Configuration Flow

```
server.xml:
  <webApplication id="myApp" location="myApp.war" contextRoot="/myApp"/>

Config Admin delivers Configuration(PID="com.ibm.ws.webApplication", id="myApp")
  → ApplicationConfigurator.updated("com.ibm.ws.webApplication~myApp", props)
  → Creates ApplicationConfig from props (location, contextRoot, startAfterRef, etc.)
  → Creates or updates ApplicationStateMachineImpl for "myApp"

State machine queues CONFIGURE action
  → DownloadFileAction: resolves location path via WsLocationAdmin
  → StartAction: calls ApplicationHandler.install(applicationInformation)
  → Handler calls DeployedAppInfoFactory.createDeployedAppInfo()
  → DeployedAppInfo creates classloaders, scans metadata, registers with container
  → Future<Boolean> resolves → state transitions to STARTED

Server logs: CWWKZ0001I: Application myApp started in x seconds.
```

**Key config classes**:
- `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/ApplicationConfig.java` — parsed view of the `<application>` config element; holds location, contextRoot, startTimeout, etc.
- `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/ApplicationManager.java` — DS component activated by `<applicationManager>` config; holds server-wide defaults like `expandApps`, `startTimeout`, `stopTimeout`

---

## 4. Classloading Architecture

### 4.1 Classloader Hierarchy

```
Bootstrap ClassLoader
  └─ Extension ClassLoader (JDK)
       └─ Application ClassLoader (JDK system classloader)
            └─ Liberty runtime ClassLoader (OSGi)
                 ├─ Feature bundle classloaders (per OSGi bundle)
                 └─ Application ClassLoader (per deployed app)
                      ├─ inherits API/SPI from feature classloaders
                      │   (governed by IBM-API-Package + apiTypeVisibility)
                      └─ Module ClassLoaders (per EAR module, if EAR)
```

Each deployed WAR or EAR root gets its own `ApplicationClassLoader`. This loader's parent delegation and visibility are configured by `ClassLoaderConfiguration`, which specifies:
- `parentType`: whether to delegate to parent first (`PARENT_FIRST`) or last (`PARENT_LAST`)
- `apiTypeVisibility`: which API types (`api`, `ibm-api`, `spec`, `third-party`) are visible from Liberty feature bundles
- References to `<library>` elements whose JARs are added to the classpath

### 4.2 ClassLoadingService

`ClassLoadingService` is the OSGi service that `DeployedAppInfoBase` calls to create application classloaders. It takes a `ClassLoaderConfiguration` and returns a `ClassLoader` wired into the feature bundle graph.

**Key entry points**:
- `com.ibm.ws.classloading/src/com/ibm/wsspi/classloading/ClassLoadingService.java` — SPI; `createTopLevelClassLoader()` builds the application's root classloader
- `com.ibm.ws.classloading/src/com/ibm/wsspi/classloading/ClassLoaderConfiguration.java` — builder for classloader settings; `setParentType()`, `setApiTypeVisibility()`, `setLibraries()`
- `com.ibm.ws.classloading/src/com/ibm/wsspi/classloading/GatewayConfiguration.java` — configures the "gateway" between OSGi bundle space and the application classloader; governs which packages cross the boundary

### 4.3 Shared Libraries

`Library` objects represent reusable JAR sets that can be referenced by multiple applications or DS components. The classloading system creates a shared `ClassLoader` for each library, then wires it into each application's classloader that references it.

**Key entry points**:
- `com.ibm.ws.classloading/src/com/ibm/wsspi/library/Library.java` — SPI; represents a `<library>` config element
- `com.ibm.ws.classloading/src/com/ibm/wsspi/library/LibraryChangeListener.java` — SPI for components that need to react when a `<library>` changes (e.g., a JAR is updated)

---

## 5. Key Entry Points

### 5.1 Core App Manager Framework

| Class | Path | What to look for |
|-------|------|------------------|
| `ApplicationConfigurator` | `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/ApplicationConfigurator.java` | `ManagedServiceFactory.updated()` is the entry point from Config Admin; creates/updates `ApplicationStateMachineImpl` per app instance |
| `ApplicationManager` | `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/ApplicationManager.java` | DS component for `<applicationManager>` element; holds `expandApps`, `startTimeout`, `stopTimeout` defaults |
| `ApplicationConfig` | `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/ApplicationConfig.java` | Parsed config for one app instance; provides `getLocation()`, `getContextRoot()`, etc. |

### 5.2 State Machine

| Class | Path | What to look for |
|-------|------|------------------|
| `ApplicationStateMachineImpl` | `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/statemachine/ApplicationStateMachineImpl.java` | State machine core; `InternalState` enum, `StateChangeAction` enum, action queue processing in `run()` |
| `ApplicationStateMachine` | `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/statemachine/ApplicationStateMachine.java` | Abstract base; defines the public interface for start/stop/remove |
| `StartAction` | `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/statemachine/StartAction.java` | Calls `ApplicationHandler.install()`; handles Future<Boolean> result |
| `StopAction` | `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/statemachine/StopAction.java` | Calls `ApplicationHandler.uninstall()`; handles async completion |
| `DownloadFileAction` | `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/statemachine/DownloadFileAction.java` | Resolves app location; handles remote and local file resolution |
| `Action` | `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/statemachine/Action.java` | Base interface for all state machine actions |

### 5.3 Application Handler SPI

| Class | Path | What to look for |
|-------|------|------------------|
| `ApplicationHandler` | `com.ibm.ws.app.manager/src/com/ibm/wsspi/application/handler/ApplicationHandler.java` | SPI; `install()` and `uninstall()` return `Future<Boolean>`; `setUpApplicationMonitoring()` for change watch setup |
| `ApplicationInformation` | `com.ibm.ws.app.manager/src/com/ibm/wsspi/application/handler/ApplicationInformation.java` | Data carrier; `getContainer()` for archive content; `getConfiguration()` for config map; `setData()`/`getData()` for handler-specific state |
| `ApplicationTypeSupported` | `com.ibm.ws.app.manager/src/com/ibm/wsspi/application/handler/ApplicationTypeSupported.java` | Annotation used on `ApplicationHandler` DS components to declare which app type(s) they support |
| `ApplicationState` | `com.ibm.ws.app.manager/src/com/ibm/wsspi/application/ApplicationState.java` | Enum: `STARTING`, `STARTED`, `STOPPED`, `FAILED`; the externally observable state |
| `Application` | `com.ibm.ws.app.manager/src/com/ibm/wsspi/application/Application.java` | SPI representing a deployed application; `start()`, `stop()` (used by JMX MBean) |
| `ApplicationMBean` | `com.ibm.ws.app.manager/src/com/ibm/websphere/application/ApplicationMBean.java` | JMX MBean interface; `start()`, `stop()`, `restart()`, `getState()` |

### 5.4 DeployedAppInfo / Module Framework

| Class | Path | What to look for |
|-------|------|------------------|
| `DeployedAppInfoFactory` | `com.ibm.ws.app.manager.module/src/com/ibm/ws/app/manager/module/DeployedAppInfoFactory.java` | SPI; `createDeployedAppInfo()` called by the handler; one implementation per app type |
| `DeployedAppInfoBase` | `com.ibm.ws.app.manager.module/src/com/ibm/ws/app/manager/module/internal/DeployedAppInfoBase.java` | Abstract base for all `DeployedAppInfo` implementations; handles classloader creation, module registration |
| `SimpleDeployedAppInfoBase` | `com.ibm.ws.app.manager.module/src/com/ibm/ws/app/manager/module/internal/SimpleDeployedAppInfoBase.java` | Simpler base for single-module apps (WAR); used by `WARDeployedAppInfo` |
| `DeployedAppInfoFactoryBase` | `com.ibm.ws.app.manager.module/src/com/ibm/ws/app/manager/module/internal/DeployedAppInfoFactoryBase.java` | Common base for factory implementations; provides access to shared services (ClassLoadingService, etc.) |
| `WebModuleInfoImpl` | `com.ibm.ws.app.manager.module/src/com/ibm/ws/app/manager/module/internal/WebModuleInfoImpl.java` | Represents one web module's metadata (context root, virtual host, classloader) |

### 5.5 WAR & EAR Deployment

| Class | Path | What to look for |
|-------|------|------------------|
| `WARApplicationHandlerImpl` | `com.ibm.ws.app.manager.war/src/com/ibm/ws/app/manager/war/internal/WARApplicationHandlerImpl.java` | WAR `ApplicationHandler`; see `install()` for WAR-specific deploy path |
| `WARDeployedAppInfoFactoryImpl` | `com.ibm.ws.app.manager.war/src/com/ibm/ws/app/manager/war/internal/WARDeployedAppInfoFactoryImpl.java` | Creates `WARDeployedAppInfo`; registered as DS `@Component(service=DeployedAppInfoFactory.class)` with WAR type property |
| `WARDeployedAppInfo` | `com.ibm.ws.app.manager.war/src/com/ibm/ws/app/manager/war/internal/WARDeployedAppInfo.java` | WAR-specific deployed app; creates the application classloader and registers with the web container |
| `EARApplicationHandlerImpl` | `com.ibm.ws.app.manager.war/src/com/ibm/ws/app/manager/ear/internal/EARApplicationHandlerImpl.java` | EAR handler; coordinates deployment of all contained modules (web, EJB, client) |
| `EARDeployedAppInfoFactoryImpl` | `com.ibm.ws.app.manager.war/src/com/ibm/ws/app/manager/ear/internal/EARDeployedAppInfoFactoryImpl.java` | Creates `EARDeployedAppInfo` with support for multi-module composition |
| `EARStructureHelper` | `com.ibm.ws.app.manager.war/src/com/ibm/ws/app/manager/ear/internal/EARStructureHelper.java` | Parses `application.xml`; identifies modules and their types within the EAR |

### 5.6 Spring Boot Deployment

| Class | Path | What to look for |
|-------|------|------------------|
| `SpringBootHandler` | `com.ibm.ws.app.manager.springboot/src/com/ibm/ws/app/manager/springboot/internal/SpringBootHandler.java` | `ApplicationHandler` for Spring Boot fat JARs; handles the nested JAR classloader unwrapping |
| `SpringBootApplicationFactory` | `com.ibm.ws.app.manager.springboot/src/com/ibm/ws/app/manager/springboot/internal/SpringBootApplicationFactory.java` | `DeployedAppInfoFactory` for Spring Boot; coordinates Liberty embedded container activation |
| `SpringBootThinUtil` | `com.ibm.ws.app.manager.springboot/src/com/ibm/ws/app/manager/springboot/util/SpringBootThinUtil.java` | Utility to convert fat JAR to thin JAR + lib index (for `springBootUtility thin` command) |

### 5.7 Application Monitor

| Class | Path | What to look for |
|-------|------|------------------|
| `DropinMonitor` | `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/monitor/DropinMonitor.java` | Watches the `dropins/` directory; creates synthetic `<application>` config entries when files appear |
| `ApplicationMonitor` | `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/monitor/ApplicationMonitor.java` | Watches individual app archives for changes; triggers reload via `UpdateHandler` |
| `AppMonitorConfigurator` | `com.ibm.ws.app.manager/src/com/ibm/ws/app/manager/internal/monitor/AppMonitorConfigurator.java` | DS component activated by `<applicationMonitor>` config; configures polling interval and update triggers |

### 5.8 Classloading

| Class | Path | What to look for |
|-------|------|------------------|
| `ClassLoadingService` | `com.ibm.ws.classloading/src/com/ibm/wsspi/classloading/ClassLoadingService.java` | Creates application classloaders; `createTopLevelClassLoader()` is the entry point for each app |
| `ClassLoaderConfiguration` | `com.ibm.ws.classloading/src/com/ibm/wsspi/classloading/ClassLoaderConfiguration.java` | Builder for classloader configuration; `setParentType()`, `setApiTypeVisibility()`, `setProtectionDomain()` |
| `GatewayConfiguration` | `com.ibm.ws.classloading/src/com/ibm/wsspi/classloading/GatewayConfiguration.java` | Controls what packages cross the OSGi→app classloader boundary |
| `ApiType` | `com.ibm.ws.classloading/src/com/ibm/wsspi/classloading/ApiType.java` | Enum: `API`, `IBM_API`, `SPEC`, `THIRD_PARTY`, `STABLE`; maps to `apiTypeVisibility` config values |
| `Library` | `com.ibm.ws.classloading/src/com/ibm/wsspi/library/Library.java` | Represents a `<library>` element; `getClassLoader()` provides the shared library classloader |

### 5.9 Lifecycle SPIs

| Class | Path | What to look for |
|-------|------|------------------|
| `ApplicationRecycleCoordinator` | `com.ibm.ws.app.manager.lifecycle/src/com/ibm/wsspi/application/lifecycle/ApplicationRecycleCoordinator.java` | SPI for components that need to trigger app restart when their configuration changes |
| `ApplicationPrereq` | `com.ibm.ws.app.manager.lifecycle/src/com/ibm/wsspi/application/lifecycle/ApplicationPrereq.java` | SPI; components implement this to declare they are prerequisites for applications to start |
| `ApplicationStartBarrier` | `com.ibm.ws.app.manager.lifecycle/src/com/ibm/wsspi/application/lifecycle/ApplicationStartBarrier.java` | SPI; holds app startup until all registered `ApplicationPrereq` services are satisfied |

---

## 6. Design Decisions & Gotchas

**Q: Why does the state machine use a `ConcurrentLinkedQueue` for actions rather than a simple synchronized block?**  
A: Application lifecycle events can arrive from multiple threads simultaneously — config updates (Config Admin thread), file change events (File Monitor thread), JMX operations (request thread). The action queue serializes these events without blocking the producers. The state machine processes one action at a time from a dedicated executor thread, ensuring consistent state transitions.

**Q: Why does `ApplicationHandler.install()` return `Future<Boolean>` instead of blocking?**  
A: CDI scanning, annotation processing, and web container registration can take hundreds of milliseconds to seconds. Blocking the state machine's processing thread would stall other applications that need to start. The `Future`-based model allows the state machine to start multiple applications in parallel (governed by `<applicationManager maxAcrossAppRestart="..."/>`) while each individual app progresses through its own async deployment.

**Q: Why is `dropins/` implemented as a synthetic config injection rather than a special code path?**  
A: `DropinMonitor` translates file system events into Config Admin `Configuration` object creation/deletion. This means the core `ApplicationConfigurator` and state machine code path is exactly the same for dropins apps and server.xml-declared apps. No special cases. When a file appears in `dropins/`, it looks to the rest of the system exactly like `<webApplication location="..." />` was added to `server.xml`.

**Q: Why does an EAR deploy its modules as separate `ApplicationStateMachine` instances?**  
A: No — an EAR deploys as a single state machine instance. `EARApplicationHandlerImpl` coordinates all module deployments within a single `install()` call, using the `EARStructureHelper` to parse `application.xml` and identify modules. The modules share the EAR's parent classloader but each get their own module classloader. All modules start or none do (transactional deployment).

**Q: What causes `CWWKZ0004E: An exception occurred while starting the application`?**  
A: The `StartAction` called `ApplicationHandler.install()`, which returned `Future<Boolean>` resolving to `false` or threw an exception. The exception is wrapped and logged. Root causes are typically: classloader failure (missing dependency), annotation scanning exception (malformed descriptor), or container registration failure (context root conflict). Check `messages.log` for the full exception chain immediately following `CWWKZ0004E`.

**Q: What is `ApplicationRecycleCoordinator` used for?**  
A: When a configuration change requires application restart (e.g., a shared library JAR is updated, or a datasource that the app uses is changed), Liberty uses `ApplicationRecycleCoordinator` to safely restart the affected apps. Components that own configuration affecting deployed apps register as `ApplicationRecycleComponent`. When their config changes, they notify the coordinator, which orchestrates an orderly app stop/start cycle.

**Q: Why does `apiTypeVisibility` not include `internal` by default?**  
A: `internal` packages are not intended for application use. Including them by default would make applications depend on Liberty implementation details that can change in any release, breaking the zero-migration guarantee. If an application explicitly sets `apiTypeVisibility="...,internal"`, it is accepting responsibility for those dependencies.

---

## 7. Extension Points

### 7.1 Custom Application Type

To add a new application type (e.g., a new archive format):
1. Create an `ApplicationHandler<T>` implementation in a DS `@Component` with `@ApplicationTypeSupported(name="myType")`
2. Create a `DeployedAppInfoFactory` implementation registered as DS `@Component(service=DeployedAppInfoFactory.class)` with the matching type property
3. Package in a Liberty feature (product extension); declare the feature as requiring `com.ibm.websphere.appserver.appmanager-1.0` (protected feature)

The application manager discovers new handlers and factories automatically when the feature is loaded.

**SPI entry point**: `com.ibm.ws.app.manager.module/src/com/ibm/ws/app/manager/module/DeployedAppInfoFactory.java`

### 7.2 Application Start Prerequisite

Implement `ApplicationPrereq` and register as a DS `@Component`. Application startup will be held until your service is satisfied. Use this for infrastructure that must exist before any application begins serving requests (e.g., a custom data source pool that must be warmed up first).

---

## 8. How to Update This Guide

- **New app type**: When a new application type bundle is added (e.g., a new `com.ibm.ws.app.manager.xxx`), add its key classes to §5.
- **State machine changes**: If new states or actions are added to `InternalState` or `StateChangeAction` enums, update §2.2.
- **Classloading changes**: If `ClassLoaderConfiguration` gains new delegation modes or the `ApiType` enum changes, update §4.
- **New lifecycle SPIs**: Add to §5.9.
- **Verification**:
  ```bash
  find dev -name "ApplicationConfigurator.java" -path "*/src/*"
  find dev -name "ApplicationStateMachineImpl.java" -path "*/src/*"
  find dev -name "WARApplicationHandlerImpl.java" -path "*/src/*"
  find dev -name "DeployedAppInfoFactory.java" -path "*/src/*"
  find dev -name "ClassLoadingService.java" -path "*/src/*"
  find dev -name "SpringBootHandler.java" -path "*/src/*"
  ```

---

## 9. Related Skills & Cross-References

| Skill | Why related |
|-------|-------------|
| `liberty-architecture` | DS lifecycle, Config Admin injection, file monitor — the app manager is built entirely on these kernel services |
| `liberty-extending-spi` | `DeployedAppInfoFactory` and `ApplicationHandler` follow the product extension + DS pattern described there |
| `liberty-server-configuration` | `<application>`, `<applicationManager>`, `<applicationMonitor>`, `<classloader>`, `<library>` config element reference |
| `liberty-security-core` | Web application security enforcement (`WebAppAuthorizationHelper`) wraps the app lifecycle |
| `liberty-web-container` | `WARDeployedAppInfo` registers with the web container after deployment; the web container SPI is the bridge |

---

*Guide last verified against codebase: Open Liberty `3bd33ac9e6e` commit. Verify paths with commands in §8.*
