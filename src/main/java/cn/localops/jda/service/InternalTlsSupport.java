package cn.localops.jda.service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;

public final class InternalTlsSupport {
    private static final String CERTIFICATE_RESOURCE = "/certificates/company-gerrit-internal-root-ca.pem";
    private static final String CERTIFICATE_ALIAS = "company-gerrit-internal-root-ca";
    private static final String EXPECTED_SHA256 =
            "D6A58354C9585E7CAF37B0BBC78C613DA61E9EE639892B4DBC9E7E4A4C5AC44B";
    private static final char[] AGENT_TRUSTSTORE_PASSWORD = "JenkinsAgentLocalTrust".toCharArray();

    private InternalTlsSupport() {
    }

    public static HttpClient newHttpClient(Duration connectTimeout) {
        return newHttpClient(connectTimeout, null);
    }

    public static HttpClient newHttpClient(Duration connectTimeout, CookieHandler cookieHandler) {
        try {
            X509TrustManager publicRoots = trustManager(null);
            X509TrustManager companyRoot = trustManager(companyKeyStore());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new CompositeTrustManager(publicRoots, companyRoot)}, new SecureRandom());
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .version(HttpClient.Version.HTTP_1_1)
                    .sslContext(context);
            if (cookieHandler != null) {
                builder.cookieHandler(cookieHandler);
            }
            return builder.build();
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException("无法初始化公司 HTTPS 证书信任链。", exception);
        }
    }

    public static Path writeAgentTrustStore(Path agentDirectory) throws IOException {
        Path destination = agentDirectory.resolve("company-truststore.p12");
        try {
            KeyStore keyStore = companyKeyStore();
            Files.createDirectories(destination.getParent());
            try (var output = Files.newOutputStream(destination)) {
                keyStore.store(output, AGENT_TRUSTSTORE_PASSWORD);
            }
            return destination;
        } catch (GeneralSecurityException exception) {
            throw new IOException("无法生成 Jenkins Agent HTTPS 信任库。", exception);
        }
    }

    static char[] agentTrustStorePassword() {
        return AGENT_TRUSTSTORE_PASSWORD.clone();
    }

    static String companyRootSha256() {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(companyCertificate().getEncoded()));
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException("无法校验公司根证书。", exception);
        }
    }

    private static KeyStore companyKeyStore() throws GeneralSecurityException, IOException {
        X509Certificate certificate = companyCertificate();
        String fingerprint = HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        if (!EXPECTED_SHA256.equals(fingerprint)) {
            throw new CertificateException("公司根证书指纹不匹配。");
        }
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, AGENT_TRUSTSTORE_PASSWORD);
        keyStore.setCertificateEntry(CERTIFICATE_ALIAS, certificate);
        return keyStore;
    }

    private static X509Certificate companyCertificate() throws IOException, CertificateException {
        try (InputStream input = InternalTlsSupport.class.getResourceAsStream(CERTIFICATE_RESOURCE)) {
            if (input == null) {
                throw new IOException("应用缺少公司根证书资源。");
            }
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static X509TrustManager trustManager(KeyStore keyStore) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore);
        return Arrays.stream(factory.getTrustManagers())
                .filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast)
                .findFirst()
                .orElseThrow(() -> new GeneralSecurityException("系统没有可用的 X.509 信任管理器。"));
    }

    private record CompositeTrustManager(X509TrustManager publicRoots, X509TrustManager companyRoot)
            implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            publicRoots.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                publicRoots.checkServerTrusted(chain, authType);
            } catch (CertificateException publicFailure) {
                try {
                    companyRoot.checkServerTrusted(chain, authType);
                } catch (CertificateException companyFailure) {
                    companyFailure.addSuppressed(publicFailure);
                    throw companyFailure;
                }
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] first = publicRoots.getAcceptedIssuers();
            X509Certificate[] second = companyRoot.getAcceptedIssuers();
            X509Certificate[] combined = Arrays.copyOf(first, first.length + second.length);
            System.arraycopy(second, 0, combined, first.length, second.length);
            return combined;
        }
    }
}
