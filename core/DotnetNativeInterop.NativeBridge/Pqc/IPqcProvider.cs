namespace DotnetNativeInterop.NativeBridge.Pqc;

/// <summary>
/// The post-quantum crypto seam for the framed-protobuf secure channel. Kept byte-oriented (wire-friendly)
/// and provider-agnostic so the concrete backend can change without touching the transport.
///
/// The shipping backend is <see cref="BouncyCastlePqcProvider"/> — BouncyCastle's pure-managed FIPS 203/204
/// (proven AOT-clean and mobile-viable in docs/bouncycastle-pqc-findings.md), chosen because .NET 10's
/// built-in <c>System.Security.Cryptography.MLKem</c>/<c>MLDsa</c> have no OS backend on iOS/Android
/// (docs/pqc-nativeaot-findings.md). This seam exists precisely so those OS types can be dropped in on
/// platforms where <c>IsSupported</c> becomes true — implement <see cref="IPqcProvider"/> over them and
/// swap the construction site; the handshake and transport code stay unchanged.
/// </summary>
internal interface IPqcProvider
{
    /// <summary>KEM algorithm label for the Trust inspector (e.g. <c>ML-KEM-768</c>).</summary>
    string KemAlgorithm { get; }

    /// <summary>Signature algorithm label for the Trust inspector (e.g. <c>ML-DSA-65</c>).</summary>
    string SigAlgorithm { get; }

    /// <summary>Creates a per-boot server identity: a KEM keypair (for decapsulation) + a DSA keypair (to sign the offer).</summary>
    IPqcServerIdentity CreateServerIdentity();

    /// <summary>Client side: verifies the DSA signature over <paramref name="message"/> with the server's DSA public key.</summary>
    bool Verify(byte[] sigPublicKey, byte[] message, byte[] signature);

    /// <summary>Client side: encapsulates to the server's KEM public key, yielding the ciphertext + shared secret.</summary>
    PqcEncapsulation Encapsulate(byte[] kemPublicKey);
}

/// <summary>A server's per-boot key material. Private keys never leave this object; only the encoded public keys do.</summary>
internal interface IPqcServerIdentity
{
    /// <summary>Encoded ML-KEM public key sent in the handshake offer.</summary>
    byte[] KemPublicKey { get; }

    /// <summary>Encoded ML-DSA public key sent in the handshake offer (lets the client verify the signature).</summary>
    byte[] SigPublicKey { get; }

    /// <summary>Signs <paramref name="message"/> (the KEM public key) with the identity's ML-DSA private key.</summary>
    byte[] Sign(byte[] message);

    /// <summary>Decapsulates the client's ML-KEM ciphertext with the identity's KEM private key, yielding the shared secret.</summary>
    byte[] Decapsulate(byte[] ciphertext);
}

/// <summary>The output of a client encapsulation: the wire ciphertext and the derived shared secret.</summary>
internal sealed record PqcEncapsulation(byte[] Ciphertext, byte[] SharedSecret);
