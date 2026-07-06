// Cross-language PQ proof — .NET side (BouncyCastle.Cryptography 2.6.2, the engine's provider).
//
// Generates the server identity (ML-KEM-768 + ML-DSA-65), signs a message, and hands the PUBLIC material
// to the Java util (bcprov 1.84, the Android client's provider). Java verifies the signature and
// encapsulates to the KEM public key; this process then decapsulates the Java-produced ciphertext with
// the in-memory KEM private key and asserts the shared secret matches Java's. If it does, the two
// BouncyCastle builds are byte-compatible on the exact primitives + params the Wave B handshake uses.
//
// args: <workDir> <javaExe> <bcprovJar> <classDir>
using System.Diagnostics;
using System.Text;
using Org.BouncyCastle.Crypto.Generators;
using Org.BouncyCastle.Crypto.Kems;
using Org.BouncyCastle.Crypto.Parameters;
using Org.BouncyCastle.Crypto.Signers;
using Org.BouncyCastle.Security;

if (args.Length < 4)
{
    Console.Error.WriteLine("usage: PqcCrossLang <workDir> <javaExe> <bcprovJar> <classDir>");
    return 64;
}

var workDir = args[0];
var javaExe = args[1];
var bcprovJar = args[2];
var classDir = args[3];
Directory.CreateDirectory(workDir);

var random = new SecureRandom();

// ML-KEM-768 keypair — the server identity's key-exchange key (private stays in this process).
var kemGenerator = new MLKemKeyPairGenerator();
kemGenerator.Init(new MLKemKeyGenerationParameters(random, MLKemParameters.ml_kem_768));
var kemPair = kemGenerator.GenerateKeyPair();
var kemPublicEncoded = ((MLKemPublicKeyParameters)kemPair.Public).GetEncoded();

// ML-DSA-65 keypair — signs the handshake offer.
var dsaGenerator = new MLDsaKeyPairGenerator();
dsaGenerator.Init(new MLDsaKeyGenerationParameters(random, MLDsaParameters.ml_dsa_65));
var dsaPair = dsaGenerator.GenerateKeyPair();
var dsaPublicEncoded = ((MLDsaPublicKeyParameters)dsaPair.Public).GetEncoded();

var message = Encoding.UTF8.GetBytes("dni cross-language pq proof v1");
var signer = new MLDsaSigner(MLDsaParameters.ml_dsa_65, deterministic: false);
signer.Init(forSigning: true, dsaPair.Private);
signer.BlockUpdate(message, 0, message.Length);
var signature = signer.GenerateSignature();

File.WriteAllBytes(Path.Combine(workDir, "kem_public.bin"), kemPublicEncoded);
File.WriteAllBytes(Path.Combine(workDir, "sig_public.bin"), dsaPublicEncoded);
File.WriteAllBytes(Path.Combine(workDir, "message.bin"), message);
File.WriteAllBytes(Path.Combine(workDir, "signature.bin"), signature);
Console.WriteLine($".NET(BC 2.6.2): kemPub={kemPublicEncoded.Length}B sigPub={dsaPublicEncoded.Length}B sig={signature.Length}B");

// --- Java (bcprov 1.84) verifies the signature + encapsulates -------------------------------------
var psi = new ProcessStartInfo(javaExe)
{
    RedirectStandardOutput = true,
    RedirectStandardError = true,
    UseShellExecute = false,
};
psi.ArgumentList.Add("-cp");
psi.ArgumentList.Add($"{bcprovJar}{Path.PathSeparator}{classDir}");
psi.ArgumentList.Add("CrossVerify");
psi.ArgumentList.Add(workDir);

using var process = Process.Start(psi)!;
var stdout = process.StandardOutput.ReadToEnd();
var stderr = process.StandardError.ReadToEnd();
process.WaitForExit();
Console.Write(stdout);
if (stderr.Length > 0)
{
    Console.Error.Write(stderr);
}

if (process.ExitCode != 0)
{
    Console.WriteLine($"FAIL: Java step exited {process.ExitCode}");
    return 1;
}

// --- .NET decapsulates the Java-produced ciphertext + compares shared secrets ----------------------
var ciphertext = File.ReadAllBytes(Path.Combine(workDir, "ciphertext.bin"));
var decapsulator = new MLKemDecapsulator(MLKemParameters.ml_kem_768);
decapsulator.Init(kemPair.Private);
var dotnetSecret = new byte[decapsulator.SecretLength];
decapsulator.Decapsulate(ciphertext, 0, ciphertext.Length, dotnetSecret, 0, dotnetSecret.Length);

var javaSecret = File.ReadAllBytes(Path.Combine(workDir, "java_secret.bin"));
var match = dotnetSecret.AsSpan().SequenceEqual(javaSecret);

Console.WriteLine($".NET(BC 2.6.2) decapsulated: secret={dotnetSecret.Length}B  secrets_match={match}");
Console.WriteLine(match
    ? "PASS: .NET-signed ML-DSA-65 verified by Java; Java-encapsulated ML-KEM-768 decapsulated by .NET to the SAME shared secret"
    : "FAIL: shared secrets differ across languages");
return match ? 0 : 1;
