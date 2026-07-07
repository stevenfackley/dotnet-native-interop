using DniObservability.EngineHost;
using DotnetNativeInterop.Engine;

var builder = WebApplication.CreateBuilder(args);

// Standard Aspire dev-time wiring: OTel (logging+tracing+metrics), health checks, service discovery.
builder.AddServiceDefaults();

// The one line that makes this a *Dni.Engine* observability host rather than a generic Aspire demo:
// subscribe the engine's own ActivitySource/Meter (see EngineTrace.Source / EngineTrace.Meter, both
// named "Dni.Engine") into the same OpenTelemetry pipeline ServiceDefaults just configured. Without
// this, ConfigureOpenTelemetry()'s AddSource(ApplicationName) would only ever see spans this host's
// own ASP.NET code creates — never the engine's ffi./http./sqlite./pb./rag./agent.* spans that ship
// on-device. AddSource/AddMeter compose additively across multiple WithTracing/WithMetrics calls, so
// this does not need to touch the shared ServiceDefaults project.
builder.Services.AddOpenTelemetry()
    .WithTracing(tracing => tracing.AddSource(EngineTrace.Source.Name))
    .WithMetrics(metrics => metrics.AddMeter(EngineTrace.Meter.Name));

// Drives the engine periodically so the Aspire dashboard has something live to show. Registered as
// its own singleton (not just AddHostedService<T>, which only registers the IHostedService interface)
// so the /demo/run endpoint below can resolve the same instance to trigger an extra iteration on demand.
builder.Services.AddSingleton<EngineDriver>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<EngineDriver>());

var app = builder.Build();

app.MapDefaultEndpoints();

app.MapGet("/", () =>
    "DniObservability.EngineHost — dev-time OTel host for DotnetNativeInterop.Engine. " +
    "See the Aspire dashboard's Traces/Metrics pages for live Dni.Engine telemetry, " +
    "or POST /demo/run to trigger one iteration on demand.");

app.MapPost("/demo/run", async (EngineDriver driver, CancellationToken ct) =>
{
    var result = await driver.RunOnceAsync(ct);
    return Results.Ok(result);
});

app.Run();
