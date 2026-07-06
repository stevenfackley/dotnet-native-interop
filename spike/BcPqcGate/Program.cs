// Gate: does BouncyCastle's PURE MANAGED ML-KEM/ML-DSA publish + run under NativeAOT?
// Purpose: docs/pqc-nativeaot-findings.md found .NET 10's built-in MLKem/MLDsa have NO OS backend
// on iOS/Android (Apple/Android unsupported in the platform matrix). BouncyCastle implements
// FIPS 203/204 entirely in managed code, sidestepping the OS backend — if it survives NativeAOT,
// the PQ-handshake feature is back on the table for mobile with BC as the provider.
// See docs/bouncycastle-pqc-findings.md.
using System.Text;
using Org.BouncyCastle.Crypto;
using Org.BouncyCastle.Crypto.Generators;
using Org.BouncyCastle.Crypto.Kems;
using Org.BouncyCastle.Crypto.Parameters;
using Org.BouncyCastle.Crypto.Signers;
using Org.BouncyCastle.Security;

var random = new SecureRandom();

bool kemOk = TryMlKem(random);
bool dsaOk = TryMlDsa(random);

Console.WriteLine(kemOk && dsaOk
    ? "PASS: BouncyCastle ML-KEM encapsulate/decapsulate + ML-DSA sign/verify round-tripped"
    : "FAIL: one or both BC PQC round-trips did not succeed (see above)");

static bool TryMlKem(SecureRandom random)
{
    try
    {
        var generator = new MLKemKeyPairGenerator();
        generator.Init(new MLKemKeyGenerationParameters(random, MLKemParameters.ml_kem_768));
        AsymmetricCipherKeyPair keyPair = generator.GenerateKeyPair();

        var encapsulator = new MLKemEncapsulator(MLKemParameters.ml_kem_768);
        encapsulator.Init(keyPair.Public);
        byte[] ciphertext = new byte[encapsulator.EncapsulationLength];
        byte[] secretSender = new byte[encapsulator.SecretLength];
        encapsulator.Encapsulate(ciphertext, 0, ciphertext.Length, secretSender, 0, secretSender.Length);

        var decapsulator = new MLKemDecapsulator(MLKemParameters.ml_kem_768);
        decapsulator.Init(keyPair.Private);
        byte[] secretReceiver = new byte[decapsulator.SecretLength];
        decapsulator.Decapsulate(ciphertext, 0, ciphertext.Length, secretReceiver, 0, secretReceiver.Length);

        bool match = secretSender.AsSpan().SequenceEqual(secretReceiver);
        Console.WriteLine(
            $"ML-KEM-768 (BC): ciphertext={ciphertext.Length}B secret={secretSender.Length}B match={match}");
        return match;
    }
    catch (Exception ex)
    {
        Console.WriteLine($"ML-KEM (BC): EXCEPTION {ex.GetType().FullName}: {ex.Message}");
        return false;
    }
}

static bool TryMlDsa(SecureRandom random)
{
    try
    {
        var generator = new MLDsaKeyPairGenerator();
        generator.Init(new MLDsaKeyGenerationParameters(random, MLDsaParameters.ml_dsa_65));
        AsymmetricCipherKeyPair keyPair = generator.GenerateKeyPair();

        byte[] message = Encoding.UTF8.GetBytes("dni loopback handshake v1");

        var signer = new MLDsaSigner(MLDsaParameters.ml_dsa_65, deterministic: false);
        signer.Init(forSigning: true, keyPair.Private);
        signer.BlockUpdate(message, 0, message.Length);
        byte[] signature = signer.GenerateSignature();

        var verifier = new MLDsaSigner(MLDsaParameters.ml_dsa_65, deterministic: false);
        verifier.Init(forSigning: false, keyPair.Public);
        verifier.BlockUpdate(message, 0, message.Length);
        bool verified = verifier.VerifySignature(signature);

        // Negative control: a tampered message must NOT verify.
        message[0] ^= 0xFF;
        verifier.Reset();
        verifier.BlockUpdate(message, 0, message.Length);
        bool tamperedRejected = !verifier.VerifySignature(signature);

        Console.WriteLine($"ML-DSA-65 (BC): sig={signature.Length}B verified={verified} tamperedRejected={tamperedRejected}");
        return verified && tamperedRejected;
    }
    catch (Exception ex)
    {
        Console.WriteLine($"ML-DSA (BC): EXCEPTION {ex.GetType().FullName}: {ex.Message}");
        return false;
    }
}
