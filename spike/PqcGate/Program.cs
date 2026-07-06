// Gate: does .NET 10 post-quantum crypto (MLKem/MLDsa) publish + run under NativeAOT?
// Purpose: gates a future "PQ handshake on the loopback HTTP transport" feature.
// See docs/pqc-nativeaot-findings.md.
using System.Security.Cryptography;
using System.Text;

Console.WriteLine($"MLKem.IsSupported = {MLKem.IsSupported}");
Console.WriteLine($"MLDsa.IsSupported = {MLDsa.IsSupported}");

bool kemOk = TryMlKem();
bool dsaOk = TryMlDsa();

if (kemOk && dsaOk)
{
    Console.WriteLine("PASS: ML-KEM encapsulate/decapsulate + ML-DSA sign/verify round-tripped");
}
else if (!MLKem.IsSupported && !MLDsa.IsSupported)
{
    Console.WriteLine("SKIP: platform reports IsSupported=false for both ML-KEM and ML-DSA");
}
else
{
    Console.WriteLine("FAIL: one or both PQC round-trips did not succeed (see above)");
}

static bool TryMlKem()
{
    if (!MLKem.IsSupported)
    {
        Console.WriteLine("ML-KEM: skipped, MLKem.IsSupported == false on this platform");
        return false;
    }

    try
    {
        using MLKem alice = MLKem.GenerateKey(MLKemAlgorithm.MLKem768);
        byte[] encapsulationKey = alice.ExportEncapsulationKey();
        using MLKem bob = MLKem.ImportEncapsulationKey(MLKemAlgorithm.MLKem768, encapsulationKey);

        bob.Encapsulate(out byte[] ciphertext, out byte[] sharedSecretBob);
        byte[] sharedSecretAlice = alice.Decapsulate(ciphertext);

        bool match = sharedSecretAlice.AsSpan().SequenceEqual(sharedSecretBob);
        Console.WriteLine(
            $"ML-KEM-768: ciphertext={ciphertext.Length}B secret={sharedSecretAlice.Length}B match={match}");
        return match;
    }
    catch (Exception ex)
    {
        Console.WriteLine($"ML-KEM: EXCEPTION {ex.GetType().FullName}: {ex.Message}");
        return false;
    }
}

#pragma warning disable SYSLIB5006 // MLDsa is [Experimental] in .NET 10 (finalizing against FIPS 204)
static bool TryMlDsa()
{
    if (!MLDsa.IsSupported)
    {
        Console.WriteLine("ML-DSA: skipped, MLDsa.IsSupported == false on this platform");
        return false;
    }

    try
    {
        using MLDsa signer = MLDsa.GenerateKey(MLDsaAlgorithm.MLDsa65);
        byte[] message = Encoding.UTF8.GetBytes("dni loopback handshake v1");
        byte[] signature = signer.SignData(message);
        bool verified = signer.VerifyData(message, signature);

        Console.WriteLine($"ML-DSA-65: sig={signature.Length}B verified={verified}");
        return verified;
    }
    catch (Exception ex)
    {
        Console.WriteLine($"ML-DSA: EXCEPTION {ex.GetType().FullName}: {ex.Message}");
        return false;
    }
}
#pragma warning restore SYSLIB5006
