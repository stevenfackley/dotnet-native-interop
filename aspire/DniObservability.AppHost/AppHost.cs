var builder = DistributedApplication.CreateBuilder(args);

// The only orchestrated resource: a managed dev host that references DotnetNativeInterop.Engine
// directly and exports its "Dni.Engine" ActivitySource/Meter over OTLP to this dashboard. This is a
// DEV-TIME observability harness — see ../README.md for the caveat that nothing here ships on-device.
builder.AddProject<Projects.DniObservability_EngineHost>("enginehost");

builder.Build().Run();
