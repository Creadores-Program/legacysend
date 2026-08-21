package com.blithe.legacysend.security;

import android.content.Context;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.blithe.legacysend.R;

import org.conscrypt.Conscrypt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

public final class TlsIdentity {
    private static final String STORE_ANDROID = "AndroidKeyStore";
    private static final String ALIAS = "legacysend-device-identity";
    private static final String LOCAL_STORE_FILE = "legacysend_identity.p12";
    private static final char[] LOCAL_STORE_PASS = "legacysend_pass".toCharArray();

    private final KeyStore keyStore;
    private final String fingerprint;

    private TlsIdentity(KeyStore keyStore, String fingerprint) {
        this.keyStore = keyStore;
        this.fingerprint = fingerprint;
    }

    private static void loadOrCreatePartM(KeyPairGenerator generator) throws Exception {
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT | KeyProperties.PURPOSE_ENCRYPT)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            .build();
        generator.initialize(spec);
    }

    private static KeyPairGenerator getKeyGeneratorM() throws Exception {
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, STORE_ANDROID);
    }

    private static X509Certificate loadOrCreatePartJMr2(KeyStore store, Context context) throws Exception {
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = null;
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
                generator = getKeyGeneratorM();
                loadOrCreatePartM(generator);
            }else{
                Calendar start = Calendar.getInstance();
                Calendar end = Calendar.getInstance();
                end.add(Calendar.YEAR, 20);
                KeyPairGeneratorSpec spec = new KeyPairGeneratorSpec.Builder(context)
                    .setAlias(ALIAS)
                    .setSubject(new X500Principal("CN=LegacySend"))
                    .setSerialNumber(new BigInteger(128, new SecureRandom()).abs().add(BigInteger.ONE))
                    .setStartDate(start.getTime())
                    .setEndDate(end.getTime())
                    .build();
                generator = KeyPairGenerator.getInstance("RSA", STORE_ANDROID);
                generator.initialize(spec);
            }
            generator.generateKeyPair();
            store.load(null);
        }
        return (X509Certificate) store.getCertificate(ALIAS);
    }

    public static TlsIdentity loadOrCreate(Context context) throws Exception {
        KeyStore store;
        X509Certificate certificate;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            store = KeyStore.getInstance(STORE_ANDROID);
            store.load(null);
            certificate = loadOrCreatePartJMr2(store, context);
        } else {
            File storeFile = new File(context.getFilesDir(), LOCAL_STORE_FILE);
            store = KeyStore.getInstance("BKS");

            if (storeFile.exists()) {
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(storeFile);
                    store.load(fis, LOCAL_STORE_PASS);
                } catch (Exception e) {
                    storeFile.delete();
                    store.load(null, LOCAL_STORE_PASS);
                } finally {
                    if (fis != null) try { fis.close(); } catch (IOException ignored) {}
                }
            }else{
                store.load(null, LOCAL_STORE_PASS);
            }
            
            if (!store.containsAlias(ALIAS)) {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048, new SecureRandom());
                KeyPair keyPair = kpg.generateKeyPair();

                X509Certificate cert = generateSelfSignedCert(keyPair);
                store.setKeyEntry(ALIAS, keyPair.getPrivate(), LOCAL_STORE_PASS, new Certificate[]{cert});

                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(storeFile);
                    store.store(fos, LOCAL_STORE_PASS);
                } finally {
                    if (fos != null) try { fos.close(); } catch (IOException ignored) {}
                }
            }
            certificate = (X509Certificate) store.getCertificate(ALIAS);
        }

        return new TlsIdentity(store, hex(MessageDigest.getInstance("SHA-256")
                .digest(certificate.getEncoded())));
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public SSLServerSocket createServerSocket(int port) throws Exception {
        SSLContext context = createContext(new AcceptAllTrustManager());
        SSLServerSocketFactory factory = context.getServerSocketFactory();
        
        if (Conscrypt.isConscrypt(factory)) {
            Conscrypt.setUseEngineSocket(factory, true);
        }

        SSLServerSocket socket = (SSLServerSocket) factory.createServerSocket(port);
        socket.setReuseAddress(true);
        socket.setWantClientAuth(false);
        enableModernTlsServer(socket);
        return socket;
    }

    public SSLSocketFactory createPinnedClientFactory(Context context, String expectedFingerprint) throws Exception {
        char[] pass = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) ? null : LOCAL_STORE_PASS;

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, pass);
        final javax.net.ssl.X509KeyManager defaultKm = (javax.net.ssl.X509KeyManager) kmf.getKeyManagers()[0];

        javax.net.ssl.KeyManager[] customKeyManagers = new javax.net.ssl.KeyManager[] {
            new javax.net.ssl.X509KeyManager() {
                @Override
                public String chooseClientAlias(String[] keyType, java.security.Principal[] issuers, java.net.Socket socket) {
                    try {
                        java.util.Enumeration<String> aliases = keyStore.aliases();
                        if (aliases.hasMoreElements()) {
                            return aliases.nextElement();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    return ALIAS;
                }

                @Override
                public String chooseServerAlias(String keyType, java.security.Principal[] issuers, java.net.Socket socket) {
                    return defaultKm.chooseServerAlias(keyType, issuers, socket);
                }

                @Override
                public java.security.cert.X509Certificate[] getCertificateChain(String alias) {
                    return defaultKm.getCertificateChain(alias);
                }

                @Override
                public String[] getClientAliases(String keyType, java.security.Principal[] issuers) {
                    return defaultKm.getClientAliases(keyType, issuers);
                }

                @Override
                public java.security.PrivateKey getPrivateKey(String alias) {
                    return defaultKm.getPrivateKey(alias);
                }

                @Override
                public String[] getServerAliases(String keyType, java.security.Principal[] issuers) {
                    return defaultKm.getServerAliases(keyType, issuers);
                }
            }
        };

        TrustManager[] trustManagers = new TrustManager[] { new FingerprintTrustManager(context, expectedFingerprint) };

        SSLContext sslContext;
        Provider conscryptProvider = Security.getProvider("Conscrypt");
        if (conscryptProvider != null) {
            sslContext = SSLContext.getInstance("TLS", conscryptProvider);
        } else {
            sslContext = SSLContext.getInstance("TLS");
        }
        
        sslContext.init(customKeyManagers, trustManagers, new SecureRandom());

        return new ModernTlsSocketFactory(sslContext.getSocketFactory());
    }

    public static HostnameVerifier pinnedHostnameVerifier() {
        return new HostnameVerifier() {
            @Override public boolean verify(String hostname, SSLSession session) {
                return true;
            }
        };
    }

    private SSLContext createContext(X509TrustManager trustManager) throws Exception {
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        
        char[] pass = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) ? null : LOCAL_STORE_PASS;
        keyManagerFactory.init(keyStore, pass);
        KeyManager[] keyManagers = keyManagerFactory.getKeyManagers();

        SSLContext context;
        Provider conscryptProvider = Security.getProvider("Conscrypt");
        if (conscryptProvider != null) {
            context = SSLContext.getInstance("TLS", conscryptProvider);
        } else {
            context = SSLContext.getInstance("TLS");
        }
        
        context.init(keyManagers, new TrustManager[] { trustManager }, new SecureRandom());
        return context;
    }

    public static String peerFingerprint(SSLSession session) {
        try {
            X509Certificate certificate = (X509Certificate) session.getPeerCertificates()[0];
            return hex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.US, "%02X", value));
        return result.toString();
    }

    private static void enableModernTlsServer(SSLServerSocket socket) {
        List<String> supported = Arrays.asList(socket.getSupportedProtocols());
        List<String> enabled = new ArrayList<String>();
        for (String candidate : new String[] { "TLSv1.3", "TLSv1.2" }) {
            if (supported.contains(candidate)) enabled.add(candidate);
        }
        if (!enabled.isEmpty()) socket.setEnabledProtocols(enabled.toArray(new String[enabled.size()]));
    }

    private static void enableModernTlsClient(SSLSocket socket) {
        List<String> supported = Arrays.asList(socket.getSupportedProtocols());
        List<String> enabled = new ArrayList<String>();
        for (String candidate : new String[] { "TLSv1.3", "TLSv1.2" }) {
            if (supported.contains(candidate)) enabled.add(candidate);
        }
        if (!enabled.isEmpty()) socket.setEnabledProtocols(enabled.toArray(new String[enabled.size()]));
    }

    private static void validateSelfSigned(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("Peer did not provide a certificate");
        }
    }

    private static X509Certificate generateSelfSignedCert(KeyPair keyPair) throws Exception {
        long now = System.currentTimeMillis();
        Date startDate = new Date(now - 86400000L);
        Date endDate = new Date(now + (20L * 365 * 24 * 60 * 60 * 1000));
        BigInteger serialNumber = BigInteger.valueOf(now);

        byte[] certBytes = SimpleX509Generator.generate(
                keyPair, "CN=LegacySend", serialNumber, startDate, endDate);

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));
    }

    private static final class AcceptAllTrustManager implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException { validateSelfSigned(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException { validateSelfSigned(chain); }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    private static final class FingerprintTrustManager implements X509TrustManager {
        private final Context context;
        private final String expected;

        FingerprintTrustManager(Context context, String expected) {
            this.context = context;
            this.expected = normalize(expected);
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException { validateSelfSigned(chain); }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain == null || chain.length == 0) {
                String msg = context != null ? context.getString(R.string.error_cert_missing) : "Peer provided no certificate";
                throw new CertificateException(msg);
            }
            try {
                String actual = hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()));
                if (!actual.equals(expected)) {
                    String msg = context != null ? context.getString(R.string.error_cert_fingerprint_mismatch) : "Certificate fingerprint mismatch";
                    throw new CertificateException(msg);
                }
            } catch (CertificateException error) {
                throw error;
            } catch (Exception error) {
                String msg = context != null ? context.getString(R.string.error_cert_verify_failed) : "Failed to verify certificate fingerprint";
                throw new CertificateException(msg, error);
            }
        }

        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }

        private static String normalize(String value) {
            return value == null ? "" : value.replace(":", "").trim().toUpperCase(java.util.Locale.US);
        }
    }

    public static final class ModernTlsSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        public ModernTlsSocketFactory(SSLSocketFactory delegate) { 
            this.delegate = delegate; 
            if (Conscrypt.isConscrypt(delegate)) {
                Conscrypt.setUseEngineSocket(delegate, true);
            }
        }

        private Socket configure(Socket socket) {
            if (socket instanceof SSLSocket) {
                enableModernTlsClient((SSLSocket) socket);
            }
            return socket;
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return configure(delegate.createSocket(s, host, port, autoClose));
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return configure(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            return configure(delegate.createSocket(host, port, local, localPort));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return configure(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort)
                throws IOException {
            return configure(delegate.createSocket(address, port, local, localPort));
        }
    }

    private static class SimpleX509Generator {
        public static byte[] generate(KeyPair keyPair, String dn, BigInteger serial, Date notBefore, Date notAfter) throws Exception {
            byte[] tbs = buildTBSCertificate(serial, dn, notBefore, notAfter, keyPair.getPublic());
            
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(keyPair.getPrivate());
            sig.update(tbs);
            byte[] signature = sig.sign();

            ByteArrayOutputStream cert = new ByteArrayOutputStream();
            cert.write(0x30);
            byte[] body = cat(tbs, SHA256_WITH_RSA_ALG_ID, encodeBitString(signature));
            writeLength(cert, body.length);
            cert.write(body);
            return cert.toByteArray();
        }

        private static byte[] buildTBSCertificate(BigInteger serial, String dn, Date notBefore, Date notAfter, PublicKey pubKey) throws Exception {
            ByteArrayOutputStream tbs = new ByteArrayOutputStream();
            
            byte[] version = new byte[]{ (byte) 0xA0, 0x03, 0x02, 0x01, 0x02 };
            byte[] serialBytes = encodeInteger(serial);
            byte[] dnBytes = encodeDN(dn);
            byte[] validity = encodeValidity(notBefore, notAfter);
            byte[] pubKeyBytes = pubKey.getEncoded();

            byte[] body = cat(version, serialBytes, SHA256_WITH_RSA_ALG_ID, dnBytes, validity, dnBytes, pubKeyBytes);
            
            tbs.write(0x30);
            writeLength(tbs, body.length);
            tbs.write(body);
            return tbs.toByteArray();
        }

        private static final byte[] SHA256_WITH_RSA_ALG_ID = new byte[] {
            0x30, 0x0D, 0x06, 0x09, 0x2A, (byte)0x86, 0x48, (byte)0x86, (byte)0xF7, 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00
        };

        private static byte[] encodeInteger(BigInteger val) throws IOException {
            byte[] raw = val.toByteArray();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x02);
            writeLength(out, raw.length);
            out.write(raw);
            return out.toByteArray();
        }

        private static byte[] encodeBitString(byte[] data) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x03);
            writeLength(out, data.length + 1);
            out.write(0x00);
            out.write(data);
            return out.toByteArray();
        }

        private static byte[] encodeDN(String cn) throws IOException {
            String name = cn.startsWith("CN=") ? cn.substring(3) : cn;
            byte[] nameBytes = name.getBytes("UTF-8");
            
            ByteArrayOutputStream attr = new ByteArrayOutputStream();
            attr.write(0x06); // OID 2.5.4.3 (commonName)
            attr.write(0x03);
            attr.write(new byte[]{0x55, 0x04, 0x03});
            attr.write(0x0C); // UTF8String
            writeLength(attr, nameBytes.length);
            attr.write(nameBytes);
            byte[] innerBytes = attr.toByteArray();

            ByteArrayOutputStream atvSeq = new ByteArrayOutputStream();
            atvSeq.write(0x30); // SEQUENCE
            writeLength(atvSeq, innerBytes.length);
            atvSeq.write(innerBytes);
            byte[] atvSeqBytes = atvSeq.toByteArray();

            ByteArrayOutputStream set = new ByteArrayOutputStream();
            set.write(0x31); // SET
            writeLength(set, atvSeqBytes.length);
            set.write(atvSeqBytes);

            ByteArrayOutputStream seq = new ByteArrayOutputStream();
            seq.write(0x30); // SEQUENCE
            byte[] setBytes = set.toByteArray();
            writeLength(seq, setBytes.length);
            seq.write(setBytes);

            return seq.toByteArray();
        }

        private static byte[] encodeValidity(Date start, Date end) throws IOException {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US);
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            
            byte[] b1 = sdf.format(start).getBytes("ASCII");
            byte[] b2 = sdf.format(end).getBytes("ASCII");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0x30);
            out.write(b1.length + b2.length + 4);
            out.write(0x17); out.write(b1.length); out.write(b1);
            out.write(0x17); out.write(b2.length); out.write(b2);
            return out.toByteArray();
        }

        private static void writeLength(ByteArrayOutputStream out, int len) {
            if (len < 128) {
                out.write(len);
            } else if (len < 256) {
                out.write(0x81);
                out.write(len);
            } else {
                out.write(0x82);
                out.write((len >> 8) & 0xFF);
                out.write(len & 0xFF);
            }
        }

        private static byte[] cat(byte[]... arrays) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] arr : arrays) out.write(arr);
            return out.toByteArray();
        }
    }
}
