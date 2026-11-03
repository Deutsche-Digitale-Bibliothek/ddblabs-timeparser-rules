package de.deutsche_digitale_bibliothek.timeparser.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Prüft die tatsächlich vom eingebetteten Tomcat gesendeten Location-Header. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReverseProxyRedirectTest {

    private static final String CONTEXT_PATH = "/app/timeparser-rules";
    private static final String PUBLIC_HOST = "labs.deutsche-digitale-bibliothek.de";
    private static final URI PUBLIC_ORIGIN = URI.create("https://" + PUBLIC_HOST);
    private static final Pattern CSRF_TOKEN = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Path TEST_FILE_PREFIX = Path.of(System.getProperty("java.io.tmpdir"),
            "timeparser-rules-proxy-" + UUID.randomUUID());

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("timeparser.database.path", () -> TEST_FILE_PREFIX + ".sqlite");
        registry.add("timeparser.csv.rules-path", () -> TEST_FILE_PREFIX + "-missing-rules.csv");
        registry.add("timeparser.csv.tests-path", () -> TEST_FILE_PREFIX + "-missing-tests.csv");
        registry.add("timeparser.security.username", () -> "proxy-test");
        registry.add("timeparser.security.password", () -> "proxy-test-password");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "/rules"})
    void keepsProxyOriginWhenOnlyThePublicHostAndPortAreAvailable(String suffix) throws Exception {
        // Der gemeldete Backend-Request enthält einen öffentlichen Host mit Port 443,
        // erreicht Tomcat aber über HTTP. Ein absoluter Redirect würde http:443 erzeugen.
        Redirect response = proxyGet(suffix, false, 443);

        assertRedirect(response, CONTEXT_PATH + "/login", PUBLIC_ORIGIN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "/rules"})
    void keepsProxyOriginWithForwardedHttpsHeaders(String suffix) throws Exception {
        assertRedirect(proxyGet(suffix, true, 443), CONTEXT_PATH + "/login", PUBLIC_ORIGIN);
    }

    @Test
    void preservesANonDefaultPublicHttpsPort() throws Exception {
        assertRedirect(proxyGet("/rules", true, 8443), CONTEXT_PATH + "/login",
                URI.create("https://" + PUBLIC_HOST + ":8443"));
    }

    @Test
    void keepsDirectLocalHttpAccessOnItsOwnOrigin() throws Exception {
        try (HttpClient client = client()) {
            var response = client.send(HttpRequest.newBuilder(localUri("/rules")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertRedirect(redirect(response), CONTEXT_PATH + "/login", URI.create("http://127.0.0.1:" + port));
        }
    }

    @Test
    void keepsLoginNavigationAndLogoutOnThePublicContextPath() throws Exception {
        try (HttpClient client = client()) {
            var loginPage = client.send(proxyRequest("/login").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(loginPage.statusCode()).isEqualTo(200);
            String loginBody = "username=proxy-test&password=proxy-test-password&_csrf=" + encode(csrf(loginPage.body()));
            var login = client.send(proxyRequest("/login")
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(loginBody)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertRedirect(redirect(login), CONTEXT_PATH + "/rules", PUBLIC_ORIGIN);

            var navigation = client.send(proxyRequest("/").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertRedirect(redirect(navigation), CONTEXT_PATH + "/rules", PUBLIC_ORIGIN);

            var rules = client.send(proxyRequest("/rules").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(rules.statusCode()).isEqualTo(200);
            var logout = client.send(proxyRequest("/logout")
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + encode(csrf(rules.body())))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertRedirect(redirect(logout), CONTEXT_PATH + "/login?logout", PUBLIC_ORIGIN);
        }
    }

    private HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private HttpRequest.Builder proxyRequest(String suffix) {
        return HttpRequest.newBuilder(localUri(suffix)).timeout(Duration.ofSeconds(10))
                .header("X-Forwarded-Host", PUBLIC_HOST).header("X-Forwarded-Port", "443");
    }

    private URI localUri(String suffix) {
        return URI.create("http://127.0.0.1:" + port + CONTEXT_PATH + suffix);
    }

    /** Ein Socket erlaubt den exakten Host-Header, den java.net.http absichtlich sperrt. */
    private Redirect proxyGet(String suffix, boolean forwardedHttps, int publicPort) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(10000);
            String request = "GET " + CONTEXT_PATH + suffix + " HTTP/1.1\r\n"
                    + "Host: " + PUBLIC_HOST + ":" + publicPort + "\r\n"
                    + "X-Forwarded-Host: " + PUBLIC_HOST + "\r\n"
                    + "X-Forwarded-Port: " + publicPort + "\r\n"
                    + (forwardedHttps ? "X-Forwarded-Proto: https\r\n" : "")
                    + "Connection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = reader.readLine();
            assertThat(statusLine).isNotNull();
            int status = Integer.parseInt(statusLine.split(" ")[1]);
            String location = null;
            for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {
                if (line.regionMatches(true, 0, "Location:", 0, "Location:".length())) {
                    location = line.substring("Location:".length()).strip();
                }
            }
            return new Redirect(status, location);
        }
    }

    private Redirect redirect(HttpResponse<?> response) {
        return new Redirect(response.statusCode(), response.headers().firstValue("Location").orElse(null));
    }

    private void assertRedirect(Redirect response, String expectedPath, URI origin) {
        assertThat(response.status()).isEqualTo(302);
        assertThat(response.location()).isEqualTo(expectedPath);
        assertThat(origin.resolve(response.location())).isEqualTo(URI.create(origin + expectedPath));
    }

    private String csrf(String html) {
        var matcher = CSRF_TOKEN.matcher(html);
        assertThat(matcher.find()).as("CSRF-Feld im gerenderten Formular").isTrue();
        return matcher.group(1);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Redirect(int status, String location) {
    }
}
