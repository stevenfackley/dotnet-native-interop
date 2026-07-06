// Gate: does EF Core (Sqlite provider) publish + run under NativeAOT?
// This is the repo's designated "documented failure" artifact. Three attempts, each fixing the
// prior failure's proximate cause, all still fail under NativeAOT -- see docs/efcore-nativeaot-findings.md
// for the exact error at each stage:
//   1. Naive (OnConfiguring + EnsureCreatedAsync, no compiled model): fails building the runtime
//      model at all ("Model building is not supported when publishing with NativeAOT").
//   2. + `dotnet ef dbcontext optimize` compiled model wired via UseModel() below: unblocks (1),
//      but EnsureCreatedAsync itself needs a *separate* "design-time model" for DDL generation,
//      which is not covered by a compiled model and still fails.
//   3. + raw SQL DDL instead of EnsureCreatedAsync (this file, as checked in): unblocks (2), but
//      the change tracker's `Add()` still needs runtime codegen for an internal shadow-value
//      factory -- fails at the exact same "use a compiled model" message as (1), from a
//      different internal code path the compiled model doesn't cover.
// Verdict: not viable under NativeAOT with a hand-authored compiled model as of these package
// versions. See the findings doc for the fallback.
using Microsoft.EntityFrameworkCore;

string dbPath = Path.Combine(Path.GetTempPath(), $"efgate-{Guid.NewGuid():N}.db");
Console.WriteLine($"DB: {dbPath}");

try
{
    await using (var db = new ManualDbContext(dbPath))
    {
        // EnsureCreatedAsync needs EF's "design-time model" (DDL generation), which is NOT
        // covered by a compiled model and is unsupported under NativeAOT (see
        // docs/efcore-nativeaot-findings.md). Raw SQL sidesteps that and isolates whether the
        // *query* pipeline (the part the compiled model does cover) works under AOT.
        await db.Database.ExecuteSqlRawAsync(
            "CREATE TABLE \"Notes\" (\"Id\" INTEGER NOT NULL CONSTRAINT \"PK_Notes\" PRIMARY KEY AUTOINCREMENT, \"Title\" TEXT NOT NULL, \"Body\" TEXT NOT NULL);");

        db.Notes.Add(new Note { Title = "torque spec", Body = "12 ft-lb star pattern" });
        db.Notes.Add(new Note { Title = "oil weight", Body = "5W-30 synthetic" });
        int written = await db.SaveChangesAsync();
        Console.WriteLine($"Insert: {written} rows written");
    }

    await using (var db = new ManualDbContext(dbPath))
    {
        List<Note> all = await db.Notes.OrderBy(n => n.Id).ToListAsync();
        Console.WriteLine($"Read: {all.Count} rows -> {string.Join(", ", all.Select(n => n.Title))}");

        Note torque = all.First(n => n.Title == "torque spec");
        torque.Body = "12 ft-lb star pattern, retorque after 500mi";
        await db.SaveChangesAsync();
        Console.WriteLine("Update: OK");

        Note oil = await db.Notes.SingleAsync(n => n.Title == "oil weight");
        db.Notes.Remove(oil);
        await db.SaveChangesAsync();
        Console.WriteLine("Delete: OK");

        int remaining = await db.Notes.CountAsync();
        Console.WriteLine($"Final count: {remaining}");
        Console.WriteLine(remaining == 1 ? "PASS: EF Core CRUD round-tripped under NativeAOT" : "FAIL: unexpected row count");
    }
}
finally
{
    try
    {
        File.Delete(dbPath);
    }
    catch (IOException)
    {
        // Best-effort cleanup only; never mask an exception from the try block above.
    }
}

internal sealed class Note
{
    public int Id { get; set; }
    public string Title { get; set; } = "";
    public string Body { get; set; } = "";
}

internal sealed class ManualDbContext(string dbPath) : DbContext
{
    public DbSet<Note> Notes => Set<Note>();

    protected override void OnConfiguring(DbContextOptionsBuilder options) =>
        options.UseSqlite($"Data Source={dbPath}")
            .UseModel(EfGate.CompiledModels.ManualDbContextModel.Instance);
}

/// <summary>Lets `dotnet ef dbcontext optimize` construct a context at design time (tooling only).</summary>
internal sealed class ManualDbContextFactory : Microsoft.EntityFrameworkCore.Design.IDesignTimeDbContextFactory<ManualDbContext>
{
    public ManualDbContext CreateDbContext(string[] args) =>
        new(Path.Combine(Path.GetTempPath(), "efgate-designtime.db"));
}
