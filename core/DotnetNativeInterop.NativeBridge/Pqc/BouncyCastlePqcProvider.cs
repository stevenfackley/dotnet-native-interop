using Org.BouncyCastle.Crypto;
using Org.BouncyCastle.Crypto.Generators;
using Org.BouncyCastle.Crypto.Kems;
using Org.BouncyCastle.Crypto.Parameters;
using Org.BouncyCastle.Crypto.Signers;
using Org.BouncyCastle.Security;

namespace DotnetNativeInterop.NativeBridge.Pqc;

/// <summary>
/// <see cref="IPqcProvider"/> over BouncyCastle's pure-managed ML-KEM-768 / ML-DSA-65 (FIPS 203/204). The
/// exact BC API calls are the ones proven AOT-clean and mobile-viable in docs/bouncycastle-pqc-findings.md
/// (spike/BcPqcGate) — reused verbatim so this feature inherits that gate's result. No OS crypto backend is
/// touched, which is why it works on iOS/Android where the built-in .NET types do not.
/// </summary>
internal sealed class BouncyCastlePqcProvider : IPqcProvider
{
    private readonly SecureRandom _random = new();

    public string KemAlgorithm => "ML-KEM-768";

    public string SigAlgorithm => "ML-DSA-65";

    public IPqcServerIdentity CreateServerIdentity()
    {
        var kemGenerator = new MLKemKeyPairGenerator();
        kemGenerator.Init(new MLKemKeyGenerationParameters(_random, MLKemParameters.ml_kem_768));
        var kemPair = kemGenerator.GenerateKeyPair();

        var dsaGenerator = new MLDsaKeyPairGenerator();
        dsaGenerator.Init(new MLDsaKeyGenerationParameters(_random, MLDsaParameters.ml_dsa_65));
        var dsaPair = dsaGenerator.GenerateKeyPair();

        return new BcServerIdentity(kemPair, dsaPair);
    }

    public bool Verify(byte[] sigPublicKey, byte[] message, byte[] signature)
    {
        var publicKey = MLDsaPublicKeyParameters.FromEncoding(MLDsaParameters.ml_dsa_65, sigPublicKey);
        var verifier = new MLDsaSigner(MLDsaParameters.ml_dsa_65, deterministic: false);
        verifier.Init(forSigning: false, publicKey);
        verifier.BlockUpdate(message, 0, message.Length);
        return verifier.VerifySignature(signature);
    }

    public PqcEncapsulation Encapsulate(byte[] kemPublicKey)
    {
        var publicKey = MLKemPublicKeyParameters.FromEncoding(MLKemParameters.ml_kem_768, kemPublicKey);
        var encapsulator = new MLKemEncapsulator(MLKemParameters.ml_kem_768);
        encapsulator.Init(publicKey);

        var ciphertext = new byte[encapsulator.EncapsulationLength];
        var secret = new byte[encapsulator.SecretLength];
        encapsulator.Encapsulate(ciphertext, 0, ciphertext.Length, secret, 0, secret.Length);
        return new PqcEncapsulation(ciphertext, secret);
    }

    // Holds the BC key objects for one server boot. Private keys stay here; only encoded public keys escape.
    private sealed class BcServerIdentity : IPqcServerIdentity
    {
        private readonly AsymmetricCipherKeyPair _kemPair;
        private readonly AsymmetricCipherKeyPair _dsaPair;

        internal BcServerIdentity(AsymmetricCipherKeyPair kemPair, AsymmetricCipherKeyPair dsaPair)
        {
            _kemPair = kemPair;
            _dsaPair = dsaPair;
            KemPublicKey = ((MLKemPublicKeyParameters)kemPair.Public).GetEncoded();
            SigPublicKey = ((MLDsaPublicKeyParameters)dsaPair.Public).GetEncoded();
        }

        public byte[] KemPublicKey { get; }

        public byte[] SigPublicKey { get; }

        public byte[] Sign(byte[] message)
        {
            var signer = new MLDsaSigner(MLDsaParameters.ml_dsa_65, deterministic: false);
            signer.Init(forSigning: true, _dsaPair.Private);
            signer.BlockUpdate(message, 0, message.Length);
            return signer.GenerateSignature();
        }

        public byte[] Decapsulate(byte[] ciphertext)
        {
            var decapsulator = new MLKemDecapsulator(MLKemParameters.ml_kem_768);
            decapsulator.Init(_kemPair.Private);
            var secret = new byte[decapsulator.SecretLength];
            decapsulator.Decapsulate(ciphertext, 0, ciphertext.Length, secret, 0, secret.Length);
            return secret;
        }
    }
}
