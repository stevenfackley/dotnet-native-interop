using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text.Json;
using OnDeviceLlm.Engine;

namespace OnDeviceLlm.NativeBridge.Ffi;

/// <summary>
/// Structured feature-catalog exports (FFI). Unlike the streaming session API, these return the
/// whole catalog and per-feature results as JSON so the native UI can render each feature as its
/// own component with live output and timing. Strings are heap UTF-8; the caller copies the text
/// then releases it with <c>ondevicellm_string_free</c>.
/// </summary>
internal static class ExportsFeatures
{
    /// <summary>Returns the feature catalog as a JSON array of descriptors. 0 on failure.</summary>
    [UnmanagedCallersOnly(EntryPoint = "ondevicellm_features_json")]
    public static nint FeaturesJson()
    {
        try
        {
            var json = JsonSerializer.Serialize(
                LanguageFeatureCatalog.Descriptors,
                typeof(IReadOnlyList<FeatureDescriptor>),
                FeaturesJsonContext.Default);
            return NativeText.Allocate(json);
        }
        catch (Exception)
        {
            return 0;
        }
    }

    /// <summary>Executes one feature by id and returns its result + timing as JSON. 0 on failure.</summary>
    [UnmanagedCallersOnly(EntryPoint = "ondevicellm_feature_run")]
    public static unsafe nint FeatureRun(byte* id)
    {
        try
        {
            var featureId = NativeText.Read((nint)id);
            var run = LanguageFeatureCatalog.Run(featureId);
            var json = JsonSerializer.Serialize(run, typeof(FeatureRun), FeaturesJsonContext.Default);
            return NativeText.Allocate(json);
        }
        catch (Exception)
        {
            return 0;
        }
    }

    /// <summary>Frees a string returned by the structured exports above.</summary>
    [UnmanagedCallersOnly(EntryPoint = "ondevicellm_string_free")]
    public static unsafe void StringFree(byte* value) => NativeText.Free((nint)value);
}
