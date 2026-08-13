package com.blithe.legacysend.security;

import android.content.Context;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;

import com.blithe.legacysend.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
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

    public static TlsIdentity loadOrCreate(Context context) throws Exception {
        KeyStore store;
        X509Certificate certificate;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            store = KeyStore.getInstance(STORE_ANDROID);
            store.load(null);
            if (!store.containsAlias(ALIAS)) {
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
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", STORE_ANDROID);
                generator.initialize(spec);
                generator.generateKeyPair();
                store.load(null);
            }
            certificate = (X509Certificate) store.getCertificate(ALIAS);
        } else {
            File storeFile = new File(context.getFilesDir(), LOCAL_STORE_FILE);
            store = KeyStore.getInstance("PKCS12");

            if (storeFile.exists()) {
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(storeFile);
                    store.load(fis, LOCAL_STORE_PASS);
                } finally {
                    if (fis != null) try { fis.close(); } catch (IOException ignored) {}
                }
            } else {
                store.load(null, null);
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048, new SecureRandom());
                KeyPair keyPair = kpg.generateKeyPair();

                X509Certificate cert = generateSelfSignedCertificateLegacy(keyPair);
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
        SSLServerSocket socket = (SSLServerSocket) factory.createServerSocket(port);
        socket.setReuseAddress(true);
        socket.setWantClientAuth(true);
        enableModernTls(socket);
        return socket;
    }

    public SSLSocketFactory createPinnedClientFactory(Context context, String expectedFingerprint) throws Exception {
        SSLContext sslContext = createContext(new FingerprintTrustManager(context, expectedFingerprint));
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
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        
        char[] pass = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) ? null : LOCAL_STORE_PASS;
        keyManagers.init(keyStore, pass);
        
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), new TrustManager[] { trustManager }, new SecureRandom());
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

    private static void enableModernTls(SSLServerSocket socket) {
        List<String> supported = Arrays.asList(socket.getSupportedProtocols());
        List<String> enabled = new ArrayList<String>();
        for (String candidate : new String[] { "TLSv1.2", "TLSv1.1", "TLSv1" }) {
            if (supported.contains(candidate)) enabled.add(candidate);
        }
        if (!enabled.isEmpty()) socket.setEnabledProtocols(enabled.toArray(new String[enabled.size()]));
    }

    private static void enableModernTls(SSLSocket socket) {
        List<String> supported = Arrays.asList(socket.getSupportedProtocols());
        List<String> enabled = new ArrayList<String>();
        for (String candidate : new String[] { "TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1" }) {
            if (supported.contains(candidate)) enabled.add(candidate);
        }
        if (!enabled.isEmpty()) socket.setEnabledProtocols(enabled.toArray(new String[enabled.size()]));
    }

    private static void validateSelfSigned(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("Peer did not provide a certificate");
        }
        try {
            chain[0].checkValidity();
            chain[0].verify(chain[0].getPublicKey());
        } catch (Exception error) {
            throw new CertificateException("Peer certificate is invalid", error);
        }
    }

    private static X509Certificate generateSelfSignedCertificateLegacy(KeyPair keyPair) throws Exception {
        Date startDate = new Date();
        Date endDate = new Date(startDate.getTime() + (20L * 365 * 24 * 60 * 60 * 1000));
        BigInteger serialNumber = new BigInteger(128, new SecureRandom()).abs().add(BigInteger.ONE);
        
        X500Principal principal = new X500Principal("CN=LegacySend");
        
        javax.security.auth.x500.X500Principal subject = principal;
        return (X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(
                        createSelfSignedCertBytes(keyPair, subject, startDate, endDate, serialNumber)));
    }

    private static byte[] createSelfSignedCertBytes(KeyPair keyPair, X500Principal subject,
                                                     Date start, Date end, BigInteger serial) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        return certEncodingFallback(keyPair, subject, start, end, serial);
    }

    private static byte[] certEncodingFallback(KeyPair keyPair, X500Principal subject, Date start, Date end, BigInteger serial) throws Exception {
        return keyPair.getPublic().getEncoded();
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

    private static final class ModernTlsSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        ModernTlsSocketFactory(SSLSocketFactory delegate) { this.delegate = delegate; }

        private Socket configure(Socket socket) {
            if (socket instanceof SSLSocket) enableModernTls((SSLSocket) socket);
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
}
