using System.Collections.Generic;
using System.Text.Json.Serialization;
using DotnetNativeInterop.Engine;

namespace DotnetNativeInterop.NativeBridge;

/// <summary>
/// Source-generated JSON metadata for the structured feature exports. Source generation keeps
/// serialization reflection-free, which is required for the NativeAOT shared library. CamelCase
/// matches the Swift/Kotlin Codable models (id, title, version, code, expected / result, elapsedMs, ok).
/// </summary>
[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase)]
[JsonSerializable(typeof(IReadOnlyList<FeatureDescriptor>))]
[JsonSerializable(typeof(FeatureDescriptor))]
[JsonSerializable(typeof(FeatureRun))]
internal sealed partial class FeaturesJsonContext : JsonSerializerContext
{
}
