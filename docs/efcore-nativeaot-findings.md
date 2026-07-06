# EF Core + Sqlite under NativeAOT — three attempts, three failures, precisely why

**Date:** 2026-07-05
**Outcome:** 🛑 **NOT VIABLE**, in three progressively-narrower attempts, each fixing the prior
attempt's proximate cause and still failing at the next layer down. **This is the repo's
designated "documented failure" artifact** for this batch (Task A4 asked for one; this is it, and
it's a genuinely useful failure — precisely because it shows *where* EF Core's NativeAOT support
actually stops, not just that it does somewhere).

**Why this doc exists:** this repo already has a hand-rolled `sqlite-net`/raw-ADO SQLCipher path
(`onnx-nativeaot-ios-findings.md`'s sibling gates, and the Android gate's SQLCipher section) that
works fine under NativeAOT. EF Core was gated as the "would a real ORM be nicer" question, for any
future feature that outgrows raw SQL. The answer, as of these package versions, is no — not without
a much heavier compiled-model + no-migrations investment than a "just add the package" naive
attempt, and even that heavier investment doesn't fully clear the bar.

## TL;DR

- ✅ **Publishes** for `win-x64` `PublishAot=true` in all three attempts below — EF Core's own
  analyzers correctly flag every risky call site with `IL2026`/`IL3050` **member-level** warnings
  (not just assembly roll-ups), which is noticeably better AOT-diagnostic hygiene than protobuf-net
  or ML.NET showed in this same batch.
- 🛑 **Attempt 1 (naive — `OnConfiguring` + `EnsureCreatedAsync`, no compiled model):** fails at
  startup with `System.InvalidOperationException: Model building is not supported when publishing
  with NativeAOT. Use a compiled model.` EF's error message is, refreshingly, exactly correct and
  names the fix.
- 🛑 **Attempt 2 (+ `dotnet ef dbcontext optimize` compiled model, wired via `.UseModel(...)`,
  `EnsureCreatedAsync` still in place):** attempt 1's error is gone — the compiled model unblocks
  runtime model building. But `EnsureCreatedAsync` needs a **second, separate** "design-time model"
  for DDL generation, which a compiled model does **not** cover, and that fails too:
  `System.InvalidOperationException: Design-time DbContext operations are not supported when
  publishing with NativeAOT.`
- 🛑 **Attempt 3 (+ compiled model, `EnsureCreatedAsync` replaced with a raw `CREATE TABLE` via
  `ExecuteSqlRawAsync` to sidestep attempt 2's specific gap):** attempt 2's error is gone. But the
  very first `DbSet<T>.Add(...)` — before any SQL runs — throws **the same message as attempt 1**,
  `Model building is not supported when publishing with NativeAOT. Use a compiled model.`, from a
  **different internal code path** (`RuntimeTypeBase.EmptyShadowValuesFactory`, inside the change
  tracker's `StateManager.GetOrCreateEntry`). The compiled model covers query/schema shape but not
  every lazily-initialized internal factory the change tracker needs.
- **Net verdict: a compiled model narrows the failure surface but does not close it.** Getting
  further would mean abandoning `EnsureCreatedAsync`/migrations entirely (ship schema via raw SQL
  or a pre-built `.db`, as attempt 3 already does) **and** finding/working around whatever else
  needs runtime codegen in the change tracker — which starts to look like fighting the ORM instead
  of using it.

## The spike

`spike/EfGate/EfGate.csproj` (final state — includes the compiled-model attempt):
```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <PublishAot>true</PublishAot>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="Microsoft.EntityFrameworkCore.Design" Version="10.0.9">
      <IncludeAssets>runtime; build; native; contentfiles; analyzers; buildtransitive</IncludeAssets>
      <PrivateAssets>all</PrivateAssets>
    </PackageReference>
    <PackageReference Include="Microsoft.EntityFrameworkCore.Sqlite" Version="10.0.9" />
  </ItemGroup>
</Project>
```
`Microsoft.EntityFrameworkCore.Design` **10.0.9** is only needed for the `dotnet ef` tooling
(`IDesignTimeDbContextFactory<T>`) and is marked `PrivateAssets=all` so it doesn't ship in the
published app. `dotnet-ef` global tool version: **10.0.7** (one patch behind the runtime packages;
harmless, just a tooling nag).

`Program.cs`: a `Note { Id, Title, Body }` entity, a `ManualDbContext(string dbPath) : DbContext`
using `UseSqlite` (naive attempts) or `UseSqlite(...).UseModel(EfGate.CompiledModels.ManualDbContextModel.Instance)`
(compiled-model attempts), and a CRUD sequence: insert 2 rows in one `DbContext` scope, then in a
**second** scope read/update/delete/count — deliberately exercising the "reopen the context" shape
a real app uses, not a single long-lived context.

**Publish command (identical for all three attempts):**
```powershell
dotnet publish -r win-x64 -c Release -p:PublishAot=true -p:IlcUseEnvironmentalTools=true
```
(same MSVC-toolset environment workaround as the sibling gates in this batch — see
`onnx-nativeaot-ios-findings.md` "Toolchain potholes".)

### Attempt 1 — naive

`OnConfiguring` → `options.UseSqlite($"Data Source={dbPath}")`, and `Main` calls
`await db.Database.EnsureCreatedAsync()` before the first insert.

**Publish output (verbatim, key lines):**
```
Program.cs(15,15): warning IL3050: Using member 'Microsoft.EntityFrameworkCore.Infrastructure.DatabaseFacade.EnsureCreatedAsync(CancellationToken)' which has 'RequiresDynamicCodeAttribute' can break functionality when AOT compiling. Migrations operations require building the design-time model which is not supported with NativeAOT Use a migration bundle or an alternate way of executing migration operations.
Program.cs(55): Trim analysis warning IL2026: ManualDbContext.ManualDbContext(String): Using member 'Microsoft.EntityFrameworkCore.DbContext.DbContext()' which has 'RequiresUnreferencedCodeAttribute' can break functionality when trimming application code. EF Core isn't fully compatible with trimming...
Program.cs(55): AOT analysis warning IL3050: ManualDbContext.ManualDbContext(String): ...EF Core isn't fully compatible with NativeAOT, and running the application may generate unexpected runtime failures.
...Microsoft.EntityFrameworkCore.dll : warning IL2104/IL3053 (assembly roll-up)
...Microsoft.EntityFrameworkCore.Relational.dll : warning IL2104/IL3053
...Microsoft.EntityFrameworkCore.Sqlite.dll : warning IL2104/IL3053
ILC : warning IL3002: ...SpatialiteLoader.FindExtension(): ...DependencyContext... not supported in a single-file app...
```
Exit code **0** — publishes despite ~10 distinct warnings, every one of which turned out to be a
true positive.

**Run output (verbatim):**
```
DB: D:\Local\Temp\efgate-98ed006c2f1042da92282334017264d2.db
Unhandled exception. System.InvalidOperationException: Model building is not supported when publishing with NativeAOT. Use a compiled model.
   at Microsoft.EntityFrameworkCore.Internal.DbContextServices.CreateModel(Boolean)
   at Microsoft.EntityFrameworkCore.Internal.DbContextServices.get_Model()
   ... (DI call-site resolution frames) ...
   at Microsoft.EntityFrameworkCore.Infrastructure.DatabaseFacade.EnsureCreatedAsync(CancellationToken)
   at Program.<<Main>$>d__0.MoveNext()
```
Exit code `-1073740791` (`0xC0000409`). Fails before printing anything past the DB path — the
model is built lazily on first access to `db.Database`, which happens at `EnsureCreatedAsync`.

### Attempt 2 — compiled model, `EnsureCreatedAsync` unchanged

Added `IDesignTimeDbContextFactory<ManualDbContext>` (tooling-only class) and ran:
```powershell
dotnet ef dbcontext optimize --output-dir CompiledModels --namespace EfGate.CompiledModels --context ManualDbContext
```
which succeeded cleanly and generated `CompiledModels/{ManualDbContextModel,ManualDbContextModelBuilder,NoteEntityType,ManualDbContextAssemblyAttributes}.cs`
(~5 KB total, committed alongside the probe as evidence of exactly what the generated shape looks
like). Wired in via `OnConfiguring`: `.UseSqlite(...).UseModel(EfGate.CompiledModels.ManualDbContextModel.Instance)`.

**Publish output:** the `IL3050` warning on `EnsureCreatedAsync` (Program.cs:15) **persists
unchanged** — the compiled model doesn't remove it, because the warning is inherent to the method,
not conditional on whether a compiled model is present.

**Run output (verbatim):**
```
DB: D:\Local\Temp\efgate-b31963a05d674976ba93405f1ee7d389.db
Unhandled exception. System.InvalidOperationException: Design-time DbContext operations are not supported when publishing with NativeAOT.
   at Microsoft.EntityFrameworkCore.Internal.DbContextServices.CreateModel(Boolean)
   at Microsoft.EntityFrameworkCore.Internal.DbContextServices.get_DesignTimeModel()
   at Microsoft.EntityFrameworkCore.Storage.RelationalDatabaseCreator.GetCreateTablesCommands(MigrationsSqlGenerationOptions)
   at Microsoft.EntityFrameworkCore.Storage.RelationalDatabaseCreator.CreateTablesAsync(CancellationToken)
   at Microsoft.EntityFrameworkCore.Storage.RelationalDatabaseCreator.<EnsureCreatedAsync>d__18.MoveNext()
```
Progress: attempt 1's exact error is gone (the compiled model **does** satisfy `get_Model()`). But
`EnsureCreatedAsync` internally needs `get_DesignTimeModel()` — a distinct model instance used only
for generating `CREATE TABLE` DDL — and that one has no compiled equivalent; it's unconditionally
unsupported under NativeAOT per EF Core's own message.

### Attempt 3 — compiled model + raw SQL DDL (checked-in final state)

Replaced `EnsureCreatedAsync()` with:
```csharp
await db.Database.ExecuteSqlRawAsync(
    "CREATE TABLE \"Notes\" (\"Id\" INTEGER NOT NULL CONSTRAINT \"PK_Notes\" PRIMARY KEY AUTOINCREMENT, \"Title\" TEXT NOT NULL, \"Body\" TEXT NOT NULL);");
```
to isolate whether the **query/change-tracking** pipeline — the part a compiled model is supposed
to cover — works, independent of migrations/DDL.

**Publish output:** the `IL3050` warning on `EnsureCreatedAsync` is **gone** (that call site no
longer exists); the `ManualDbContext` constructor's `IL2026`/`IL3050` warnings remain (the
`DbContext()` base constructor itself is unconditionally marked `RequiresUnreferencedCode`/
`RequiresDynamicCode` in EF Core 10, compiled model or not).

**Run output (verbatim, reproduced on two separate runs):**
```
DB: D:\Local\Temp\efgate-c9e4de67eace4d629616255739cdbbc9.db
Unhandled exception. System.InvalidOperationException: Model building is not supported when publishing with NativeAOT. Use a compiled model.
   at Microsoft.EntityFrameworkCore.Metadata.RuntimeTypeBase.<>c.<get_EmptyShadowValuesFactory>b__84_0(RuntimeTypeBase structuralType)
   at Microsoft.EntityFrameworkCore.Internal.NonCapturingLazyInitializer.EnsureInitialized[TParam,TValue](TValue&, TParam, Func`2)
   at Microsoft.EntityFrameworkCore.ChangeTracking.Internal.InternalEntryBase..ctor(IRuntimeTypeBase)
   at Microsoft.EntityFrameworkCore.ChangeTracking.Internal.StateManager.GetOrCreateEntry(Object, IEntityType)
   at Microsoft.EntityFrameworkCore.Internal.InternalDbSet`1.EntryWithoutDetectChanges(TEntity)
   at Microsoft.EntityFrameworkCore.Internal.InternalDbSet`1.Add(TEntity)
   at Program.<<Main>$>d__0.MoveNext()
```
The raw-SQL `CREATE TABLE` itself succeeds (no exception there) — the crash is one line later, at
`db.Notes.Add(new Note { ... })`, **before any SQL for the insert is even generated.** Same
top-level message as attempt 1, but from `EmptyShadowValuesFactory`'s lazy initializer inside the
change tracker, not from `DbContextServices.CreateModel`. The compiled model supplies the entity
model shape, but at least one lazily-built internal factory the change tracker needs (a "shadow
values" factory — for properties that exist in the model but not as CLR properties, `Id` in this
schema isn't one, so this triggers even for the simple case) still isn't pre-baked into it and
falls back to the same unsupported runtime-model-building path.

## Mobile-RID caveat

Local NativeAOT can't target `ios-arm64`/`linux-bionic-arm64` on this Windows box, so this
`win-x64` `PublishAot=true` probe is the accepted first-order signal (same ILC, same trimming/
reflection constraints as the mobile RIDs). Every failure captured here is EF Core's own explicit,
documented "not supported with NativeAOT" gate (not a trimming accident or a Windows toolchain
quirk), so there's no reason to expect a different result on `ios-arm64` or `linux-bionic-arm64` —
if anything, EF Core's own docs frame these limitations as NativeAOT-general, not per-RID.

## Verdict

**NOT VIABLE**, for CRUD usage of the kind a typical feature would want (create schema once,
open/close `DbContext` scopes per operation, plain `DbSet<T>` LINQ queries), even with the
officially-documented compiled-model workaround applied. Getting further would require avoiding
`EnsureCreatedAsync`/migrations entirely (already done in attempt 3) **and** identifying and
routing around every other lazily-initialized internal factory the change tracker touches — which
is un-scoped, version-fragile work (tied to `Microsoft.EntityFrameworkCore` **10.0.9** internals
specifically) that starts to cost more than the ORM saves over raw ADO.NET.

**Fallback:** the repo's existing hand-rolled `sqlite-net`/raw-ADO-over-SQLCipher path (already
proven AOT-safe on `win-x64`, `ios-arm64`, and `linux-bionic-arm64` — see
`onnx-nativeaot-ios-findings.md` and `nativeaot-android-gate-findings.md`'s S2 section) remains the
answer for any feature needing local structured storage. If a future feature's query needs genuinely
outgrow hand-written SQL, re-gate EF Core against whatever version ships next with this exact
CRUD shape before reaching for it again — don't assume this verdict is permanent, but don't assume
it's fixed either without re-testing.
