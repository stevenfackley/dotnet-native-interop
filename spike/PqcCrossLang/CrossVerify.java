// Cross-language PQ proof — Java side (bcprov-jdk18on 1.84, the Android client's provider).
//
// Reads the .NET-produced public material, verifies the ML-DSA-65 signature over the message, and
// encapsulates to the ML-KEM-768 public key — using the SAME lightweight org.bouncycastle.crypto.* API
// the Android client (PqHandshakeClient) uses. Writes the ciphertext + shared secret back for .NET to
// decapsulate and compare. Compiled + run by run.ps1 (never the JCA "BC" provider).
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.crypto.kems.MLKEMGenerator;
import org.bouncycastle.crypto.params.MLDSAParameters;
import org.bouncycastle.crypto.params.MLDSAPublicKeyParameters;
import org.bouncycastle.crypto.params.MLKEMParameters;
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters;
import org.bouncycastle.crypto.signers.MLDSASigner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;

public final class CrossVerify {
    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        byte[] kemPublic = Files.readAllBytes(dir.resolve("kem_public.bin"));
        byte[] sigPublic = Files.readAllBytes(dir.resolve("sig_public.bin"));
        byte[] message = Files.readAllBytes(dir.resolve("message.bin"));
        byte[] signature = Files.readAllBytes(dir.resolve("signature.bin"));

        // Verify the .NET-produced ML-DSA-65 signature (pure mode, empty context).
        MLDSAPublicKeyParameters dsaPublic = new MLDSAPublicKeyParameters(MLDSAParameters.ml_dsa_65, sigPublic);
        MLDSASigner verifier = new MLDSASigner();
        verifier.init(false, dsaPublic);
        verifier.update(message, 0, message.length);
        boolean verified = verifier.verifySignature(signature);
        System.out.println("JAVA(bc1.84) verify ML-DSA-65 = " + (verified ? "OK" : "FAIL"));
        if (!verified) {
            System.exit(2);
        }

        // Encapsulate to the .NET ML-KEM-768 public key -> ciphertext (wire) + shared secret (local).
        MLKEMPublicKeyParameters kemKey = new MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, kemPublic);
        SecretWithEncapsulation enc = new MLKEMGenerator(new SecureRandom()).generateEncapsulated(kemKey);
        Files.write(dir.resolve("ciphertext.bin"), enc.getEncapsulation());
        Files.write(dir.resolve("java_secret.bin"), enc.getSecret());
        System.out.println("JAVA(bc1.84) encapsulate ML-KEM-768 = OK ct="
            + enc.getEncapsulation().length + "B secret=" + enc.getSecret().length + "B");
    }
}
